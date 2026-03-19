package com.loopers.application.payment;

import com.loopers.application.order.OrderCompensationService;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResponse;
import com.loopers.domain.payment.PaymentGatewayStatusResponse;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.payment.SyncResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ManualPaymentFacade 단위 테스트 (V1 수동 Resilience)")
class ManualPaymentFacadeTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private OrderService orderService;

    @Mock
    private OrderCompensationService orderCompensationService;

    @InjectMocks
    private ManualPaymentFacade manualPaymentFacade;

    private Payment createPayment(Long paymentId, Long orderId, PaymentStatus status) {
        Payment payment = Payment.create(orderId, 1L, 50000, "SAMSUNG", "1234-5678-9012-3456");
        ReflectionTestUtils.setField(payment, "id", paymentId);
        if (status == PaymentStatus.PENDING) {
            payment.markPending("tx-123");
        }
        return payment;
    }

    private Order createOrderStub(Long orderId, Long memberId, int totalAmount) {
        try {
            java.lang.reflect.Constructor<Order> ctor = Order.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            Order order = ctor.newInstance();
            ReflectionTestUtils.setField(order, "id", orderId);
            ReflectionTestUtils.setField(order, "memberId", memberId);
            ReflectionTestUtils.setField(order, "totalAmount", totalAmount);
            ReflectionTestUtils.setField(order, "discountAmount", 0);
            ReflectionTestUtils.setField(order, "usedPoints", 0);
            return order;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("결제를 처리할 때,")
    class ProcessPayment {

        @Test
        @DisplayName("PG 접수 성공 시 PENDING 상태로 업데이트한다.")
        void handlesPgAccepted() {
            // given
            Long memberId = 1L;
            Long orderId = 100L;
            Payment payment = createPayment(1L, orderId, PaymentStatus.REQUESTED);
            Payment pendingPayment = createPayment(1L, orderId, PaymentStatus.PENDING);

            Order order = createOrderStub(orderId, memberId, 50000);
            when(orderService.findOrderDetail(orderId, memberId)).thenReturn(order);
            when(paymentService.initiatePayment(memberId, orderId, 50000, "SAMSUNG", "1234"))
                    .thenReturn(payment);
            when(paymentGateway.requestPayment(eq(memberId), any(Payment.class)))
                    .thenReturn(new PaymentGatewayResponse("20250316:TR:abc123", true, false, null));
            when(paymentService.findPayment(1L)).thenReturn(pendingPayment);

            // when
            PaymentInfo result = manualPaymentFacade.processPayment(memberId, orderId, "SAMSUNG", "1234");

            // then
            verify(paymentService).updatePaymentStatus(eq(1L),
                    eq(new PaymentGatewayResponse("20250316:TR:abc123", true, false, null)));
        }

        @Test
        @DisplayName("PG 접수 실패 시 FAILED 상태로 업데이트한다.")
        void handlesPgRejection() {
            // given
            Long memberId = 1L;
            Long orderId = 100L;
            Payment payment = createPayment(1L, orderId, PaymentStatus.REQUESTED);
            Payment failedPayment = createPayment(1L, orderId, PaymentStatus.REQUESTED);
            ReflectionTestUtils.setField(failedPayment, "status", PaymentStatus.FAILED);

            Order order = createOrderStub(orderId, memberId, 50000);
            when(orderService.findOrderDetail(orderId, memberId)).thenReturn(order);
            when(paymentService.initiatePayment(memberId, orderId, 50000, "SAMSUNG", "1234"))
                    .thenReturn(payment);
            when(paymentGateway.requestPayment(eq(memberId), any(Payment.class)))
                    .thenReturn(PaymentGatewayResponse.failed("PG 시스템 장애"));
            when(paymentService.findPayment(1L)).thenReturn(failedPayment);

            // when
            PaymentInfo result = manualPaymentFacade.processPayment(memberId, orderId, "SAMSUNG", "1234");

            // then
            verify(paymentService).updatePaymentStatus(eq(1L), eq(PaymentGatewayResponse.failed("PG 시스템 장애")));
            assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("PG 타임아웃 시 UNKNOWN 상태로 업데이트한다.")
        void handlesPgTimeout() {
            // given
            Long memberId = 1L;
            Long orderId = 100L;
            Payment payment = createPayment(1L, orderId, PaymentStatus.REQUESTED);
            Payment unknownPayment = createPayment(1L, orderId, PaymentStatus.REQUESTED);
            ReflectionTestUtils.setField(unknownPayment, "status", PaymentStatus.UNKNOWN);

            Order order = createOrderStub(orderId, memberId, 50000);
            when(orderService.findOrderDetail(orderId, memberId)).thenReturn(order);
            when(paymentService.initiatePayment(memberId, orderId, 50000, "SAMSUNG", "1234"))
                    .thenReturn(payment);
            when(paymentGateway.requestPayment(eq(memberId), any(Payment.class)))
                    .thenReturn(PaymentGatewayResponse.ofTimeout());
            when(paymentService.findPayment(1L)).thenReturn(unknownPayment);

            // when
            PaymentInfo result = manualPaymentFacade.processPayment(memberId, orderId, "SAMSUNG", "1234");

            // then
            verify(paymentService).updatePaymentStatus(eq(1L), eq(PaymentGatewayResponse.ofTimeout()));
            assertThat(result.status()).isEqualTo(PaymentStatus.UNKNOWN);
        }

        @Test
        @DisplayName("PaymentGateway가 예외를 던지면 전파한다.")
        void handlesUnexpectedException() {
            // given
            Long memberId = 1L;
            Long orderId = 100L;
            Payment payment = createPayment(1L, orderId, PaymentStatus.REQUESTED);

            Order order = createOrderStub(orderId, memberId, 50000);
            when(orderService.findOrderDetail(orderId, memberId)).thenReturn(order);
            when(paymentService.initiatePayment(memberId, orderId, 50000, "SAMSUNG", "1234"))
                    .thenReturn(payment);
            when(paymentGateway.requestPayment(eq(memberId), any(Payment.class)))
                    .thenThrow(new RuntimeException("예상치 못한 예외"));

            // when & then
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> manualPaymentFacade.processPayment(memberId, orderId, "SAMSUNG", "1234"));
        }
    }

    @Nested
    @DisplayName("콜백을 처리할 때,")
    class HandleCallback {

        @Test
        @DisplayName("PG 콜백 SUCCESS 시 결제 동기화 후 주문을 완료 처리한다.")
        void completesOrderOnSuccessCallback() {
            // given
            Long orderId = 100L;
            String txId = "20250316:TR:abc123";
            when(paymentService.syncPaymentResult(orderId, txId, "SUCCESS"))
                    .thenReturn(SyncResult.SUCCESS);

            // when
            manualPaymentFacade.handleCallback(orderId, txId, "SUCCESS");

            // then
            verify(paymentService).syncPaymentResult(orderId, txId, "SUCCESS");
            verify(orderService).completeOrder(orderId);
        }

        @Test
        @DisplayName("PG 콜백 FAILED 시 결제 동기화 후 주문 실패 처리 및 재고를 복구한다.")
        void failsOrderAndRestoresStockOnFailedCallback() {
            // given
            Long orderId = 100L;
            String txId = "20250316:TR:abc123";
            when(paymentService.syncPaymentResult(orderId, txId, "LIMIT_EXCEEDED"))
                    .thenReturn(SyncResult.FAILED);

            // when
            manualPaymentFacade.handleCallback(orderId, txId, "LIMIT_EXCEEDED");

            // then
            verify(orderCompensationService).compensateFailedOrder(orderId);
        }

        @Test
        @DisplayName("이미 처리 완료된 결제 콜백이면 주문 상태를 변경하지 않는다.")
        void doesNotChangeOrderWhenSkipped() {
            // given
            Long orderId = 100L;
            String txId = "20250316:TR:abc123";
            when(paymentService.syncPaymentResult(orderId, txId, "SUCCESS"))
                    .thenReturn(SyncResult.SKIPPED);

            // when
            manualPaymentFacade.handleCallback(orderId, txId, "SUCCESS");

            // then
            verify(orderService, never()).completeOrder(any());
            verify(orderCompensationService, never()).compensateFailedOrder(any());
        }
    }

    @Nested
    @DisplayName("결제 상태를 확인할 때,")
    class VerifyPayment {

        @Test
        @DisplayName("PG 상태가 SUCCESS이면 결제 동기화 후 주문을 완료 처리한다.")
        void completesOrderWhenPgSuccess() {
            // given
            Long memberId = 1L;
            Long orderId = 100L;
            Payment payment = createPayment(1L, orderId, PaymentStatus.PENDING);

            when(paymentGateway.getPaymentStatus(memberId, "100"))
                    .thenReturn(Optional.of(new PaymentGatewayStatusResponse("tx-123", "100", "SUCCESS")));
            when(paymentService.syncPaymentResult(orderId, "tx-123", "SUCCESS"))
                    .thenReturn(SyncResult.SUCCESS);
            when(paymentService.findPaymentByOrderId(orderId)).thenReturn(payment);

            // when
            manualPaymentFacade.verifyPayment(memberId, orderId);

            // then
            verify(paymentService).syncPaymentResult(orderId, "tx-123", "SUCCESS");
            verify(orderService).completeOrder(orderId);
        }

        @Test
        @DisplayName("PG 상태가 FAILED이면 결제 동기화 후 주문 실패 처리 및 재고를 복구한다.")
        void failsOrderWhenPgFailed() {
            // given
            Long memberId = 1L;
            Long orderId = 100L;
            Payment payment = createPayment(1L, orderId, PaymentStatus.PENDING);
            ReflectionTestUtils.setField(payment, "status", PaymentStatus.FAILED);

            when(paymentGateway.getPaymentStatus(memberId, "100"))
                    .thenReturn(Optional.of(new PaymentGatewayStatusResponse("tx-123", "100", "FAILED")));
            when(paymentService.syncPaymentResult(orderId, "tx-123", "FAILED"))
                    .thenReturn(SyncResult.FAILED);
            when(paymentService.findPaymentByOrderId(orderId)).thenReturn(payment);

            // when
            manualPaymentFacade.verifyPayment(memberId, orderId);

            // then
            verify(orderCompensationService).compensateFailedOrder(orderId);
        }

        @Test
        @DisplayName("PG 조회 결과가 없고 REQUESTED 상태면 FAILED로 전환한다.")
        void marksRequestedAsFailedWhenPgHasNoResult() {
            // given
            Long memberId = 1L;
            Long orderId = 100L;
            Payment payment = createPayment(1L, orderId, PaymentStatus.REQUESTED);
            ReflectionTestUtils.setField(payment, "status", PaymentStatus.FAILED);

            when(paymentGateway.getPaymentStatus(memberId, "100")).thenReturn(Optional.empty());
            when(paymentService.findPaymentByOrderId(orderId)).thenReturn(payment);

            // when
            PaymentInfo result = manualPaymentFacade.verifyPayment(memberId, orderId);

            // then
            verify(paymentService).markRequestedAsFailed(orderId);
            assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("PG 상태가 미완료이면 동기화하지 않는다.")
        void doesNotSyncWhenPgNotCompleted() {
            // given
            Long memberId = 1L;
            Long orderId = 100L;
            Payment payment = createPayment(1L, orderId, PaymentStatus.PENDING);

            when(paymentGateway.getPaymentStatus(memberId, "100"))
                    .thenReturn(Optional.of(new PaymentGatewayStatusResponse("tx-123", "100", "PROCESSING")));
            when(paymentService.findPaymentByOrderId(orderId)).thenReturn(payment);

            // when
            manualPaymentFacade.verifyPayment(memberId, orderId);

            // then
            verify(paymentService, never()).syncPaymentResult(any(), any(), any());
        }

        @Test
        @DisplayName("PG 상태 조회가 비어있으면 동기화하지 않는다.")
        void doesNotSyncWhenPgStatusEmpty() {
            // given
            Long memberId = 1L;
            Long orderId = 100L;
            Payment payment = createPayment(1L, orderId, PaymentStatus.PENDING);

            when(paymentGateway.getPaymentStatus(memberId, "100")).thenReturn(Optional.empty());
            when(paymentService.findPaymentByOrderId(orderId)).thenReturn(payment);

            // when
            manualPaymentFacade.verifyPayment(memberId, orderId);

            // then
            verify(paymentService, never()).syncPaymentResult(any(), any(), any());
        }
    }
}
