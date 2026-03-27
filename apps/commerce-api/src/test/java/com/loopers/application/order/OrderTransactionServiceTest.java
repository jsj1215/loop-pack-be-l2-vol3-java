package com.loopers.application.order;

import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentGatewayResponse;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.payment.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OrderTransactionService 테스트")
@ExtendWith(MockitoExtension.class)
class OrderTransactionServiceTest {

    @Mock
    private OrderService orderService;

    @Mock
    private PaymentService paymentService;

    private OrderTransactionService orderTransactionService;

    @BeforeEach
    void setUp() {
        orderTransactionService = new OrderTransactionService(orderService, paymentService);
    }

    @Test
    @DisplayName("전액 할인 — 주문 완료를 처리한다")
    void completeOrder_delegatesToOrderService() {
        // given
        Long orderId = 1L;

        // when
        orderTransactionService.completeOrder(orderId);

        // then
        verify(orderService).completeOrder(orderId);
    }

    @Test
    @DisplayName("결제 성공 — 결제 상태를 업데이트하고 SUCCESS를 반환한다")
    void updatePaymentStatus_success() {
        // given
        Long paymentId = 1L;
        PaymentGatewayResponse response = mock(PaymentGatewayResponse.class);
        Payment payment = mock(Payment.class);
        when(payment.getStatus()).thenReturn(PaymentStatus.SUCCESS);
        when(paymentService.findPayment(paymentId)).thenReturn(payment);

        // when
        PaymentStatus status = orderTransactionService.updatePaymentStatus(paymentId, response);

        // then
        assertThat(status).isEqualTo(PaymentStatus.SUCCESS);
        verify(paymentService).updatePaymentStatus(paymentId, response);
    }

    @Test
    @DisplayName("결제 실패 — FAILED를 반환한다")
    void updatePaymentStatus_failed() {
        // given
        Long paymentId = 1L;
        PaymentGatewayResponse response = mock(PaymentGatewayResponse.class);
        Payment payment = mock(Payment.class);
        when(payment.getStatus()).thenReturn(PaymentStatus.FAILED);
        when(paymentService.findPayment(paymentId)).thenReturn(payment);

        // when
        PaymentStatus status = orderTransactionService.updatePaymentStatus(paymentId, response);

        // then
        assertThat(status).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("결제 PENDING — PENDING을 반환한다")
    void updatePaymentStatus_pending() {
        // given
        Long paymentId = 1L;
        PaymentGatewayResponse response = mock(PaymentGatewayResponse.class);
        Payment payment = mock(Payment.class);
        when(payment.getStatus()).thenReturn(PaymentStatus.PENDING);
        when(paymentService.findPayment(paymentId)).thenReturn(payment);

        // when
        PaymentStatus status = orderTransactionService.updatePaymentStatus(paymentId, response);

        // then
        assertThat(status).isEqualTo(PaymentStatus.PENDING);
    }
}
