package com.loopers.domain.product;

public record ProductSearchCondition(
        String keyword,
        ProductSortType sort,
        Long brandId) {

    public static ProductSearchCondition of(String keyword, ProductSortType sort, Long brandId) {
        return new ProductSearchCondition(keyword, sort != null ? sort : ProductSortType.LATEST, brandId);
    }
}
