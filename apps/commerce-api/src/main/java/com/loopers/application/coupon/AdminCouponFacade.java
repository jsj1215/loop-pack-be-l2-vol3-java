package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.CouponService;
import com.loopers.domain.coupon.DiscountType;
import com.loopers.domain.coupon.MemberCoupon;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

@RequiredArgsConstructor
@Component
@Transactional(readOnly = true)
public class AdminCouponFacade {

    private final CouponService couponService;

    @Transactional
    public CouponInfo createCoupon(Coupon coupon) {
        Coupon savedCoupon = couponService.createCoupon(coupon);
        return CouponInfo.from(savedCoupon);
    }

    public Page<CouponInfo> getCoupons(Pageable pageable) {
        return couponService.findAllCoupons(pageable)
                .map(CouponInfo::from);
    }

    public CouponDetailInfo getCoupon(Long couponId) {
        Coupon coupon = couponService.findById(couponId);
        return CouponDetailInfo.from(coupon);
    }

    @Transactional
    public CouponInfo updateCoupon(Long couponId, String name, CouponScope couponScope, Long targetId,
                                   DiscountType discountType, int discountValue, int minOrderAmount,
                                   int maxDiscountAmount,
                                   ZonedDateTime validFrom, ZonedDateTime validTo) {
        Coupon coupon = couponService.updateCoupon(couponId, name, couponScope, targetId,
                discountType, discountValue, minOrderAmount, maxDiscountAmount,
                validFrom, validTo);
        return CouponInfo.from(coupon);
    }

    @Transactional
    public void deleteCoupon(Long couponId) {
        couponService.softDelete(couponId);
    }

    public Page<CouponIssueInfo> getCouponIssues(Long couponId, Pageable pageable) {
        Coupon coupon = couponService.findById(couponId);
        return couponService.findCouponIssues(couponId, pageable)
                .map(mc -> CouponIssueInfo.from(mc, coupon));
    }
}
