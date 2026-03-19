package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResponse;
import com.loopers.domain.payment.PaymentGatewayStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 수동 서킷브레이커 기반 PaymentGateway 구현체 (V1 결제 흐름에서 사용)
 *
 * ManualPgApiClient 내부의 수동 서킷브레이커/리트라이를 활용하며,
 * PG 전용 응답을 도메인 응답으로 변환한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ManualPaymentGateway implements PaymentGateway {

    private final ManualPgApiClient manualPgApiClient;

    @Override
    public PaymentGatewayResponse requestPayment(Long memberId, Payment payment) {
        try {
            PgPaymentRequest pgRequest = PgPaymentRequest.from(payment, manualPgApiClient.getCallbackUrl());
            PgPaymentResponse pgResponse = manualPgApiClient.requestPayment(memberId, pgRequest);
            return toGatewayResponse(pgResponse);
        } catch (Exception e) {
            log.error("PG 호출 중 예상치 못한 예외 발생 orderId={}", payment.getOrderId(), e);
            return PaymentGatewayResponse.failed("결제 처리 중 오류 발생");
        }
    }

    @Override
    public Optional<PaymentGatewayStatusResponse> getPaymentStatus(Long memberId, String orderId) {
        return manualPgApiClient.getPaymentStatus(memberId, orderId)
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
