package com.loopers.interfaces.api.coupon.dto;

import com.loopers.application.coupon.CouponDetailInfo;
import com.loopers.application.coupon.CouponInfo;
import com.loopers.application.coupon.CouponIssueInfo;
import com.loopers.application.coupon.MyCouponInfo;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.DiscountType;
import com.loopers.domain.coupon.MemberCouponStatus;

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
            ZonedDateTime validFrom,
            ZonedDateTime validTo) {

        public Coupon toCoupon() {
            return new Coupon(name, couponScope, targetId, discountType, discountValue,
                    minOrderAmount, maxDiscountAmount, validFrom, validTo);
        }
    }

    public record UpdateCouponRequest(
            String name,
            CouponScope couponScope,
            Long targetId,
            DiscountType discountType,
            int discountValue,
            int minOrderAmount,
            int maxDiscountAmount,
            ZonedDateTime validFrom,
            ZonedDateTime validTo) {
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
            ZonedDateTime validTo) {

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
                    info.validTo());
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
            ZonedDateTime validTo,
            MemberCouponStatus status) {

        public static MyCouponResponse from(MyCouponInfo info) {
            return new MyCouponResponse(
                    info.memberCouponId(),
                    info.couponName(),
                    info.couponScope(),
                    info.discountType(),
                    info.discountValue(),
                    info.minOrderAmount(),
                    info.maxDiscountAmount(),
                    info.validTo(),
                    info.status());
        }
    }

    public record CouponIssueResponse(
            Long memberCouponId,
            Long memberId,
            Long couponId,
            String couponName,
            MemberCouponStatus status,
            ZonedDateTime issuedAt,
            ZonedDateTime usedAt) {

        public static CouponIssueResponse from(CouponIssueInfo info) {
            return new CouponIssueResponse(
                    info.memberCouponId(),
                    info.memberId(),
                    info.couponId(),
                    info.couponName(),
                    info.status(),
                    info.issuedAt(),
                    info.usedAt());
        }
    }
}
