package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.DiscountType;

import java.time.ZonedDateTime;

public record CouponInfo(
        Long id,
        String name,
        CouponScope couponScope,
        DiscountType discountType,
        int discountValue,
        int minOrderAmount,
        int maxDiscountAmount,
        ZonedDateTime validFrom,
        ZonedDateTime validTo) {

    public static CouponInfo from(Coupon coupon) {
        return new CouponInfo(
                coupon.getId(),
                coupon.getName(),
                coupon.getCouponScope(),
                coupon.getDiscountType(),
                coupon.getDiscountValue(),
                coupon.getMinOrderAmount(),
                coupon.getMaxDiscountAmount(),
                coupon.getValidFrom(),
                coupon.getValidTo());
    }
}
