package com.loopers.interfaces.api.product;

import com.loopers.application.product.AdminProductFacade;
import com.loopers.application.product.ProductDetailInfo;
import com.loopers.application.product.ProductInfo;
import com.loopers.domain.auth.Admin;
import com.loopers.domain.product.AdminProductSearchCondition;
import com.loopers.domain.product.AdminProductSearchType;
import com.loopers.domain.product.ProductOption;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginAdmin;
import com.loopers.interfaces.api.product.dto.ProductV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/products")
public class AdminProductV1Controller {

    private final AdminProductFacade adminProductFacade;

    @GetMapping
    public ApiResponse<Page<ProductV1Dto.ProductResponse>> getProducts(
            @LoginAdmin Admin admin,
            @RequestParam(required = false) AdminProductSearchType searchType,
            @RequestParam(required = false) String searchValue,
            Pageable pageable) {
        AdminProductSearchCondition condition = AdminProductSearchCondition.of(searchType, searchValue);
        Page<ProductInfo> products = adminProductFacade.getProducts(condition, pageable);
        return ApiResponse.success(products.map(ProductV1Dto.ProductResponse::from));
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductV1Dto.ProductDetailResponse> getProduct(
            @LoginAdmin Admin admin, @PathVariable Long productId) {
        ProductDetailInfo info = adminProductFacade.getProduct(productId);
        return ApiResponse.success(ProductV1Dto.ProductDetailResponse.from(info));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductV1Dto.ProductDetailResponse> createProduct(
            @LoginAdmin Admin admin, @RequestBody ProductV1Dto.CreateProductRequest request) {
        List<ProductOption> options = request.options().stream()
                .map(opt -> new ProductOption(null, opt.optionName(), opt.stockQuantity()))
                .toList();

        ProductDetailInfo info = adminProductFacade.createProduct(
                request.brandId(), request.name(), request.price(),
                request.marginType(), request.marginValue(),
                request.discountPrice(), request.shippingFee(),
                request.description(), options);
        return ApiResponse.success(ProductV1Dto.ProductDetailResponse.from(info));
    }

    @PutMapping("/{productId}")
    public ApiResponse<ProductV1Dto.ProductDetailResponse> updateProduct(
            @LoginAdmin Admin admin, @PathVariable Long productId,
            @RequestBody ProductV1Dto.UpdateProductRequest request) {
        List<ProductOption> options = request.options().stream()
                .map(opt -> new ProductOption(null, opt.optionName(), opt.stockQuantity()))
                .toList();

        ProductDetailInfo info = adminProductFacade.updateProduct(
                productId, request.name(), request.price(), request.supplyPrice(),
                request.discountPrice(), request.shippingFee(),
                request.description(), request.status(), request.displayYn(), options);
        return ApiResponse.success(ProductV1Dto.ProductDetailResponse.from(info));
    }

    @DeleteMapping("/{productId}")
    public ApiResponse<Void> deleteProduct(
            @LoginAdmin Admin admin, @PathVariable Long productId) {
        adminProductFacade.deleteProduct(productId);
        return ApiResponse.success(null);
    }
}
