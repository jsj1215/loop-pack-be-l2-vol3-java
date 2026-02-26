package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.DiscountType;

import java.time.ZonedDateTime;

public record CouponDetailInfo(
        Long id,
        String name,
        CouponScope couponScope,
        Long targetId,
        DiscountType discountType,
        int discountValue,
        int minOrderAmount,
        int maxDiscountAmount,
        int totalQuantity,
        int issuedQuantity,
        ZonedDateTime validFrom,
        ZonedDateTime validTo,
        ZonedDateTime createdAt,
        ZonedDateTime updatedAt) {

    public static CouponDetailInfo from(Coupon coupon) {
        return new CouponDetailInfo(
                coupon.getId(),
                coupon.getName(),
                coupon.getCouponScope(),
                coupon.getTargetId(),
                coupon.getDiscountType(),
                coupon.getDiscountValue(),
                coupon.getMinOrderAmount(),
                coupon.getMaxDiscountAmount(),
                coupon.getTotalQuantity(),
                coupon.getIssuedQuantity(),
                coupon.getValidFrom(),
                coupon.getValidTo(),
                coupon.getCreatedAt(),
                coupon.getUpdatedAt());
    }
}
