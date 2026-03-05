package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.coupon.MemberCouponStatus;

import java.time.ZonedDateTime;

public record CouponIssueInfo(
        Long memberCouponId,
        Long memberId,
        Long couponId,
        String couponName,
        MemberCouponStatus status,
        ZonedDateTime issuedAt,
        ZonedDateTime usedAt) {

    public static CouponIssueInfo from(MemberCoupon memberCoupon, Coupon coupon) {
        return new CouponIssueInfo(
                memberCoupon.getId(),
                memberCoupon.getMemberId(),
                memberCoupon.getCouponId(),
                coupon.getName(),
                memberCoupon.getStatus(),
                memberCoupon.getCreatedAt(),
                memberCoupon.getUsedAt());
    }
}
