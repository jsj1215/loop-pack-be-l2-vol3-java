package com.loopers.domain.coupon;

import java.util.Optional;

// 쿠폰 도메인 Repository 인터페이스
public interface CouponRepository {

    Optional<Coupon> findById(Long id);
}
