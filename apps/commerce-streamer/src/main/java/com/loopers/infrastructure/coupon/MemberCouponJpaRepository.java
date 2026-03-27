package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.coupon.MemberCouponStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

// 회원 쿠폰 JPA Repository (Soft Delete 적용)
public interface MemberCouponJpaRepository extends JpaRepository<MemberCoupon, Long> {

    Optional<MemberCoupon> findByIdAndDeletedAtIsNull(Long id);

    long countByCouponIdAndStatusIn(Long couponId, Collection<MemberCouponStatus> statuses);
}
