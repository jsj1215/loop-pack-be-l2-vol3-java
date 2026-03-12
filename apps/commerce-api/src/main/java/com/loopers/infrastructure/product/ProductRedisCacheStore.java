package com.loopers.infrastructure.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductCacheStore;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductSearchCondition;
import com.loopers.domain.product.ProductStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Redis 기반 상품 캐시 저장소 구현체.
 *
 * - 모든 Redis 호출을 try-catch로 감싸서, Redis 장애 시에도 DB fallback으로 서비스가 정상 동작한다.
 * - Domain 객체(Product)를 내부 직렬화 record로 변환하여 JSON으로 저장하고,
 *   조회 시 다시 Domain 객체로 복원한다.
 */
@Slf4j
@RequiredArgsConstructor
@Primary
@Component
public class ProductRedisCacheStore implements ProductCacheStore {

    private static final String DETAIL_KEY_PREFIX = "product:detail:";
    private static final String SEARCH_KEY_PREFIX = "product:search:";
    private static final Duration DETAIL_TTL = Duration.ofMinutes(10);
    private static final Duration SEARCH_TTL = Duration.ofMinutes(3);
    private static final int MAX_CACHEABLE_PAGE = 2;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    // ==================== 상품 상세 캐시 ====================

    @Override
    public Optional<Product> getDetail(Long productId) {
        try {
            String json = redisTemplate.opsForValue().get(detailKey(productId));
            if (json == null) {
                return Optional.empty();
            }
            CachedProductData data = objectMapper.readValue(json, CachedProductData.class);
            return Optional.of(data.toProduct());
        } catch (Exception e) {
            log.warn("Redis 상품 상세 조회 실패: productId={}", productId, e);
            return Optional.empty();
        }
    }

    @Override
    public void setDetail(Long productId, Product product) {
        try {
            String json = objectMapper.writeValueAsString(CachedProductData.from(product));
            redisTemplate.opsForValue().set(detailKey(productId), json, DETAIL_TTL);
        } catch (Exception e) {
            log.warn("Redis 상품 상세 저장 실패: productId={}", productId, e);
        }
    }

    @Override
    public void evictDetail(Long productId) {
        try {
            redisTemplate.delete(detailKey(productId));
        } catch (Exception e) {
            log.warn("Redis 상품 상세 캐시 삭제 실패: productId={}", productId, e);
        }
    }

    // ==================== 상품 목록 캐시 ====================

    @Override
    public Optional<Page<Product>> getSearch(ProductSearchCondition condition, Pageable pageable) {
        if (!isCacheablePage(pageable.getPageNumber())) {
            return Optional.empty();
        }
        try {
            String cacheKey = buildSearchKey(condition, pageable);
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json == null) {
                return Optional.empty();
            }
            CachedSearchPage cached = objectMapper.readValue(json, CachedSearchPage.class);
            List<Product> products = cached.content().stream()
                    .map(CachedProductData::toProduct)
                    .toList();
            return Optional.of(new PageImpl<>(products, pageable, cached.totalElements()));
        } catch (Exception e) {
            log.warn("Redis 상품 목록 조회 실패: condition={}", condition, e);
            return Optional.empty();
        }
    }

    @Override
    public void setSearch(ProductSearchCondition condition, Pageable pageable, Page<Product> products) {
        if (!isCacheablePage(pageable.getPageNumber())) {
            return;
        }
        try {
            String cacheKey = buildSearchKey(condition, pageable);
            List<CachedProductData> content = products.getContent().stream()
                    .map(CachedProductData::from)
                    .toList();
            String json = objectMapper.writeValueAsString(new CachedSearchPage(content, products.getTotalElements()));
            redisTemplate.opsForValue().set(cacheKey, json, SEARCH_TTL);
        } catch (Exception e) {
            log.warn("Redis 상품 목록 저장 실패: condition={}", condition, e);
        }
    }

    // ==================== 내부 유틸리티 ====================

    private boolean isCacheablePage(int pageNumber) {
        return pageNumber <= MAX_CACHEABLE_PAGE;
    }

    private String buildSearchKey(ProductSearchCondition condition, Pageable pageable) {
        String keywordPart = condition.keyword() != null ? condition.keyword() : "all";
        String brandPart = condition.brandId() != null ? condition.brandId().toString() : "all";
        return SEARCH_KEY_PREFIX + keywordPart + ":" + condition.sort().name()
                + ":" + brandPart + ":" + pageable.getPageNumber() + ":" + pageable.getPageSize();
    }

    private String detailKey(Long productId) {
        return DETAIL_KEY_PREFIX + productId;
    }

    // ==================== 직렬화/역직렬화용 내부 record ====================

    record CachedProductData(
            Long id, Long brandId, String brandName,
            String name, int price, int supplyPrice, int discountPrice,
            int shippingFee, int likeCount, String description,
            MarginType marginType, ProductStatus status, String displayYn,
            List<CachedOptionData> options, ZonedDateTime createdAt
    ) {
        static CachedProductData from(Product product) {
            List<CachedOptionData> cachedOptions = product.getOptions().stream()
                    .map(CachedOptionData::from)
                    .toList();
            return new CachedProductData(
                    product.getId(), product.getBrand().getId(), product.getBrand().getName(),
                    product.getName(), product.getPrice(), product.getSupplyPrice(), product.getDiscountPrice(),
                    product.getShippingFee(), product.getLikeCount(), product.getDescription(),
                    product.getMarginType(), product.getStatus(), product.getDisplayYn(),
                    cachedOptions, product.getCreatedAt()
            );
        }

        Product toProduct() {
            Brand brand = Brand.restoreFromCache(brandId, brandName);

            List<ProductOption> productOptions = options.stream()
                    .map(CachedOptionData::toProductOption)
                    .toList();

            return Product.restoreFromCache(id, brand, name, price, supplyPrice, discountPrice,
                    shippingFee, likeCount, description, marginType, status, displayYn,
                    productOptions, createdAt);
        }
    }

    record CachedOptionData(Long id, Long productId, String optionName, int stockQuantity) {
        static CachedOptionData from(ProductOption option) {
            return new CachedOptionData(option.getId(), option.getProductId(),
                    option.getOptionName(), option.getStockQuantity());
        }

        ProductOption toProductOption() {
            return ProductOption.restoreFromCache(id, productId, optionName, stockQuantity);
        }
    }

    record CachedSearchPage(List<CachedProductData> content, long totalElements) {
    }

}
