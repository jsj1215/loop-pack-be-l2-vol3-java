package com.loopers.interfaces.api.product.dto;

import com.loopers.application.product.ProductDetailInfo;
import com.loopers.application.product.ProductInfo;
import com.loopers.application.product.ProductOptionInfo;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.ProductStatus;

import java.time.ZonedDateTime;
import java.util.List;

public class ProductV1Dto {

    public record ProductResponse(
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

        public static ProductResponse from(ProductInfo info) {
            return new ProductResponse(
                    info.id(),
                    info.brandId(),
                    info.brandName(),
                    info.name(),
                    info.price(),
                    info.discountPrice(),
                    info.shippingFee(),
                    info.likeCount(),
                    info.status(),
                    info.displayYn(),
                    info.createdAt());
        }
    }

    public record ProductDetailResponse(
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
            List<ProductOptionResponse> options,
            ZonedDateTime createdAt) {

        public static ProductDetailResponse from(ProductDetailInfo info) {
            List<ProductOptionResponse> optionResponses = info.options().stream()
                    .map(ProductOptionResponse::from)
                    .toList();

            return new ProductDetailResponse(
                    info.id(),
                    info.brandId(),
                    info.brandName(),
                    info.name(),
                    info.price(),
                    info.supplyPrice(),
                    info.discountPrice(),
                    info.shippingFee(),
                    info.likeCount(),
                    info.description(),
                    info.marginType(),
                    info.status(),
                    info.displayYn(),
                    optionResponses,
                    info.createdAt());
        }
    }

    public record ProductOptionResponse(
            Long id,
            String optionName,
            int stockQuantity) {

        public static ProductOptionResponse from(ProductOptionInfo info) {
            return new ProductOptionResponse(
                    info.id(),
                    info.optionName(),
                    info.stockQuantity());
        }
    }

    public record CreateProductRequest(
            Long brandId,
            String name,
            int price,
            MarginType marginType,
            int marginValue,
            int discountPrice,
            int shippingFee,
            String description,
            List<ProductOptionRequest> options) {
    }

    public record UpdateProductRequest(
            String name,
            int price,
            int supplyPrice,
            int discountPrice,
            int shippingFee,
            String description,
            ProductStatus status,
            String displayYn,
            List<ProductOptionRequest> options) {
    }

    public record ProductOptionRequest(
            String optionName,
            int stockQuantity) {
    }
}
