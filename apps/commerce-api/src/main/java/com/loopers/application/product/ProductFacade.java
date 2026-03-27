package com.loopers.application.product;

import com.loopers.domain.event.ProductLikedEvent;
import com.loopers.domain.event.ProductViewedEvent;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductCacheStore;
import com.loopers.domain.product.ProductSearchCondition;
import com.loopers.domain.product.ProductService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 상품 관련 Use Case Facade.
//
// Kafka 이벤트 발행:
// - like()/unlike(): ProductLikedEvent 발행 → KafkaEventPublishListener가 AFTER_COMMIT에서 직접 Kafka 발행
// - getProduct(): ProductViewedEvent 발행 → KafkaEventPublishListener가 직접 Kafka 발행
//
// 통계성 이벤트(좋아요 수, 조회수)는 유실되어도 배치 보정이 가능하므로
// Outbox Pattern 없이 직접 Kafka 발행한다.
@Component
@Transactional(readOnly = true)
public class ProductFacade {

    private final ProductService productService;
    private final LikeService likeService;
    private final ProductCacheStore redisCacheStore;
    private final ProductCacheStore localCacheStore;
    private final ApplicationEventPublisher eventPublisher;

    public ProductFacade(ProductService productService,
                         LikeService likeService,
                         @Qualifier("productRedisCacheStore") ProductCacheStore redisCacheStore,
                         @Qualifier("productLocalCacheStore") ProductCacheStore localCacheStore,
                         ApplicationEventPublisher eventPublisher) {
        this.productService = productService;
        this.likeService = likeService;
        this.redisCacheStore = redisCacheStore;
        this.localCacheStore = localCacheStore;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 상품 목록 조회 - Redis Cache-Aside 적용.
     */
    public Page<ProductInfo> getProducts(ProductSearchCondition condition, Pageable pageable) {
        Page<Product> products = redisCacheStore.getSearch(condition, pageable)
                .orElseGet(() -> {
                    Page<Product> fromDb = productService.search(condition, pageable);
                    redisCacheStore.setSearch(condition, pageable, fromDb);
                    return fromDb;
                });
        return products.map(ProductInfo::from);
    }

    /**
     * 상품 목록 조회 - 로컬 캐시 Cache-Aside 적용.
     */
    public Page<ProductInfo> getProductsWithLocalCache(ProductSearchCondition condition, Pageable pageable) {
        Page<Product> products = localCacheStore.getSearch(condition, pageable)
                .orElseGet(() -> {
                    Page<Product> fromDb = productService.search(condition, pageable);
                    localCacheStore.setSearch(condition, pageable, fromDb);
                    return fromDb;
                });
        return products.map(ProductInfo::from);
    }

    // 상품 상세 조회 - Redis Cache-Aside 적용.
    // ProductViewedEvent를 발행하여 유저 행동 로깅 + Kafka 발행(통계 집계)을 수행한다.
    public ProductDetailInfo getProduct(Long productId, Long memberId) {
        Product product = redisCacheStore.getDetail(productId)
                .orElseGet(() -> {
                    Product fromDb = productService.findById(productId);
                    redisCacheStore.setDetail(productId, fromDb);
                    return fromDb;
                });

        eventPublisher.publishEvent(new ProductViewedEvent(productId, memberId));

        return ProductDetailInfo.from(product);
    }

    /**
     * 상품 상세 조회 - 로컬 캐시 Cache-Aside 적용.
     */
    public ProductDetailInfo getProductWithLocalCache(Long productId) {
        Product product = localCacheStore.getDetail(productId)
                .orElseGet(() -> {
                    Product fromDb = productService.findById(productId);
                    localCacheStore.setDetail(productId, fromDb);
                    return fromDb;
                });
        return ProductDetailInfo.from(product);
    }

    /**
     * 상품 목록 조회 - 캐시 미적용 (DB 직접 조회)
     */
    public Page<ProductInfo> getProductsNoCache(ProductSearchCondition condition, Pageable pageable) {
        return productService.search(condition, pageable).map(ProductInfo::from);
    }

    /**
     * 상품 상세 조회 - 캐시 미적용 (DB 직접 조회)
     */
    public ProductDetailInfo getProductNoCache(Long productId) {
        Product product = productService.findById(productId);
        return ProductDetailInfo.from(product);
    }

    // 상품 좋아요.
    // 좋아요 상태 변경(주요 로직)만 이 트랜잭션에서 처리하고,
    // likeCount 집계(부가 로직)는 ProductLikedEvent를 통해 LikeEventListener에 위임한다.
    // Kafka 발행도 KafkaEventPublishListener가 AFTER_COMMIT에서 직접 처리한다.
    @Transactional
    public void like(Long memberId, Long productId) {
        productService.findById(productId);
        boolean changed = likeService.like(memberId, productId);
        if (changed) {
            eventPublisher.publishEvent(new ProductLikedEvent(productId, memberId, true));
        }
    }

    // 상품 좋아요 취소.
    @Transactional
    public void unlike(Long memberId, Long productId) {
        boolean changed = likeService.unlike(memberId, productId);
        if (changed) {
            eventPublisher.publishEvent(new ProductLikedEvent(productId, memberId, false));
        }
    }

    public Page<ProductInfo> getLikedProducts(Long memberId, Pageable pageable) {
        return likeService.getLikedProducts(memberId, pageable).map(ProductInfo::from);
    }
}
