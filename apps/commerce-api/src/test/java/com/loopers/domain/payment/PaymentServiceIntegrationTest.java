package com.loopers.domain.payment;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.infrastructure.payment.ManualPgApiClient;
import com.loopers.infrastructure.payment.PgFeignClient;
import com.loopers.support.error.CoreException;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [통합 테스트 - PaymentService]
 *
 * 테스트 대상: PaymentService (Domain Layer)
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: Service → Repository → Database
 *
 * PG 외부 호출 없이 PaymentService의 DB 연동 로직을 검증한다:
 *   - 결제 생성 / 재시도(resetForRetry) / 중복 방지
 *   - 콜백 동기화(syncPaymentResult) / 멱등성
 *   - PG 트랜잭션 ID 검증
 *   - REQUESTED 상태 복구(markRequestedAsFailed)
 */
@SpringBootTest
@DisplayName("PaymentService 통합 테스트")
class PaymentServiceIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @MockitoBean
    private PgFeignClient pgFeignClient;

    @MockitoBean
    private ManualPgApiClient manualPgApiClient;

    private Long memberId;
    private Long orderId;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @BeforeEach
    void setUp() {
        memberId = 1L;

        // 주문 생성 (결제 테스트에 필요한 최소 데이터 — protected 생성자 사용)
        Order order = createOrderStub(memberId, 50000);
        order = orderRepository.save(order);
        orderId = order.getId();
    }

    private Order createOrderStub(Long memberId, int totalAmount) {
        try {
            java.lang.reflect.Constructor<Order> ctor = Order.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            Order order = ctor.newInstance();
            org.springframework.test.util.ReflectionTestUtils.setField(order, "memberId", memberId);
            org.springframework.test.util.ReflectionTestUtils.setField(order, "totalAmount", totalAmount);
            org.springframework.test.util.ReflectionTestUtils.setField(order, "discountAmount", 0);
            org.springframework.test.util.ReflectionTestUtils.setField(order, "usedPoints", 0);
            org.springframework.test.util.ReflectionTestUtils.setField(order, "status", OrderStatus.PENDING);
            return order;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("결제를 시작할 때,")
    class InitiatePayment {

        @Test
        @DisplayName("주문에 대한 Payment가 REQUESTED 상태로 생성된다.")
        void createsPaymentWithRequestedStatus() {
            // when
            Payment payment = paymentService.initiatePayment(memberId, orderId, 50000, "SAMSUNG", "1234-5678-9012-3456");

            // then
            Payment found = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertAll(
                    () -> assertThat(found.getOrderId()).isEqualTo(orderId),
                    () -> assertThat(found.getMemberId()).isEqualTo(memberId),
                    () -> assertThat(found.getAmount()).isEqualTo(50000),
                    () -> assertThat(found.getStatus()).isEqualTo(PaymentStatus.REQUESTED)
            );
        }

        @Test
        @DisplayName("FAILED 상태의 기존 Payment가 있으면 재사용하여 REQUESTED로 리셋한다.")
        void reusesFailedPayment() {
            // given - 첫 결제 후 실패 처리
            Payment payment = paymentService.initiatePayment(memberId, orderId, 50000, "SAMSUNG", "1234");
            paymentService.handlePgResponse(payment.getId(), false, null, "카드 한도 초과");

            // when - 재시도
            Payment retried = paymentService.initiatePayment(memberId, orderId, 50000, "HYUNDAI", "5678");

            // then - 같은 Payment ID, 새 카드 정보, REQUESTED 상태
            assertAll(
                    () -> assertThat(retried.getId()).isEqualTo(payment.getId()),
                    () -> assertThat(retried.getCardType()).isEqualTo("HYUNDAI"),
                    () -> assertThat(retried.getCardNo()).isEqualTo("5678"),
                    () -> assertThat(retried.getStatus()).isEqualTo(PaymentStatus.REQUESTED),
                    () -> assertThat(retried.getFailureReason()).isNull()
            );
        }

        @Test
        @DisplayName("PENDING 상태의 결제가 있으면 중복 생성이 거부된다.")
        void rejectsDuplicatePayment() {
            // given
            Payment payment = paymentService.initiatePayment(memberId, orderId, 50000, "SAMSUNG", "1234");
            paymentService.handlePgResponse(payment.getId(), true, "tx-123", null);

            // when & then
            assertThrows(CoreException.class,
                    () -> paymentService.initiatePayment(memberId, orderId, 50000, "HYUNDAI", "5678"));
        }
    }

    @Nested
    @DisplayName("콜백 동기화 시,")
    class SyncPaymentResult {

        @Test
        @DisplayName("PG 성공 콜백이 오면 Payment는 SUCCESS가 되고 SyncResult.SUCCESS를 반환한다.")
        void syncsSuccessCallback() {
            // given
            Payment payment = paymentService.initiatePayment(memberId, orderId, 50000, "SAMSUNG", "1234");
            paymentService.handlePgResponse(payment.getId(), true, "tx-abc", null);

            // when
            SyncResult result = paymentService.syncPaymentResult(orderId, "tx-abc", "SUCCESS");

            // then
            Payment found = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertAll(
                    () -> assertThat(result).isEqualTo(SyncResult.SUCCESS),
                    () -> assertThat(found.getStatus()).isEqualTo(PaymentStatus.SUCCESS)
            );
        }

        @Test
        @DisplayName("PG 실패 콜백이 오면 Payment는 FAILED가 되고 SyncResult.FAILED를 반환한다.")
        void syncsFailureCallback() {
            // given
            Payment payment = paymentService.initiatePayment(memberId, orderId, 50000, "SAMSUNG", "1234");
            paymentService.handlePgResponse(payment.getId(), true, "tx-abc", null);

            // when
            SyncResult result = paymentService.syncPaymentResult(orderId, "tx-abc", "LIMIT_EXCEEDED");

            // then
            Payment found = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertAll(
                    () -> assertThat(result).isEqualTo(SyncResult.FAILED),
                    () -> assertThat(found.getStatus()).isEqualTo(PaymentStatus.FAILED)
            );
        }

        @Test
        @DisplayName("이미 SUCCESS인 결제에 중복 콜백이 와도 상태가 변경되지 않는다 (멱등성).")
        void idempotentOnDuplicateCallback() {
            // given
            Payment payment = paymentService.initiatePayment(memberId, orderId, 50000, "SAMSUNG", "1234");
            paymentService.handlePgResponse(payment.getId(), true, "tx-abc", null);
            paymentService.syncPaymentResult(orderId, "tx-abc", "SUCCESS");

            // when - 중복 콜백
            paymentService.syncPaymentResult(orderId, "tx-abc", "SUCCESS");

            // then - 상태 유지
            Payment found = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertThat(found.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        }

        @Test
        @DisplayName("PG 트랜잭션 ID가 불일치하면 예외가 발생한다.")
        void rejectsTransactionIdMismatch() {
            // given
            Payment payment = paymentService.initiatePayment(memberId, orderId, 50000, "SAMSUNG", "1234");
            paymentService.handlePgResponse(payment.getId(), true, "tx-real", null);

            // when & then
            assertThrows(CoreException.class,
                    () -> paymentService.syncPaymentResult(orderId, "tx-fake", "SUCCESS"));
        }
    }

    @Nested
    @DisplayName("REQUESTED 상태 복구 시,")
    class MarkRequestedAsFailed {

        @Test
        @DisplayName("REQUESTED 상태의 Payment를 FAILED로 전환한다.")
        void marksRequestedAsFailed() {
            // given
            paymentService.initiatePayment(memberId, orderId, 50000, "SAMSUNG", "1234");

            // when
            paymentService.markRequestedAsFailed(orderId);

            // then
            Payment found = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertThat(found.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("PENDING 상태의 Payment는 변경하지 않는다.")
        void doesNotAffectPendingPayment() {
            // given
            Payment payment = paymentService.initiatePayment(memberId, orderId, 50000, "SAMSUNG", "1234");
            paymentService.handlePgResponse(payment.getId(), true, "tx-123", null);

            // when
            paymentService.markRequestedAsFailed(orderId);

            // then
            Payment found = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertThat(found.getStatus()).isEqualTo(PaymentStatus.PENDING);
        }
    }
}
