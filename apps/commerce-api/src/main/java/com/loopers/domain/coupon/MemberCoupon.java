package com.loopers.domain.coupon;

import java.time.ZonedDateTime;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

@Getter
@Entity
@Table(name = "member_coupon", uniqueConstraints = {
        @UniqueConstraint(name = "uk_member_coupon", columnNames = {"member_id", "coupon_id"})
})
public class MemberCoupon extends BaseEntity {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MemberCouponStatus status;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "used_at")
    private ZonedDateTime usedAt;

    protected MemberCoupon() {}

    public MemberCoupon(Long memberId, Long couponId) {
        this.memberId = memberId;
        this.couponId = couponId;
        this.status = MemberCouponStatus.AVAILABLE;
    }

    /**
     * 쿠폰 사용 가능 여부 확인
     */
    public boolean isAvailable() {
        return this.status == MemberCouponStatus.AVAILABLE;
    }

    /**
     * 쿠폰 삭제 처리 (상태 기반 soft delete)
     * - BaseEntity의 deletedAt과 함께 상태를 DELETED로 변경
     */
    public void markDeleted() {
        this.status = MemberCouponStatus.DELETED;
        this.delete();
    }

    /**
     * 삭제된 쿠폰 재발급 처리
     * - DELETED 상태에서만 호출 가능
     * - 상태를 AVAILABLE로 복원하고 사용 이력을 초기화
     */
    public void reissue() {
        if (this.status != MemberCouponStatus.DELETED) {
            throw new CoreException(ErrorType.CONFLICT, "이미 다운로드한 쿠폰입니다.");
        }
        this.status = MemberCouponStatus.AVAILABLE;
        this.orderId = null;
        this.usedAt = null;
        this.restore();
    }
}
