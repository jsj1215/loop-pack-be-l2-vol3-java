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

    @Column(name = "fail_reason")
    private String failReason;

    protected MemberCoupon() {}

    public MemberCoupon(Long memberId, Long couponId) {
        this.memberId = memberId;
        this.couponId = couponId;
        this.status = MemberCouponStatus.AVAILABLE;
    }

    /**
     * 선착순 쿠폰 발급 요청용 정적 팩토리 메서드.
     * 상태를 REQUESTED로 생성하여 비동기 처리 대기 상태를 표현한다.
     */
    public static MemberCoupon createRequested(Long memberId, Long couponId) {
        MemberCoupon memberCoupon = new MemberCoupon();
        memberCoupon.memberId = memberId;
        memberCoupon.couponId = couponId;
        memberCoupon.status = MemberCouponStatus.REQUESTED;
        return memberCoupon;
    }

    /**
     * 쿠폰 사용 가능 여부 확인
     */
    public boolean isAvailable() {
        return this.status == MemberCouponStatus.AVAILABLE;
    }

    /**
     * 선착순 발급 승인 — REQUESTED → AVAILABLE 상태 전환
     */
    public void approve() {
        if (this.status != MemberCouponStatus.REQUESTED) {
            throw new CoreException(ErrorType.CONFLICT, "요청 상태의 쿠폰만 승인할 수 있습니다.");
        }
        this.status = MemberCouponStatus.AVAILABLE;
    }

    /**
     * 선착순 발급 거부 — REQUESTED → FAILED 상태 전환
     */
    public void reject(String reason) {
        if (this.status != MemberCouponStatus.REQUESTED) {
            throw new CoreException(ErrorType.CONFLICT, "요청 상태의 쿠폰만 거부할 수 있습니다.");
        }
        this.status = MemberCouponStatus.FAILED;
        this.failReason = reason;
    }

    /**
     * 선착순 발급 재요청 — FAILED → REQUESTED 상태 전환
     * 수량 초과 등으로 거절된 후 재시도할 때 사용한다.
     */
    public void retryRequest() {
        if (this.status != MemberCouponStatus.FAILED) {
            throw new CoreException(ErrorType.CONFLICT, "실패 상태의 쿠폰만 재요청할 수 있습니다.");
        }
        this.status = MemberCouponStatus.REQUESTED;
        this.failReason = null;
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
