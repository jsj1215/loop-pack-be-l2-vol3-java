package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * commerce-api의 MemberCoupon 엔티티 복제.
 * Consumer에서 상태 전환(REQUESTED → AVAILABLE/FAILED)을 수행한다.
 */
@Getter
@Entity
@Table(name = "member_coupon")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberCoupon extends BaseEntity {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MemberCouponStatus status;

    @Column(name = "fail_reason")
    private String failReason;

    /**
     * 발급 승인 — REQUESTED → AVAILABLE
     */
    public void approve() {
        if (this.status != MemberCouponStatus.REQUESTED) {
            throw new IllegalStateException("요청 상태의 쿠폰만 승인할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = MemberCouponStatus.AVAILABLE;
    }

    /**
     * 발급 거부 — REQUESTED → FAILED
     */
    public void reject(String reason) {
        if (this.status != MemberCouponStatus.REQUESTED) {
            throw new IllegalStateException("요청 상태의 쿠폰만 거부할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = MemberCouponStatus.FAILED;
        this.failReason = reason;
    }
}
