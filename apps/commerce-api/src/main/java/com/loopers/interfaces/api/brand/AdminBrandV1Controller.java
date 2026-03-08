package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.AdminBrandFacade;
import com.loopers.application.brand.BrandInfo;
import com.loopers.domain.auth.Admin;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginAdmin;
import com.loopers.interfaces.api.brand.dto.BrandV1Dto;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/brands")
public class AdminBrandV1Controller {

    private final AdminBrandFacade adminBrandFacade;

    @GetMapping
    public ApiResponse<Page<BrandV1Dto.BrandResponse>> getBrands(
            @LoginAdmin Admin admin, Pageable pageable) {
        Page<BrandInfo> brands = adminBrandFacade.getBrands(pageable);
        return ApiResponse.success(brands.map(BrandV1Dto.BrandResponse::from));
    }

    @GetMapping("/{brandId}")
    public ApiResponse<BrandV1Dto.BrandResponse> getBrand(
            @LoginAdmin Admin admin, @PathVariable Long brandId) {
        BrandInfo info = adminBrandFacade.getBrand(brandId);
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(info));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BrandV1Dto.BrandResponse> createBrand(
            @LoginAdmin Admin admin, @RequestBody BrandV1Dto.CreateBrandRequest request) {
        BrandInfo info = adminBrandFacade.createBrand(request.name(), request.description());
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(info));
    }

    @PutMapping("/{brandId}")
    public ApiResponse<BrandV1Dto.BrandResponse> updateBrand(
            @LoginAdmin Admin admin, @PathVariable Long brandId,
            @RequestBody BrandV1Dto.UpdateBrandRequest request) {
        BrandInfo info = adminBrandFacade.updateBrand(brandId, request.name(), request.description(), request.status());
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(info));
    }

    @DeleteMapping("/{brandId}")
    public ApiResponse<Void> deleteBrand(
            @LoginAdmin Admin admin, @PathVariable Long brandId) {
        adminBrandFacade.deleteBrand(brandId);
        return ApiResponse.success(null);
    }
}
