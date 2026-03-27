package com.loopers.domain.coupon;

// 회원 쿠폰 상태 (commerce-streamer용)
public enum MemberCouponStatus {
    REQUESTED,
    AVAILABLE,
    USED,
    EXPIRED,
    FAILED,
    DELETED
}
