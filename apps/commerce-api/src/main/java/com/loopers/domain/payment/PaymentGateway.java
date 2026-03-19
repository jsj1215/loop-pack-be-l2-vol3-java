package com.loopers.domain.payment;

import java.util.Optional;

/**
 * 결제 게이트웨이 도메인 인터페이스 — DIP(의존성 역전 원칙) 적용
 *
 * Application 레이어(PaymentFacade)가 Infrastructure 레이어(PgApiClient)에 직접 의존하지 않도록
 * Domain 레이어에 인터페이스를 정의하고, Infrastructure 레이어에서 구현체를 제공한다.
 *
 * 의존 방향:
 *   PaymentFacade (Application) → PaymentGateway (Domain) ← Resilience4jPaymentGateway (Infrastructure)
 */
public interface PaymentGateway {

    /**
     * PG에 결제를 요청하고 결과를 반환한다.
     * 구현체 내부에서 타임아웃, 서킷브레이커, 리트라이, fallback을 모두 처리하며,
     * 어떤 장애 상황에서도 예외를 던지지 않고 적절한 응답을 반환한다.
     */
    PaymentGatewayResponse requestPayment(Long memberId, Payment payment);

    /**
     * PG에 결제 상태를 직접 조회한다 (Polling 기반 보상).
     * 콜백 유실 시 verify API에서 호출하며, 조회 실패 시 빈 Optional을 반환한다.
     */
    Optional<PaymentGatewayStatusResponse> getPaymentStatus(Long memberId, String orderId);
}
