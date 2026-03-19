package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResponse;
import com.loopers.domain.payment.PaymentGatewayStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resilience4j 기반 PaymentGateway 구현체 (V2 결제 흐름에서 사용)
 *
 * PgApiClient의 Resilience4j 어노테이션(@TimeLimiter/@CircuitBreaker/@Retry)을
 * Spring AOP 프록시를 통해 호출하며, PG 전용 응답을 도메인 응답으로 변환한다.
 *
 * 2단계 Fallback:
 *   [1단계] PgApiClient 내부 Resilience4j Fallback → PgPaymentResponse.timeout()/failed()
 *   [2단계] 이 클래스의 try-catch (최외곽 방어) → PaymentGatewayResponse.failed()
 *
 * @Primary: 두 PaymentGateway 구현체 중 기본 주입 대상으로 사용된다.
 */
@Slf4j
@RequiredArgsConstructor
@Primary
@Component
public class Resilience4jPaymentGateway implements PaymentGateway {

    private final PgApiClient pgApiClient;

    @Override
    public PaymentGatewayResponse requestPayment(Long memberId, Payment payment) {
        try {
            PgPaymentRequest pgRequest = PgPaymentRequest.from(payment, pgApiClient.getCallbackUrl());
            PgPaymentResponse pgResponse = pgApiClient.requestPayment(memberId, pgRequest).join();
            return toGatewayResponse(pgResponse);
        } catch (Exception e) {
            log.error("PG 호출 중 예상치 못한 예외 발생 orderId={}", payment.getOrderId(), e);
            return PaymentGatewayResponse.failed("결제 처리 중 오류 발생");
        }
    }

    @Override
    public Optional<PaymentGatewayStatusResponse> getPaymentStatus(Long memberId, String orderId) {
        return pgApiClient.getPaymentStatus(memberId, orderId)
                .map(r -> new PaymentGatewayStatusResponse(r.transactionId(), r.orderId(), r.status()));
    }

    private PaymentGatewayResponse toGatewayResponse(PgPaymentResponse pgResponse) {
        return new PaymentGatewayResponse(
                pgResponse.transactionId(),
                pgResponse.isAccepted(),
                pgResponse.isTimeout(),
                pgResponse.message());
    }
}
