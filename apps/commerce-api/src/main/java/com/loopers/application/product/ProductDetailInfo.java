package com.loopers.application.product;

import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductStatus;

import java.time.ZonedDateTime;
import java.util.List;

public record ProductDetailInfo(
        Long id,
        Long brandId,
        String brandName,
        String name,
        int price,
        int supplyPrice,
        int discountPrice,
        int shippingFee,
        int likeCount,
        String description,
        MarginType marginType,
        ProductStatus status,
        String displayYn,
        List<ProductOptionInfo> options,
        ZonedDateTime createdAt) {

    public static ProductDetailInfo from(Product product) {
        List<ProductOptionInfo> optionInfos = product.getOptions().stream()
                .map(ProductOptionInfo::from)
                .toList();

        return new ProductDetailInfo(
                product.getId(),
                product.getBrand().getId(),
                product.getBrand().getName(),
                product.getName(),
                product.getPrice(),
                product.getSupplyPrice(),
                product.getDiscountPrice(),
                product.getShippingFee(),
                product.getLikeCount(),
                product.getDescription(),
                product.getMarginType(),
                product.getStatus(),
                product.getDisplayYn(),
                optionInfos,
                product.getCreatedAt());
    }
}
