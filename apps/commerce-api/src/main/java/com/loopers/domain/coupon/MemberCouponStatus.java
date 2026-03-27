package com.loopers.domain.coupon;

// 회원 쿠폰 상태
// REQUESTED: 발급 요청됨 (Kafka 처리 대기)
// AVAILABLE: 발급 완료 (사용 가능)
// USED: 사용 완료
// EXPIRED: 만료됨
// FAILED: 발급 실패
// DELETED: 삭제됨
public enum MemberCouponStatus {
    REQUESTED,
    AVAILABLE,
    USED,
    EXPIRED,
    FAILED,
    DELETED
}
