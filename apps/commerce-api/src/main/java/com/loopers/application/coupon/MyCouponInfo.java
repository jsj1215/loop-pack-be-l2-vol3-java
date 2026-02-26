package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.DiscountType;
import com.loopers.domain.coupon.MemberCoupon;

import java.time.ZonedDateTime;

public record MyCouponInfo(
        Long memberCouponId,
        String couponName,
        CouponScope couponScope,
        DiscountType discountType,
        int discountValue,
        int minOrderAmount,
        int maxDiscountAmount,
        ZonedDateTime validTo) {

    public static MyCouponInfo from(MemberCoupon memberCoupon, Coupon coupon) {
        return new MyCouponInfo(
                memberCoupon.getId(),
                coupon.getName(),
                coupon.getCouponScope(),
                coupon.getDiscountType(),
                coupon.getDiscountValue(),
                coupon.getMinOrderAmount(),
                coupon.getMaxDiscountAmount(),
                coupon.getValidTo());
    }
}
