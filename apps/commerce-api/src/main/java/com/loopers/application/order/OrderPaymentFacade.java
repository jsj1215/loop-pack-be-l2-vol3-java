package com.loopers.application.order;

import com.loopers.domain.event.OrderFailedEvent;
import com.loopers.domain.event.OrderPaidEvent;
import com.loopers.domain.order.OrderService.OrderItemRequest;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResponse;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.payment.PaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

// 주문+결제 복합 Use Case 전담 Facade
//
// 트랜잭션 분리 전략:
//   [트랜잭션 1] OrderPlacementService.placeOrder — 주문 생성 (PENDING 상태로 커밋)
//   [트랜잭션 밖] PG 결제 호출 (Resilience4j 적용)
//   [트랜잭션 2] OrderTransactionService — 결제 상태 업데이트
//   [트랜잭션 3] 결제 실패 시 보상 트랜잭션 (재고/쿠폰/포인트 원복)
//
// Kafka 이벤트 발행:
// - 성공 경로: OrderPaidEvent를 ApplicationEventPublisher로 발행.
//   KafkaEventPublishListener가 AFTER_COMMIT에서 직접 Kafka 발행 (통계성 이벤트, 유실 허용)
// - 실패 경로: OrderFailedEvent를 ApplicationEventPublisher로 발행 (Kafka 전파 대상 아님)
//
// 주의: 이벤트 발행 시점은 OrderTransactionService의 TX가 커밋된 이후(TX 밖)이다.
// 따라서 @TransactionalEventListener(AFTER_COMMIT) 리스너는 fallbackExecution=true가 없으면
// 이벤트를 수신하지 못한다. 새로운 리스너 추가 시 반드시 fallbackExecution=true를 설정할 것.
@Slf4j
@Component
public class OrderPaymentFacade {

    private final OrderPlacementService orderPlacementService;
    private final OrderCompensationService orderCompensationService;
    private final OrderTransactionService orderTransactionService;
    private final PaymentService paymentService;
    private final PaymentGateway paymentGateway;
    private final ApplicationEventPublisher eventPublisher;

    public OrderPaymentFacade(
            OrderPlacementService orderPlacementService,
            OrderCompensationService orderCompensationService,
            OrderTransactionService orderTransactionService,
            PaymentService paymentService,
            @Qualifier("resilience4jPaymentGateway") PaymentGateway paymentGateway,
            ApplicationEventPublisher eventPublisher) {
        this.orderPlacementService = orderPlacementService;
        this.orderCompensationService = orderCompensationService;
        this.orderTransactionService = orderTransactionService;
        this.paymentService = paymentService;
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
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
            // 전액 할인 → 결제 없이 주문 완료
            orderTransactionService.completeOrder(orderInfo.id());

            // OrderPaidEvent 발행 → KafkaEventPublishListener가 Kafka 발행 + 기존 리스너(로깅, 알림)
            eventPublisher.publishEvent(new OrderPaidEvent(
                    orderInfo.id(), memberId, orderInfo.totalAmount(),
                    toOrderedProductEvents(orderInfo)));
        } else {
            processPayment(memberId, orderInfo, cardType, cardNo);
        }

        return orderInfo;
    }

    // PG 결제 처리.
    private void processPayment(Long memberId, OrderDetailInfo orderInfo,
                                String cardType, String cardNo) {
        Long orderId = orderInfo.id();
        int totalAmount = orderInfo.totalAmount();

        try {
            // [트랜잭션 2] Payment(REQUESTED) 생성/저장
            Payment payment = paymentService.initiatePayment(
                    memberId, orderId, orderInfo.paymentAmount(), cardType, cardNo);

            log.info("결제 요청 시작 paymentId={}, orderId={}, amount={}",
                    payment.getId(), payment.getOrderId(), payment.getAmount());

            // [트랜잭션 밖] PG API 호출
            PaymentGatewayResponse response = paymentGateway.requestPayment(memberId, payment);

            // [트랜잭션 3] PG 응답에 따라 Payment 상태 업데이트
            PaymentStatus status = orderTransactionService.updatePaymentStatus(payment.getId(), response);

            // 결제 결과에 따른 후속 처리
            if (status == PaymentStatus.FAILED) {
                Payment updated = paymentService.findPayment(payment.getId());
                log.warn("결제 즉시 실패 → 보상 시작 orderId={}, reason={}", orderId, updated.getFailureReason());
                orderCompensationService.compensateFailedOrder(orderId);
                eventPublisher.publishEvent(new OrderFailedEvent(orderId, memberId, updated.getFailureReason()));
            } else if (status == PaymentStatus.SUCCESS) {
                eventPublisher.publishEvent(new OrderPaidEvent(
                        orderId, memberId, totalAmount, toOrderedProductEvents(orderInfo)));
            }
        } catch (Exception e) {
            log.error("결제 처리 중 예외 발생 → 보상 시작 orderId={}", orderId, e);
            orderCompensationService.compensateFailedOrder(orderId);
            eventPublisher.publishEvent(new OrderFailedEvent(orderId, memberId, e.getMessage()));
            throw e;
        }
    }

    private List<OrderPaidEvent.OrderedProduct> toOrderedProductEvents(OrderDetailInfo orderInfo) {
        return orderInfo.orderItems().stream()
                .map(item -> new OrderPaidEvent.OrderedProduct(item.productId(), item.quantity()))
                .toList();
    }
}
