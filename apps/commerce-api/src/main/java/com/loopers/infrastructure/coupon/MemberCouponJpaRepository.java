package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.coupon.MemberCouponStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

// 회원 쿠폰 JPA Repository (Soft Delete 적용)
public interface MemberCouponJpaRepository extends JpaRepository<MemberCoupon, Long> {

    Optional<MemberCoupon> findByIdAndDeletedAtIsNull(Long id);

    Optional<MemberCoupon> findByMemberIdAndCouponIdAndDeletedAtIsNull(Long memberId, Long couponId);

    Optional<MemberCoupon> findByMemberIdAndCouponId(Long memberId, Long couponId);

    List<MemberCoupon> findByMemberIdAndStatusAndDeletedAtIsNull(Long memberId, MemberCouponStatus status);

    List<MemberCoupon> findByMemberIdAndDeletedAtIsNull(Long memberId);

    Page<MemberCoupon> findByCouponIdAndDeletedAtIsNull(Long couponId, Pageable pageable);

    long countByCouponIdAndStatusIn(Long couponId, Collection<MemberCouponStatus> statuses);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE MemberCoupon mc SET mc.status = 'USED', mc.orderId = :orderId, mc.usedAt = :usedAt " +
            "WHERE mc.id = :id AND mc.status = 'AVAILABLE'")
    int updateStatusToUsed(@Param("id") Long id, @Param("orderId") Long orderId, @Param("usedAt") ZonedDateTime usedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE MemberCoupon mc SET mc.status = 'AVAILABLE', mc.orderId = null, mc.usedAt = null " +
            "WHERE mc.id = :id AND mc.status = 'USED'")
    int updateStatusToAvailable(@Param("id") Long id);
}
