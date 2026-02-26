package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.AdminCouponFacade;
import com.loopers.application.coupon.CouponDetailInfo;
import com.loopers.application.coupon.CouponInfo;
import com.loopers.domain.auth.Admin;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginAdmin;
import com.loopers.interfaces.api.coupon.dto.CouponV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/coupons")
public class AdminCouponV1Controller {

    private final AdminCouponFacade adminCouponFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CouponV1Dto.CouponResponse> createCoupon(
            @LoginAdmin Admin admin, @RequestBody CouponV1Dto.CreateCouponRequest request) {
        CouponInfo info = adminCouponFacade.createCoupon(request.toCoupon());
        return ApiResponse.success(CouponV1Dto.CouponResponse.from(info));
    }

    @GetMapping
    public ApiResponse<Page<CouponV1Dto.CouponResponse>> getCoupons(
            @LoginAdmin Admin admin, Pageable pageable) {
        Page<CouponInfo> coupons = adminCouponFacade.getCoupons(pageable);
        return ApiResponse.success(coupons.map(CouponV1Dto.CouponResponse::from));
    }

    @GetMapping("/{couponId}")
    public ApiResponse<CouponV1Dto.CouponDetailResponse> getCoupon(
            @LoginAdmin Admin admin, @PathVariable Long couponId) {
        CouponDetailInfo info = adminCouponFacade.getCoupon(couponId);
        return ApiResponse.success(CouponV1Dto.CouponDetailResponse.from(info));
    }
}
