package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.DiscountType;
import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.coupon.MemberCouponStatus;

import java.time.ZonedDateTime;

public record MyCouponInfo(
        Long memberCouponId,
        String couponName,
        CouponScope couponScope,
        DiscountType discountType,
        int discountValue,
        int minOrderAmount,
        int maxDiscountAmount,
        ZonedDateTime validTo,
        MemberCouponStatus status) {

    public static MyCouponInfo from(MemberCoupon memberCoupon, Coupon coupon) {
        return new MyCouponInfo(
                memberCoupon.getId(),
                coupon.getName(),
                coupon.getCouponScope(),
                coupon.getDiscountType(),
                coupon.getDiscountValue(),
                coupon.getMinOrderAmount(),
                coupon.getMaxDiscountAmount(),
                coupon.getValidTo(),
                resolveDisplayStatus(memberCoupon, coupon));
    }

    private static MemberCouponStatus resolveDisplayStatus(MemberCoupon memberCoupon, Coupon coupon) {
        if (memberCoupon.getStatus() == MemberCouponStatus.USED) {
            return MemberCouponStatus.USED;
        }
        if (memberCoupon.getStatus() == MemberCouponStatus.AVAILABLE && coupon.getValidTo().isBefore(ZonedDateTime.now())) {
            return MemberCouponStatus.EXPIRED;
        }
        return MemberCouponStatus.AVAILABLE;
    }
}
