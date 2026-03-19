package com.loopers.application.payment;

import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentStatus;

import java.time.ZonedDateTime;

/**
 * Application 계층의 결제 정보 전달 객체 (Info DTO)
 *
 * 역할:
 *   Domain 엔티티(Payment)를 Application → Interfaces 계층으로 전달할 때 사용하는 중간 DTO이다.
 *   도메인 엔티티를 컨트롤러에 직접 노출하지 않아, 계층 간 의존성을 느슨하게 유지한다.
 *
 * 변환 흐름:
 *   Domain(Payment) → Application(PaymentInfo) → Interfaces(PaymentV1Dto.PaymentResponse) → JSON
 *
 * record를 사용하여 불변 객체로 구성하며, 모든 필드가 생성 시 결정되어 변경 불가하다.
 */
public record PaymentInfo(
        Long id,
        Long orderId,
        Long memberId,
        int amount,
        String cardType,
        PaymentStatus status,
        String pgTransactionId,
        String failureReason,
        ZonedDateTime createdAt) {

    /** Domain 엔티티(Payment)로부터 Application DTO를 생성하는 팩토리 메서드 */
    public static PaymentInfo from(Payment payment) {
        return new PaymentInfo(
                payment.getId(),
                payment.getOrderId(),
                payment.getMemberId(),
                payment.getAmount(),
                payment.getCardType(),
                payment.getStatus(),
                payment.getPgTransactionId(),
                payment.getFailureReason(),
                payment.getCreatedAt());
    }
}
