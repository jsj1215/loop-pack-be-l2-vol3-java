package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.Payment;

/**
 * PG 결제 요청 DTO — PG API에 전송할 결제 정보를 담는 불변(Immutable) 객체
 *
 * 모든 필드를 String으로 직렬화하는 이유:
 *   PG API의 요청 스펙에 맞추기 위함이다. 외부 시스템과의 통신에서는
 *   우리 도메인의 타입(Long, int)이 아닌 PG가 요구하는 형식으로 변환해야 한다.
 *
 * @param orderId     우리 시스템의 주문 ID (PG에서 콜백 시 이 값으로 매핑)
 * @param cardType    카드 종류 (VISA, MASTER 등)
 * @param cardNo      카드 번호
 * @param amount      결제 금액 (문자열로 직렬화)
 * @param callbackUrl PG가 결제 완료 후 결과를 전송할 Webhook URL
 */
public record PgPaymentRequest(
        String orderId,
        String cardType,
        String cardNo,
        String amount,
        String callbackUrl) {

    /**
     * 도메인 엔티티(Payment)로부터 PG 요청 DTO를 생성하는 팩토리 메서드.
     * 레이어 간 의존성: Infrastructure DTO가 Domain 엔티티를 참조하여 변환한다.
     * (Domain → Infrastructure 방향의 의존은 DIP 위반이 아님: 인프라 계층이 도메인을 참조)
     */
    public static PgPaymentRequest from(Payment payment, String callbackUrl) {
        return new PgPaymentRequest(
                String.valueOf(payment.getOrderId()),
                payment.getCardType(),
                payment.getCardNo(),
                String.valueOf(payment.getAmount()),
                callbackUrl);
    }
}
