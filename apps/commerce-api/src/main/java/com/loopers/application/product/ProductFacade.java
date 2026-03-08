package com.loopers.application.product;

import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductSearchCondition;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
@Transactional(readOnly = true)
public class ProductFacade {

    private final ProductService productService;
    private final LikeService likeService;

    public Page<ProductInfo> getProducts(ProductSearchCondition condition, Pageable pageable) {
        return productService.search(condition, pageable).map(ProductInfo::from);
    }

    public ProductDetailInfo getProduct(Long productId) {
        Product product = productService.findById(productId);
        return ProductDetailInfo.from(product);
    }

    @Transactional
    public void like(Long memberId, Long productId) {
        likeService.like(memberId, productId);
    }

    @Transactional
    public void unlike(Long memberId, Long productId) {
        likeService.unlike(memberId, productId);
    }

    public Page<ProductInfo> getLikedProducts(Long memberId, Pageable pageable) {
        return likeService.getLikedProducts(memberId, pageable).map(ProductInfo::from);
    }
}
