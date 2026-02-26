package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.coupon.MemberCouponStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MemberCouponJpaRepository extends JpaRepository<MemberCoupon, Long> {

    Optional<MemberCoupon> findByIdAndDeletedAtIsNull(Long id);

    Optional<MemberCoupon> findByMemberIdAndCouponIdAndDeletedAtIsNull(Long memberId, Long couponId);

    List<MemberCoupon> findByMemberIdAndStatusAndDeletedAtIsNull(Long memberId, MemberCouponStatus status);
}
