package com.loopers.application.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductStatus;

import java.time.ZonedDateTime;

public record ProductInfo(
        Long id,
        Long brandId,
        String brandName,
        String name,
        int price,
        int discountPrice,
        int shippingFee,
        int likeCount,
        ProductStatus status,
        String displayYn,
        ZonedDateTime createdAt) {

    public static ProductInfo from(Product product) {
        return new ProductInfo(
                product.getId(),
                product.getBrand().getId(),
                product.getBrand().getName(),
                product.getName(),
                product.getPrice(),
                product.getDiscountPrice(),
                product.getShippingFee(),
                product.getLikeCount(),
                product.getStatus(),
                product.getDisplayYn(),
                product.getCreatedAt());
    }
}
