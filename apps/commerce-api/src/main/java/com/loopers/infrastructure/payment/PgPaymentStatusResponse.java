package com.loopers.infrastructure.payment;

/**
 * PG 결제 상태 조회 응답 DTO — verify API에서 PG에 직접 결제 상태를 조회한 결과
 *
 * PG API의 GET /api/v1/payments?orderId=... 응답을 매핑한다.
 * 콜백 유실 시 Polling 기반 보상 메커니즘에서 사용된다.
 *
 * @param transactionId PG가 발급한 고유 트랜잭션 ID
 * @param orderId       우리 시스템의 주문 ID
 * @param status        PG의 현재 결제 상태 ("SUCCESS", "FAILED", "LIMIT_EXCEEDED", "INVALID_CARD", "PENDING" 등)
 * @param message       상세 메시지
 */
public record PgPaymentStatusResponse(
        String transactionId,
        String orderId,
        String status,
        String message) {
}
