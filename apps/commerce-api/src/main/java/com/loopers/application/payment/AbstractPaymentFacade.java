package com.loopers.application.payment;

import com.loopers.application.order.OrderCompensationService;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResponse;
import com.loopers.domain.payment.PaymentGatewayStatusResponse;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.payment.SyncResult;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 Facade 공통 로직 — V1(수동 Resilience)과 V2(Resilience4j)가 공유하는 결제 처리 흐름
 *
 * 트랜잭션 분리 전략:
 *   [트랜잭션 1] Payment 생성/저장 → [트랜잭션 밖] PG 호출 → [트랜잭션 2] 상태 업데이트
 *
 * 서브클래스는 생성자에서 적절한 PaymentGateway 구현체를 주입하기만 하면 된다.
 */
@Slf4j
public abstract class AbstractPaymentFacade {

    protected final PaymentService paymentService;
    protected final PaymentGateway paymentGateway;
    protected final OrderService orderService;
    protected final OrderCompensationService orderCompensationService;

    protected AbstractPaymentFacade(PaymentService paymentService,
                                    PaymentGateway paymentGateway,
                                    OrderService orderService,
                                    OrderCompensationService orderCompensationService) {
        this.paymentService = paymentService;
        this.paymentGateway = paymentGateway;
        this.orderService = orderService;
        this.orderCompensationService = orderCompensationService;
    }

    /**
     * 결제 처리 메인 로직 — 3단계 분리 실행
     *
     * [트랜잭션 1] initiatePayment: 주문 검증 + Payment(REQUESTED) 생성/저장
     * [트랜잭션 밖] paymentGateway.requestPayment: PG API 호출 (장애 대응 포함)
     * [트랜잭션 2] updatePaymentStatus: PG 응답에 따라 Payment 상태 업데이트
     */
    public PaymentInfo processPayment(Long memberId, Long orderId, String cardType, String cardNo) {
        Order order = orderService.findOrderDetail(orderId, memberId);
        order.validatePayable();
        int amount = order.getPaymentAmount();

        Payment payment = paymentService.initiatePayment(memberId, orderId, amount, cardType, cardNo);

        log.info("결제 요청 시작 paymentId={}, orderId={}, amount={}",
                payment.getId(), payment.getOrderId(), payment.getAmount());

        PaymentGatewayResponse response = paymentGateway.requestPayment(memberId, payment);

        paymentService.updatePaymentStatus(payment.getId(), response);

        return PaymentInfo.from(paymentService.findPayment(payment.getId()));
    }

    /**
     * PG 콜백을 수신하여 결제/주문 상태를 동기화한다.
     * 이미 최종 상태(SUCCESS/FAILED)인 결제는 멱등성을 위해 무시한다.
     */
    public void handleCallback(Long orderId, String pgTransactionId, String pgStatus) {
        log.info("PG 콜백 수신 orderId={}, txId={}, status={}", orderId, pgTransactionId, pgStatus);
        SyncResult result = paymentService.syncPaymentResult(orderId, pgTransactionId, pgStatus);
        applyOrderStateChange(orderId, result);
    }

    /**
     * 결제 상태 검증 — 콜백 유실 시 PG 직접 조회로 상태를 보상(Compensation)한다.
     */
    public PaymentInfo verifyPayment(Long memberId, Long orderId) {
        paymentGateway.getPaymentStatus(memberId, String.valueOf(orderId))
                .filter(PaymentGatewayStatusResponse::isCompleted)
                .ifPresentOrElse(
                        pgStatus -> {
                            SyncResult result = paymentService.syncPaymentResult(orderId, pgStatus.transactionId(), pgStatus.status());
                            applyOrderStateChange(orderId, result);
                        },
                        () -> {
                            boolean failed = paymentService.markRequestedAsFailed(orderId);
                            if (failed) {
                                orderCompensationService.compensateFailedOrder(orderId);
                            }
                        }
                );

        Payment payment = paymentService.findPaymentByOrderId(orderId);
        return PaymentInfo.from(payment);
    }

    private void applyOrderStateChange(Long orderId, SyncResult result) {
        switch (result) {
            case SUCCESS -> orderService.completeOrder(orderId);
            case FAILED -> orderCompensationService.compensateFailedOrder(orderId);
            case SKIPPED -> log.info("이미 처리 완료된 결제 orderId={} → 주문 상태 변경 생략", orderId);
        }
    }
}
