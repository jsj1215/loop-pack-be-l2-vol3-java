package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductCacheStore;
import com.loopers.domain.product.ProductSearchCondition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Caffeine 기반 로컬 캐시 저장소 구현체.
 * Spring CacheManager를 통해 로컬 캐시를 조회/저장/삭제한다.
 */
@Slf4j
@Component
public class ProductLocalCacheStore implements ProductCacheStore {

    private static final int MAX_CACHEABLE_PAGE = 2;

    private final Cache productDetailCache;
    private final Cache productSearchCache;

    public ProductLocalCacheStore(CacheManager cacheManager) {
        this.productDetailCache = Objects.requireNonNull(
                cacheManager.getCache("productDetail"), "productDetail cache must be configured");
        this.productSearchCache = Objects.requireNonNull(
                cacheManager.getCache("productSearch"), "productSearch cache must be configured");
    }

    @Override
    public Optional<Product> getDetail(Long productId) {
        Product cached = productDetailCache.get(productId, Product.class);
        return Optional.ofNullable(cached);
    }

    @Override
    public void setDetail(Long productId, Product product) {
        productDetailCache.put(productId, product);
    }

    @Override
    public void evictDetail(Long productId) {
        productDetailCache.evict(productId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Page<Product>> getSearch(ProductSearchCondition condition, Pageable pageable) {
        if (!isCacheablePage(pageable.getPageNumber())) {
            return Optional.empty();
        }
        String key = buildSearchKey(condition, pageable);
        Page<Product> cached = productSearchCache.get(key, Page.class);
        return Optional.ofNullable(cached);
    }

    @Override
    public void setSearch(ProductSearchCondition condition, Pageable pageable, Page<Product> products) {
        if (!isCacheablePage(pageable.getPageNumber())) {
            return;
        }
        String key = buildSearchKey(condition, pageable);
        productSearchCache.put(key, products);
    }

    private boolean isCacheablePage(int pageNumber) {
        return pageNumber <= MAX_CACHEABLE_PAGE;
    }

    private String buildSearchKey(ProductSearchCondition condition, Pageable pageable) {
        String keywordPart = condition.keyword() != null ? condition.keyword() : "all";
        String brandPart = condition.brandId() != null ? condition.brandId().toString() : "all";
        return keywordPart + "_" + condition.sort().name()
                + "_" + brandPart + "_" + pageable.getPageNumber() + "_" + pageable.getPageSize();
    }
}
