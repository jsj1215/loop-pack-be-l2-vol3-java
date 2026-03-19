package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Payment 도메인 모델 단위 테스트")
class PaymentTest {

    @Nested
    @DisplayName("결제를 생성할 때,")
    class Create {

        @Test
        @DisplayName("유효한 정보면 REQUESTED 상태로 생성된다.")
        void createsWithRequestedStatus() {
            // given
            Long orderId = 1L;
            Long memberId = 1L;
            int amount = 50000;
            String cardType = "SAMSUNG";
            String cardNo = "1234-5678-9012-3456";

            // when
            Payment payment = Payment.create(orderId, memberId, amount, cardType, cardNo);

            // then
            assertAll(
                    () -> assertThat(payment.getOrderId()).isEqualTo(orderId),
                    () -> assertThat(payment.getMemberId()).isEqualTo(memberId),
                    () -> assertThat(payment.getAmount()).isEqualTo(amount),
                    () -> assertThat(payment.getCardType()).isEqualTo(cardType),
                    () -> assertThat(payment.getCardNo()).isEqualTo(cardNo),
                    () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED)
            );
        }

        @Test
        @DisplayName("주문 ID가 null이면 예외가 발생한다.")
        void throwsWhenOrderIdIsNull() {
            // when & then
            assertThrows(CoreException.class,
                    () -> Payment.create(null, 1L, 50000, "SAMSUNG", "1234"));
        }

        @Test
        @DisplayName("회원 ID가 null이면 예외가 발생한다.")
        void throwsWhenMemberIdIsNull() {
            // when & then
            assertThrows(CoreException.class,
                    () -> Payment.create(1L, null, 50000, "SAMSUNG", "1234"));
        }

        @Test
        @DisplayName("결제 금액이 0 이하이면 예외가 발생한다.")
        void throwsWhenAmountIsZeroOrNegative() {
            // when & then
            assertThrows(CoreException.class,
                    () -> Payment.create(1L, 1L, 0, "SAMSUNG", "1234"));
        }

        @Test
        @DisplayName("카드 종류가 null이거나 빈 문자열이면 예외가 발생한다.")
        void throwsWhenCardTypeIsBlank() {
            // when & then
            assertThrows(CoreException.class,
                    () -> Payment.create(1L, 1L, 50000, null, "1234"));
            assertThrows(CoreException.class,
                    () -> Payment.create(1L, 1L, 50000, "  ", "1234"));
        }

