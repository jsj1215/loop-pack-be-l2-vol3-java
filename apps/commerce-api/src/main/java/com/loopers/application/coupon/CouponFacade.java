package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponService;
import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.member.Member;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
@Transactional(readOnly = true)
public class CouponFacade {

    private final CouponService couponService;

    public List<CouponInfo> getAvailableCoupons() {
        return couponService.findAvailableCoupons().stream()
                .map(CouponInfo::from)
                .toList();
    }

    @Transactional
    public CouponInfo downloadCoupon(Member member, Long couponId) {
        MemberCoupon memberCoupon = couponService.downloadCoupon(member.getId(), couponId);
        Coupon coupon = couponService.findById(memberCoupon.getCouponId());
        return CouponInfo.from(coupon);
    }

    public List<MyCouponInfo> getMyCoupons(Member member) {
        List<MemberCoupon> memberCoupons = couponService.findMyCoupons(member.getId());
        return memberCoupons.stream()
                .map(mc -> {
                    Coupon coupon = couponService.findById(mc.getCouponId());
                    return MyCouponInfo.from(mc, coupon);
                })
                .toList();
    }
}
