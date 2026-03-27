package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// 쿠폰 JPA Repository (Soft Delete 적용)
public interface CouponJpaRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByIdAndDeletedAtIsNull(Long id);
}
