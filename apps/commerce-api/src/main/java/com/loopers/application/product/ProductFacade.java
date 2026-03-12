package com.loopers.application.product;

import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductCacheStore;
import com.loopers.domain.product.ProductSearchCondition;
import com.loopers.domain.product.ProductService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class ProductFacade {

    private final ProductService productService;
    private final LikeService likeService;
    private final ProductCacheStore redisCacheStore;
    private final ProductCacheStore localCacheStore;

    public ProductFacade(ProductService productService,
                         LikeService likeService,
                         @Qualifier("productRedisCacheStore") ProductCacheStore redisCacheStore,
                         @Qualifier("productLocalCacheStore") ProductCacheStore localCacheStore) {
        this.productService = productService;
        this.likeService = likeService;
        this.redisCacheStore = redisCacheStore;
        this.localCacheStore = localCacheStore;
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

    /**
     * 상품 상세 조회 - Redis Cache-Aside 적용.
     */
    public ProductDetailInfo getProduct(Long productId) {
        Product product = redisCacheStore.getDetail(productId)
                .orElseGet(() -> {
                    Product fromDb = productService.findById(productId);
                    redisCacheStore.setDetail(productId, fromDb);
                    return fromDb;
                });
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

    @Transactional
    public void like(Long memberId, Long productId) {
        productService.findById(productId);
        boolean changed = likeService.like(memberId, productId);
        if (changed) {
            productService.incrementLikeCount(productId);
        }
    }

    @Transactional
    public void unlike(Long memberId, Long productId) {
        boolean changed = likeService.unlike(memberId, productId);
        if (changed) {
            productService.decrementLikeCount(productId);
        }
    }

    public Page<ProductInfo> getLikedProducts(Long memberId, Pageable pageable) {
        return likeService.getLikedProducts(memberId, pageable).map(ProductInfo::from);
    }
}
