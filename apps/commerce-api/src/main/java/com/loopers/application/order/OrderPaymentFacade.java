package com.loopers.application.order;

import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderService.OrderItemRequest;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResponse;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.payment.PaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 주문+결제 복합 Use Case 전담 Facade
 *
 * OrderFacade(주문 조회)와 PaymentFacade(결제 조회/콜백/검증)의 상위 조율자로,
 * "주문 생성 → PG 결제" 흐름을 하나의 Use Case로 관리한다.
 *
 * 트랜잭션 분리 전략:
 *   [트랜잭션 1] OrderPlacementService.placeOrder — 주문 생성 (PENDING 상태로 커밋)
 *   [트랜잭션 밖] PG 결제 호출 (Resilience4j 적용)
 *   [트랜잭션 2] PaymentService — 결제 상태 업데이트
 *   [트랜잭션 3] 결제 실패 시 보상 트랜잭션 (재고/쿠폰/포인트 원복)
 */
@Slf4j
@Component
public class OrderPaymentFacade {

    private final OrderPlacementService orderPlacementService;
    private final OrderCompensationService orderCompensationService;
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final PaymentGateway paymentGateway;

    public OrderPaymentFacade(
            OrderPlacementService orderPlacementService,
            OrderCompensationService orderCompensationService,
            OrderService orderService,
            PaymentService paymentService,
            @Qualifier("resilience4jPaymentGateway") PaymentGateway paymentGateway) {
        this.orderPlacementService = orderPlacementService;
        this.orderCompensationService = orderCompensationService;
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.paymentGateway = paymentGateway;
    }

    /**
     * 주문 생성 + PG 결제 요청
     * - 결제 금액이 0 이하: 전액 할인이므로 결제 없이 바로 PAID 처리
     * - 결제 금액이 있으면: PG 결제 호출 (Resilience4j 적용)
     */
    public OrderDetailInfo createOrder(Long memberId, List<OrderItemRequest> itemRequests,
                                       Long memberCouponId, int usedPoints,
                                       List<Long> productOptionIdsFromCart,
                                       String cardType, String cardNo) {
        // [트랜잭션 1] 주문 생성 및 관련 처리 (PENDING 상태로 커밋)
        OrderDetailInfo orderInfo = orderPlacementService.placeOrder(
                memberId, itemRequests, memberCouponId, usedPoints, productOptionIdsFromCart);

        if (orderInfo.paymentAmount() <= 0) {
            orderService.completeOrder(orderInfo.id());
        } else {
            processPayment(memberId, orderInfo.id(), orderInfo.paymentAmount(), cardType, cardNo);
        }

        return orderInfo;
    }

    private void processPayment(Long memberId, Long orderId, int amount,
                                String cardType, String cardNo) {
        // [트랜잭션 2] Payment(REQUESTED) 생성/저장
        Payment payment = paymentService.initiatePayment(memberId, orderId, amount, cardType, cardNo);

        log.info("결제 요청 시작 paymentId={}, orderId={}, amount={}",
                payment.getId(), payment.getOrderId(), payment.getAmount());

        // [트랜잭션 밖] PG API 호출 (PaymentGateway 구현체가 장애 대응을 내부 처리)
        PaymentGatewayResponse response = paymentGateway.requestPayment(memberId, payment);

        // [트랜잭션 3] PG 응답에 따라 Payment 상태 업데이트
        paymentService.updatePaymentStatus(payment.getId(), response);

        // [트랜잭션 4] 즉시 실패(FAILED) 확인 시 보상 트랜잭션 실행
        // PENDING/UNKNOWN은 PG 콜백 또는 verify로 최종 상태가 결정되므로 여기서 보상하지 않는다.
        Payment updated = paymentService.findPayment(payment.getId());
        if (updated.getStatus() == PaymentStatus.FAILED) {
            log.warn("결제 즉시 실패 → 보상 시작 orderId={}, reason={}", orderId, updated.getFailureReason());
            orderCompensationService.compensateFailedOrder(orderId);
        }
    }
}
