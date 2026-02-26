package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
}
