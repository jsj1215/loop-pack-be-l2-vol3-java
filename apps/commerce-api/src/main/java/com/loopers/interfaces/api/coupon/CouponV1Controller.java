package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.application.coupon.CouponInfo;
import com.loopers.application.coupon.MyCouponInfo;
import com.loopers.domain.member.Member;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginMember;
import com.loopers.interfaces.api.coupon.dto.CouponV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/coupons")
public class CouponV1Controller {

    private final CouponFacade couponFacade;

    @GetMapping
    public ApiResponse<List<CouponV1Dto.CouponResponse>> getAvailableCoupons() {
        List<CouponInfo> coupons = couponFacade.getAvailableCoupons();
        List<CouponV1Dto.CouponResponse> response = coupons.stream()
                .map(CouponV1Dto.CouponResponse::from)
                .toList();
        return ApiResponse.success(response);
    }

    @PostMapping("/{couponId}/download")
    public ApiResponse<CouponV1Dto.CouponResponse> downloadCoupon(
            @LoginMember Member member, @PathVariable Long couponId) {
        CouponInfo info = couponFacade.downloadCoupon(member, couponId);
        return ApiResponse.success(CouponV1Dto.CouponResponse.from(info));
    }

    @GetMapping("/me")
    public ApiResponse<List<CouponV1Dto.MyCouponResponse>> getMyCoupons(@LoginMember Member member) {
        List<MyCouponInfo> myCoupons = couponFacade.getMyCoupons(member);
        List<CouponV1Dto.MyCouponResponse> response = myCoupons.stream()
                .map(CouponV1Dto.MyCouponResponse::from)
                .toList();
        return ApiResponse.success(response);
    }
}
