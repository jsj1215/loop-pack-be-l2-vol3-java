package com.loopers.application.product;

import com.loopers.domain.product.ProductOption;

public record ProductOptionInfo(
        Long id,
        String optionName,
        int stockQuantity) {

    public static ProductOptionInfo from(ProductOption option) {
        return new ProductOptionInfo(
                option.getId(),
                option.getOptionName(),
                option.getStockQuantity());
    }
}
