package com.loopers.domain.coupon;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface MemberCouponRepository {

    Optional<MemberCoupon> findById(Long id);

    Optional<MemberCoupon> findByMemberIdAndCouponId(Long memberId, Long couponId);

    Optional<MemberCoupon> findByMemberIdAndCouponIdIncludingDeleted(Long memberId, Long couponId);

    List<MemberCoupon> findByMemberIdAndStatus(Long memberId, MemberCouponStatus status);

    List<MemberCoupon> findByMemberId(Long memberId);

    Page<MemberCoupon> findByCouponId(Long couponId, Pageable pageable);

    MemberCoupon save(MemberCoupon memberCoupon);
}
