package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductDetailInfo;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.product.ProductInfo;
import com.loopers.domain.member.Member;
import com.loopers.domain.product.ProductSearchCondition;
import com.loopers.domain.product.ProductSortType;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginMember;
import com.loopers.interfaces.api.product.dto.ProductV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/products")
public class ProductV1Controller {

    private final ProductFacade productFacade;

    @GetMapping
    public ApiResponse<Page<ProductV1Dto.ProductResponse>> getProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) ProductSortType sort,
            @RequestParam(required = false) Long brandId,
            Pageable pageable) {
        ProductSearchCondition condition = ProductSearchCondition.of(keyword, sort, brandId); //ProductSearchCondition : 검색 조건을 위한 객체
        Page<ProductInfo> products = productFacade.getProducts(condition, pageable);
        return ApiResponse.success(products.map(ProductV1Dto.ProductResponse::from));
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductV1Dto.ProductDetailResponse> getProduct(@PathVariable Long productId) {
        ProductDetailInfo info = productFacade.getProduct(productId);
        return ApiResponse.success(ProductV1Dto.ProductDetailResponse.from(info));
    }

    @PostMapping("/{productId}/likes")
    public ApiResponse<Void> like(@LoginMember Member member, @PathVariable Long productId) {
        productFacade.like(member.getId(), productId);
        return ApiResponse.success(null);
    }

    @PutMapping("/{productId}/likes")
    public ApiResponse<Void> unlike(@LoginMember Member member, @PathVariable Long productId) {
        productFacade.unlike(member.getId(), productId);
        return ApiResponse.success(null);
    }

    @GetMapping("/users/{userId}/likes")
    public ApiResponse<Page<ProductV1Dto.ProductResponse>> getLikedProducts(
            @LoginMember Member member, @PathVariable Long userId, Pageable pageable) {
        Page<ProductInfo> products = productFacade.getLikedProducts(member.getId(), pageable);
        return ApiResponse.success(products.map(ProductV1Dto.ProductResponse::from));
    }
}
