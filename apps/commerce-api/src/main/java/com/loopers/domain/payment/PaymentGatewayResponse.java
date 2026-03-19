package com.loopers.domain.payment;

/**
 * 결제 게이트웨이 응답 — PG 구현체에 독립적인 도메인 레벨 응답 객체
 *
 * Infrastructure 레이어의 PG 전용 응답(PgPaymentResponse)을 Domain 레이어에서 사용할 수 있도록
 * 추상화한 객체이다. Application 레이어(Facade)는 이 타입만 참조한다.
 *
 * @param transactionId PG가 발급한 고유 트랜잭션 ID (접수 성공 시에만 존재)
 * @param accepted      PG 접수 성공 여부
 * @param timeout       타임아웃 발생 여부 (PG 도달 여부 불확실)
 * @param message       상세 메시지 (실패 사유 등)
 */
public record PaymentGatewayResponse(
        String transactionId,
        boolean accepted,
        boolean timeout,
        String message) {

    public static PaymentGatewayResponse failed(String message) {
        return new PaymentGatewayResponse(null, false, false, message);
    }

    public static PaymentGatewayResponse ofTimeout() {
        return new PaymentGatewayResponse(null, false, true, "PG 응답 타임아웃");
    }
}