        @Test
        @DisplayName("카드 번호가 null이거나 빈 문자열이면 예외가 발생한다.")
        void throwsWhenCardNoIsBlank() {
            // when & then
            assertThrows(CoreException.class,
                    () -> Payment.create(1L, 1L, 50000, "SAMSUNG", null));
            assertThrows(CoreException.class,
                    () -> Payment.create(1L, 1L, 50000, "SAMSUNG", "  "));
        }
    }

    @Nested
    @DisplayName("상태를 전이할 때,")
    class StatusTransition {

        @Test
        @DisplayName("REQUESTED → PENDING 전이 시 pgTransactionId가 저장된다.")
        void transitionToPending() {
            // given
            Payment payment = Payment.create(1L, 1L, 50000, "SAMSUNG", "1234");

            // when
            payment.markPending("20250316:TR:abc123");

            // then
            assertAll(
                    () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING),
                    () -> assertThat(payment.getPgTransactionId()).isEqualTo("20250316:TR:abc123")
            );
        }

        @Test
        @DisplayName("PENDING → SUCCESS 전이 시 pgTransactionId가 저장된다.")
        void transitionToSuccess() {
            // given
            Payment payment = Payment.create(1L, 1L, 50000, "SAMSUNG", "1234");
            payment.markPending("20250316:TR:abc123");

            // when
            payment.markSuccess("20250316:TR:abc123");

            // then
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        }

        @Test
        @DisplayName("PENDING → FAILED 전이 시 실패 사유가 저장된다.")
        void transitionToFailed() {
            // given
            Payment payment = Payment.create(1L, 1L, 50000, "SAMSUNG", "1234");
            payment.markPending("20250316:TR:abc123");

            // when
            payment.markFailed("LIMIT_EXCEEDED");

            // then
            assertAll(
                    () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED),
                    () -> assertThat(payment.getFailureReason()).isEqualTo("LIMIT_EXCEEDED")
            );
        }

        @Test
        @DisplayName("REQUESTED → UNKNOWN 전이가 가능하다.")
        void transitionToUnknown() {
            // given
            Payment payment = Payment.create(1L, 1L, 50000, "SAMSUNG", "1234");

            // when
            payment.markUnknown();

            // then
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        }

        @Test
        @DisplayName("SUCCESS 상태에서 상태 전이를 시도하면 예외가 발생한다.")
        void throwsWhenAlreadySuccess() {
            // given
            Payment payment = Payment.create(1L, 1L, 50000, "SAMSUNG", "1234");
            payment.markSuccess("20250316:TR:abc123");

            // when & then
            assertThrows(CoreException.class, () -> payment.markFailed("LIMIT_EXCEEDED"));
        }

        @Test
        @DisplayName("FAILED 상태에서 상태 전이를 시도하면 예외가 발생한다.")
        void throwsWhenAlreadyFailed() {
            // given
            Payment payment = Payment.create(1L, 1L, 50000, "SAMSUNG", "1234");
            payment.markFailed("LIMIT_EXCEEDED");

            // when & then
            assertThrows(CoreException.class, () -> payment.markSuccess("20250316:TR:abc123"));
        }
    }

    @Nested
    @DisplayName("결제를 재시도할 때,")
    class ResetForRetry {

        @Test
        @DisplayName("FAILED 상태면 REQUESTED로 리셋되고 카드 정보가 갱신된다.")
        void resetsFailedPayment() {
            // given
            Payment payment = Payment.create(1L, 1L, 50000, "SAMSUNG", "1234");
            payment.markFailed("LIMIT_EXCEEDED");

            // when
            payment.resetForRetry(30000, "HYUNDAI", "5678");

            // then
            assertAll(
                    () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED),
                    () -> assertThat(payment.getAmount()).isEqualTo(30000),
                    () -> assertThat(payment.getCardType()).isEqualTo("HYUNDAI"),
                    () -> assertThat(payment.getCardNo()).isEqualTo("5678"),
                    () -> assertThat(payment.getPgTransactionId()).isNull(),
                    () -> assertThat(payment.getFailureReason()).isNull()
            );
        }

        @Test
        @DisplayName("FAILED가 아닌 상태에서 재시도하면 예외가 발생한다.")
        void throwsWhenNotFailed() {
            // given
            Payment payment = Payment.create(1L, 1L, 50000, "SAMSUNG", "1234");
            payment.markPending("tx-123");

            // when & then
            assertThrows(CoreException.class,
                    () -> payment.resetForRetry(30000, "HYUNDAI", "5678"));
        }
    }

    @Nested
    @DisplayName("상태 확인 메서드")
    class StatusCheck {

        @Test
        @DisplayName("SUCCESS 또는 FAILED이면 isFinalized가 true다.")
        void isFinalizedWhenSuccessOrFailed() {
            // given
            Payment successPayment = Payment.create(1L, 1L, 50000, "SAMSUNG", "1234");
            successPayment.markSuccess("tx1");

            Payment failedPayment = Payment.create(2L, 1L, 30000, "SAMSUNG", "1234");
            failedPayment.markFailed("LIMIT_EXCEEDED");

            // then
            assertAll(
                    () -> assertThat(successPayment.isFinalized()).isTrue(),
                    () -> assertThat(failedPayment.isFinalized()).isTrue()
            );
        }

        @Test
        @DisplayName("REQUESTED, PENDING, UNKNOWN이면 needsVerification이 true다.")
        void needsVerificationWhenRequestedOrPendingOrUnknown() {
            // given
            Payment requestedPayment = Payment.create(1L, 1L, 50000, "SAMSUNG", "1234");

            Payment pendingPayment = Payment.create(2L, 1L, 50000, "SAMSUNG", "1234");
            pendingPayment.markPending("tx1");

            Payment unknownPayment = Payment.create(3L, 1L, 30000, "SAMSUNG", "1234");
            unknownPayment.markUnknown();

            // then
            assertAll(
                    () -> assertThat(requestedPayment.needsVerification()).isTrue(),
                    () -> assertThat(pendingPayment.needsVerification()).isTrue(),
                    () -> assertThat(unknownPayment.needsVerification()).isTrue()
            );
        }
    }
}
