package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.AdminCouponFacade;
import com.loopers.application.coupon.CouponDetailInfo;
import com.loopers.application.coupon.CouponInfo;
import com.loopers.application.coupon.CouponIssueInfo;
import com.loopers.domain.auth.Admin;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginAdmin;
import com.loopers.interfaces.api.coupon.dto.CouponV1Dto;
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
@RequestMapping("/api-admin/v1/coupons")
public class AdminCouponV1Controller {

    private final AdminCouponFacade adminCouponFacade;

    /**
     * 쿠폰 - 생성
     * @param admin
     * @param request
     * @return
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CouponV1Dto.CouponResponse> createCoupon(
            @LoginAdmin Admin admin, @RequestBody CouponV1Dto.CreateCouponRequest request) {
        CouponInfo info = adminCouponFacade.createCoupon(request.toCoupon());
        return ApiResponse.success(CouponV1Dto.CouponResponse.from(info));
    }

    /**
     * 쿠폰 - 조회
     * @param admin
     * @param pageable
     * @return
     */
    @GetMapping
    public ApiResponse<Page<CouponV1Dto.CouponResponse>> getCoupons(
            @LoginAdmin Admin admin, Pageable pageable) {
        Page<CouponInfo> coupons = adminCouponFacade.getCoupons(pageable);
        return ApiResponse.success(coupons.map(CouponV1Dto.CouponResponse::from));
    }

    /**
     * 쿠폰 - 상세 조회
     * @param admin
     * @param couponId
     * @return
     */
    @GetMapping("/{couponId}")
    public ApiResponse<CouponV1Dto.CouponDetailResponse> getCoupon(
            @LoginAdmin Admin admin, @PathVariable Long couponId) {
        CouponDetailInfo info = adminCouponFacade.getCoupon(couponId);
        return ApiResponse.success(CouponV1Dto.CouponDetailResponse.from(info));
    }

    /**
     * 쿠폰 - 수정
     * @param admin
     * @param couponId
     * @param request
     * @return
     */
    @PutMapping("/{couponId}")
    public ApiResponse<CouponV1Dto.CouponResponse> updateCoupon(
            @LoginAdmin Admin admin, @PathVariable Long couponId,
            @RequestBody CouponV1Dto.UpdateCouponRequest request) {
        CouponInfo info = adminCouponFacade.updateCoupon(
                couponId, request.name(), request.couponScope(), request.targetId(),
                request.discountType(), request.discountValue(), request.minOrderAmount(),
                request.maxDiscountAmount(),
                request.validFrom(), request.validTo());
        return ApiResponse.success(CouponV1Dto.CouponResponse.from(info));
    }

    /**
     * 쿠폰 - 삭제
     * @param admin
     * @param couponId
     * @return
     */
    @DeleteMapping("/{couponId}")
    public ApiResponse<Void> deleteCoupon(
            @LoginAdmin Admin admin, @PathVariable Long couponId) {
        adminCouponFacade.deleteCoupon(couponId);
        return ApiResponse.success(null);
    }

    /**
     * 쿠폰 - 발급
     * @param admin
     * @param couponId
     * @param pageable
     * @return
     */
    @GetMapping("/{couponId}/issues")
    public ApiResponse<Page<CouponV1Dto.CouponIssueResponse>> getCouponIssues(
            @LoginAdmin Admin admin, @PathVariable Long couponId, Pageable pageable) {
        Page<CouponIssueInfo> issues = adminCouponFacade.getCouponIssues(couponId, pageable);
        return ApiResponse.success(issues.map(CouponV1Dto.CouponIssueResponse::from));
    }
}
