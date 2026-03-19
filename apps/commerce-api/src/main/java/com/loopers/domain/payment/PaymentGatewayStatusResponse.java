package com.loopers.domain.payment;

/**
 * 결제 게이트웨이 상태 조회 응답 — verify API에서 PG 직접 조회 결과를 담는 도메인 객체
 *
 * @param transactionId PG가 발급한 고유 트랜잭션 ID
 * @param orderId       주문 ID
 * @param status        PG의 현재 결제 상태 ("SUCCESS", "FAILED", "LIMIT_EXCEEDED", "INVALID_CARD" 등)
 */
public record PaymentGatewayStatusResponse(
        String transactionId,
        String orderId,
        String status) {

    /**
     * PG에서 결제 처리가 완료된 상태인지 판단한다.
     * 완료 상태에만 우리 DB를 동기화하며, 아직 처리 중(PENDING 등)이면 동기화하지 않는다.
     */
    public boolean isCompleted() {
        return "SUCCESS".equals(status) || "FAILED".equals(status)
                || "LIMIT_EXCEEDED".equals(status) || "INVALID_CARD".equals(status);
    }
}
