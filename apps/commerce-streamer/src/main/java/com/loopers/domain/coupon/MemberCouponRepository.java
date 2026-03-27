package com.loopers.domain.coupon;

import java.util.Collection;
import java.util.Optional;

// 회원 쿠폰 도메인 Repository 인터페이스
public interface MemberCouponRepository {

    Optional<MemberCoupon> findById(Long id);

    long countByCouponIdAndStatusIn(Long couponId, Collection<MemberCouponStatus> statuses);

    MemberCoupon save(MemberCoupon memberCoupon);
}
