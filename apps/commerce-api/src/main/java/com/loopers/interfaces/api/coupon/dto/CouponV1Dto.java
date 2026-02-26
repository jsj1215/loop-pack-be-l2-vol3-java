package com.loopers.interfaces.api.coupon.dto;

import com.loopers.application.coupon.CouponDetailInfo;
import com.loopers.application.coupon.CouponInfo;
import com.loopers.application.coupon.MyCouponInfo;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.DiscountType;

import java.time.ZonedDateTime;

public class CouponV1Dto {

    public record CreateCouponRequest(
            String name,
            CouponScope couponScope,
            Long targetId,
            DiscountType discountType,
            int discountValue,
            int minOrderAmount,
            int maxDiscountAmount,
            int totalQuantity,
            ZonedDateTime validFrom,
            ZonedDateTime validTo) {

        public Coupon toCoupon() {
            return new Coupon(name, couponScope, targetId, discountType, discountValue,
                    minOrderAmount, maxDiscountAmount, totalQuantity, validFrom, validTo);
        }
    }

    public record CouponResponse(
            Long id,
            String name,
            CouponScope couponScope,
            DiscountType discountType,
            int discountValue,
            int minOrderAmount,
            int maxDiscountAmount,
            ZonedDateTime validFrom,
            ZonedDateTime validTo,
            int remainingQuantity) {

        public static CouponResponse from(CouponInfo info) {
            return new CouponResponse(
                    info.id(),
                    info.name(),
                    info.couponScope(),
                    info.discountType(),
                    info.discountValue(),
                    info.minOrderAmount(),
                    info.maxDiscountAmount(),
                    info.validFrom(),
                    info.validTo(),
                    info.remainingQuantity());
        }
    }

    public record CouponDetailResponse(
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

        public static CouponDetailResponse from(CouponDetailInfo info) {
            return new CouponDetailResponse(
                    info.id(),
                    info.name(),
                    info.couponScope(),
                    info.targetId(),
                    info.discountType(),
                    info.discountValue(),
                    info.minOrderAmount(),
                    info.maxDiscountAmount(),
                    info.totalQuantity(),
                    info.issuedQuantity(),
                    info.validFrom(),
                    info.validTo(),
                    info.createdAt(),
                    info.updatedAt());
        }
    }

    public record MyCouponResponse(
            Long memberCouponId,
            String couponName,
            CouponScope couponScope,
            DiscountType discountType,
            int discountValue,
            int minOrderAmount,
            int maxDiscountAmount,
            ZonedDateTime validTo) {

        public static MyCouponResponse from(MyCouponInfo info) {
            return new MyCouponResponse(
                    info.memberCouponId(),
                    info.couponName(),
                    info.couponScope(),
                    info.discountType(),
                    info.discountValue(),
                    info.minOrderAmount(),
                    info.maxDiscountAmount(),
                    info.validTo());
        }
    }
}
