package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.coupon.MemberCouponRepository;
import com.loopers.domain.coupon.MemberCouponStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;

// MemberCouponRepository 구현체 (Soft Delete 조건 적용)
@RequiredArgsConstructor
@Component
public class MemberCouponRepositoryImpl implements MemberCouponRepository {

    private final MemberCouponJpaRepository memberCouponJpaRepository;

    @Override
    public Optional<MemberCoupon> findById(Long id) {
        return memberCouponJpaRepository.findByIdAndDeletedAtIsNull(id);
    }

    @Override
    public long countByCouponIdAndStatusIn(Long couponId, Collection<MemberCouponStatus> statuses) {
        return memberCouponJpaRepository.countByCouponIdAndStatusIn(couponId, statuses);
    }

    @Override
    public MemberCoupon save(MemberCoupon memberCoupon) {
        return memberCouponJpaRepository.save(memberCoupon);
    }
}
