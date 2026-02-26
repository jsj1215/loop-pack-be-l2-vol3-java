package com.loopers.domain.product;

public record AdminProductSearchCondition(
        AdminProductSearchType searchType,
        String searchValue) {

    public static AdminProductSearchCondition of(AdminProductSearchType searchType, String searchValue) {
        return new AdminProductSearchCondition(searchType, searchValue);
    }
}
