package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponService;
import com.loopers.domain.coupon.MemberCouponDetail;
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

    @Transactional
    public CouponInfo downloadCoupon(Member member, Long couponId) {
        MemberCouponDetail detail = couponService.downloadCoupon(member.getId(), couponId);
        return CouponInfo.from(detail.coupon());
    }

    public List<MyCouponInfo> getMyCoupons(Member member) {
        List<MemberCouponDetail> details = couponService.getMyCouponDetails(member.getId());
        return details.stream()
                .map(detail -> MyCouponInfo.from(detail.memberCoupon(), detail.coupon()))
                .toList();
    }
}
