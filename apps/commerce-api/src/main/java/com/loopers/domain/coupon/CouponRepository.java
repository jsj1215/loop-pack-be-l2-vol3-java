package com.loopers.domain.coupon;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface CouponRepository {

    Optional<Coupon> findById(Long id);

    Optional<Coupon> findByIdWithLock(Long id);

    List<Coupon> findByIds(List<Long> ids);

    Page<Coupon> findAll(Pageable pageable);

    Coupon save(Coupon coupon);
}
