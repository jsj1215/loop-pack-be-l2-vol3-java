package com.loopers.domain.coupon;

import java.util.List;
import java.util.Optional;

public interface MemberCouponRepository {

    Optional<MemberCoupon> findById(Long id);

    Optional<MemberCoupon> findByMemberIdAndCouponId(Long memberId, Long couponId);

    List<MemberCoupon> findByMemberIdAndStatus(Long memberId, MemberCouponStatus status);

    MemberCoupon save(MemberCoupon memberCoupon);
}
