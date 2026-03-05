package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CouponRepositoryImpl implements CouponRepository {

    private final CouponJpaRepository couponJpaRepository;

    @Override
    public Optional<Coupon> findById(Long id) {
        return couponJpaRepository.findByIdAndDeletedAtIsNull(id);
    }

    @Override
    public Optional<Coupon> findByIdWithLock(Long id) {
        return couponJpaRepository.findByIdWithLockAndDeletedAtIsNull(id);
    }

    @Override
    public List<Coupon> findByIds(List<Long> ids) {
        return couponJpaRepository.findAllByIdInAndDeletedAtIsNull(ids);
    }

    @Override
    public Page<Coupon> findAll(Pageable pageable) {
        return couponJpaRepository.findAllByDeletedAtIsNull(pageable);
    }

    @Override
    public Coupon save(Coupon coupon) {
        return couponJpaRepository.save(coupon);
    }
}
