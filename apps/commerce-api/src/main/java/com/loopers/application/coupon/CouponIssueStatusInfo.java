package com.loopers.application.coupon;

import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.coupon.MemberCouponStatus;

// 쿠폰 발급 요청 상태 응답 정보
public record CouponIssueStatusInfo(
        MemberCouponStatus status,
        String failReason
) {
    public static CouponIssueStatusInfo from(MemberCoupon memberCoupon) {
        return new CouponIssueStatusInfo(
                memberCoupon.getStatus(),
                memberCoupon.getFailReason()
        );
    }
}
