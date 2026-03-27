package com.loopers.domain.coupon;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

// 회원 쿠폰 도메인 Repository 인터페이스
public interface MemberCouponRepository {

    Optional<MemberCoupon> findById(Long id);

    Optional<MemberCoupon> findByMemberIdAndCouponId(Long memberId, Long couponId);

    Optional<MemberCoupon> findByMemberIdAndCouponIdIncludingDeleted(Long memberId, Long couponId);

    List<MemberCoupon> findByMemberIdAndStatus(Long memberId, MemberCouponStatus status);

    List<MemberCoupon> findByMemberId(Long memberId);

    Page<MemberCoupon> findByCouponId(Long couponId, Pageable pageable);

    MemberCoupon save(MemberCoupon memberCoupon);

    long countByCouponIdAndStatusIn(Long couponId, Collection<MemberCouponStatus> statuses);

    int updateStatusToUsed(Long id, Long orderId, ZonedDateTime usedAt);

    int updateStatusToAvailable(Long id);
}
