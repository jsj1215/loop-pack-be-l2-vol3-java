package com.loopers.application.order;

import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentGatewayResponse;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 주문/결제 비즈니스 처리를 하나의 트랜잭션으로 묶는 서비스.
//
// OrderPaymentFacade는 @Transactional이 없는 조율자 역할이므로,
// 원자성이 필요한 DB 작업들을 이 서비스에서 하나의 TX로 묶어 처리한다.
// 이 서비스에서 묶는 구간은 전부 DB 작업이며 외부 API 호출이 포함되지 않는다.
@Slf4j
@RequiredArgsConstructor
@Component
public class OrderTransactionService {

    private final OrderService orderService;
    private final PaymentService paymentService;

    // 전액 할인 주문 완료를 하나의 TX로 처리한다.
    @Transactional
    public void completeOrder(Long orderId) {
        orderService.completeOrder(orderId);
        log.info("전액 할인 주문 완료: orderId={}", orderId);
    }

    // 결제 상태 업데이트를 하나의 TX로 처리한다.
    @Transactional
    public PaymentStatus updatePaymentStatus(Long paymentId, PaymentGatewayResponse response) {
        paymentService.updatePaymentStatus(paymentId, response);
        Payment updated = paymentService.findPayment(paymentId);
        return updated.getStatus();
    }

}
