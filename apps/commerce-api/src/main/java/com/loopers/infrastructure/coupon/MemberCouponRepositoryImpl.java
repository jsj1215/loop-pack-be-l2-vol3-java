package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.coupon.MemberCouponRepository;
import com.loopers.domain.coupon.MemberCouponStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class MemberCouponRepositoryImpl implements MemberCouponRepository {

    private final MemberCouponJpaRepository memberCouponJpaRepository;

    @Override
    public Optional<MemberCoupon> findById(Long id) {
        return memberCouponJpaRepository.findByIdAndDeletedAtIsNull(id);
    }

    @Override
    public Optional<MemberCoupon> findByMemberIdAndCouponId(Long memberId, Long couponId) {
        return memberCouponJpaRepository.findByMemberIdAndCouponIdAndDeletedAtIsNull(memberId, couponId);
    }

    @Override
    public List<MemberCoupon> findByMemberIdAndStatus(Long memberId, MemberCouponStatus status) {
        return memberCouponJpaRepository
                .findByMemberIdAndStatusAndDeletedAtIsNull(memberId, status);
    }

    @Override
    public MemberCoupon save(MemberCoupon memberCoupon) {
        return memberCouponJpaRepository.save(memberCoupon);
    }
}
