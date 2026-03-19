package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService 단위 테스트")
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private PaymentService paymentService;

    private Payment createPaymentWithId(Long paymentId, Long orderId, PaymentStatus status) {
        Payment payment = Payment.create(orderId, 1L, 50000, "SAMSUNG", "1234-5678-9012-3456");
        ReflectionTestUtils.setField(payment, "id", paymentId);
        if (status == PaymentStatus.PENDING) {
            payment.markPending("tx-123");
        } else if (status == PaymentStatus.FAILED) {
            payment.markFailed("LIMIT_EXCEEDED");
        }
        return payment;
    }

    @Nested
    @DisplayName("결제를 시작할 때,")
    class InitiatePayment {

        @Test
        @DisplayName("REQUESTED 상태의 Payment를 생성한다.")
        void createsPaymentWithRequestedStatus() {
            // given
            Long memberId = 1L;
            Long orderId = 100L;

            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
            when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
                Payment saved = invocation.getArgument(0);
                ReflectionTestUtils.setField(saved, "id", 1L);
                return saved;
            });

            // when
            Payment result = paymentService.initiatePayment(memberId, orderId, 50000, "SAMSUNG", "1234-5678-9012-3456");

            // then
            assertAll(
                    () -> assertThat(result.getOrderId()).isEqualTo(orderId),
                    () -> assertThat(result.getMemberId()).isEqualTo(memberId),
                    () -> assertThat(result.getAmount()).isEqualTo(50000),
                    () -> assertThat(result.getStatus()).isEqualTo(PaymentStatus.REQUESTED),
                    () -> verify(paymentRepository).save(any(Payment.class))
            );
        }

        @Test
        @DisplayName("이미 진행 중인 결제가 있으면 예외가 발생한다.")
        void throwsWhenPaymentAlreadyInProgress() {
            // given
            Long memberId = 1L;
            Long orderId = 100L;
            Payment existingPayment = createPaymentWithId(1L, orderId, PaymentStatus.PENDING);

            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(existingPayment));

            // when & then
            assertThrows(CoreException.class,
                    () -> paymentService.initiatePayment(memberId, orderId, 50000, "SAMSUNG", "1234"));
        }

        @Test
        @DisplayName("이전 결제가 FAILED 상태면 기존 Payment를 재사용하여 REQUESTED로 리셋한다.")
        void reusesExistingPaymentWhenPreviousFailed() {
            // given
            Long memberId = 1L;
            Long orderId = 100L;
            Payment failedPayment = createPaymentWithId(1L, orderId, PaymentStatus.FAILED);

            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(failedPayment));

            // when
            Payment result = paymentService.initiatePayment(memberId, orderId, 50000, "HYUNDAI", "9999-8888-7777-6666");

            // then
            assertAll(
                    () -> assertThat(result.getId()).isEqualTo(1L),
                    () -> assertThat(result.getStatus()).isEqualTo(PaymentStatus.REQUESTED),
                    () -> assertThat(result.getCardType()).isEqualTo("HYUNDAI"),
                    () -> assertThat(result.getCardNo()).isEqualTo("9999-8888-7777-6666"),
                    () -> assertThat(result.getPgTransactionId()).isNull(),
                    () -> assertThat(result.getFailureReason()).isNull()
            );
        }
    }

    @Nested
    @DisplayName("PG 응답을 처리할 때,")
    class HandlePgResponse {

        @Test
        @DisplayName("PG가 접수하면 PENDING 상태로 전이한다.")
        void transitionsToPendingWhenAccepted() {
            // given
            Payment payment = createPaymentWithId(1L, 100L, PaymentStatus.REQUESTED);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

            // when
            paymentService.handlePgResponse(1L, true, "20250316:TR:abc123", null);

            // then
            assertAll(
                    () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING),
                    () -> assertThat(payment.getPgTransactionId()).isEqualTo("20250316:TR:abc123")
            );
        }

        @Test
        @DisplayName("PG가 거부하면 FAILED 상태로 전이한다.")
        void transitionsToFailedWhenRejected() {
            // given
            Payment payment = createPaymentWithId(1L, 100L, PaymentStatus.REQUESTED);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

            // when
            paymentService.handlePgResponse(1L, false, null, "PG 시스템 장애");

            // then
            assertAll(
                    () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED),
                    () -> assertThat(payment.getFailureReason()).isEqualTo("PG 시스템 장애")
            );
        }

        @Test
        @DisplayName("PG 응답이 불명확하면 UNKNOWN 상태로 전이한다.")
        void transitionsToUnknownWhenUnclear() {
            // given
            Payment payment = createPaymentWithId(1L, 100L, PaymentStatus.REQUESTED);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

            // when
            paymentService.handlePgResponse(1L, false, null, null);

            // then
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("결제 결과를 동기화할 때,")
    class SyncPaymentResult {

        @Test
        @DisplayName("PG 성공이면 Payment를 SUCCESS로 변경하고 SyncResult.SUCCESS를 반환한다.")
        void syncsSuccessResult() {
            // given
            Long orderId = 100L;
            Payment payment = createPaymentWithId(1L, orderId, PaymentStatus.PENDING);

            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

            // when
            SyncResult result = paymentService.syncPaymentResult(orderId, "tx-123", "SUCCESS");

            // then
            assertAll(
                    () -> assertThat(result).isEqualTo(SyncResult.SUCCESS),
                    () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS)
            );
        }

        @Test
        @DisplayName("PG 실패면 Payment를 FAILED로 변경하고 SyncResult.FAILED를 반환한다.")
        void syncsFailureResult() {
            // given
            Long orderId = 100L;
            Payment payment = createPaymentWithId(1L, orderId, PaymentStatus.PENDING);

            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

            // when
            SyncResult result = paymentService.syncPaymentResult(orderId, "tx-123", "LIMIT_EXCEEDED");

            // then
            assertAll(
                    () -> assertThat(result).isEqualTo(SyncResult.FAILED),
                    () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED)
            );
        }

        @Test
        @DisplayName("이미 처리 완료된 결제면 SyncResult.SKIPPED를 반환한다 (멱등성).")
        void skipsWhenAlreadyFinalized() {
            // given
            Long orderId = 100L;
            Payment payment = Payment.create(orderId, 1L, 50000, "SAMSUNG", "1234");
            payment.markSuccess("tx-123");

            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

            // when
            SyncResult result = paymentService.syncPaymentResult(orderId, "tx-456", "SUCCESS");

            // then
            assertAll(
                    () -> assertThat(result).isEqualTo(SyncResult.SKIPPED),
                    () -> assertThat(payment.getPgTransactionId()).isEqualTo("tx-123")
            );
        }

        @Test
        @DisplayName("동일한 콜백이 중복으로 와도 멱등하게 처리된다.")
        void handlesIdenticalDuplicateCallback() {
            // given
            Long orderId = 100L;
            Payment payment = Payment.create(orderId, 1L, 50000, "SAMSUNG", "1234");
            payment.markPending("tx-123");

            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

            // when - 첫 번째 콜백
            SyncResult first = paymentService.syncPaymentResult(orderId, "tx-123", "SUCCESS");

            // then - 두 번째 동일 콜백은 이미 finalized이므로 SKIPPED
            SyncResult second = paymentService.syncPaymentResult(orderId, "tx-123", "SUCCESS");
            assertAll(
                    () -> assertThat(first).isEqualTo(SyncResult.SUCCESS),
                    () -> assertThat(second).isEqualTo(SyncResult.SKIPPED),
                    () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS)
            );
        }

        @Test
        @DisplayName("PG 트랜잭션 ID가 일치하지 않으면 예외가 발생한다.")
        void throwsWhenTransactionIdMismatch() {
            // given
            Long orderId = 100L;
            Payment payment = createPaymentWithId(1L, orderId, PaymentStatus.PENDING);
            // payment는 PENDING 상태에서 pgTransactionId = "tx-123"

            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

            // when & then
            assertThrows(CoreException.class,
                    () -> paymentService.syncPaymentResult(orderId, "fake-tx-id", "SUCCESS"));
        }

        @Test
        @DisplayName("REQUESTED 상태의 Payment는 PG 미도달로 판단하여 FAILED로 전환한다.")
        void marksRequestedAsFailed() {
            // given
            Long orderId = 100L;
            Payment payment = Payment.create(orderId, 1L, 50000, "SAMSUNG", "1234");
            ReflectionTestUtils.setField(payment, "id", 1L);

            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

            // when
            paymentService.markRequestedAsFailed(orderId);

            // then
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("REQUESTED가 아닌 상태의 Payment는 markRequestedAsFailed로 변경되지 않는다.")
        void doesNotMarkNonRequestedAsFailed() {
            // given
            Long orderId = 100L;
            Payment payment = createPaymentWithId(1L, orderId, PaymentStatus.PENDING);

            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

            // when
            paymentService.markRequestedAsFailed(orderId);

            // then
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        }

        @Test
        @DisplayName("Payment에 pgTransactionId가 없으면(UNKNOWN 등) 검증을 통과한다.")
        void allowsSyncWhenNoPgTransactionId() {
            // given
            Long orderId = 100L;
            Payment payment = Payment.create(orderId, 1L, 50000, "SAMSUNG", "1234");
            ReflectionTestUtils.setField(payment, "id", 1L);
            payment.markUnknown();

            when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

            // when
            SyncResult result = paymentService.syncPaymentResult(orderId, "tx-new", "SUCCESS");

            // then
            assertAll(
                    () -> assertThat(result).isEqualTo(SyncResult.SUCCESS),
                    () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS)
            );
        }
    }
}
