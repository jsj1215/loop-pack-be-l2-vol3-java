package com.loopers.interfaces.api;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.member.BirthDate;
import com.loopers.domain.member.Email;
import com.loopers.domain.member.LoginId;
import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberName;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.point.Point;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductStatus;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.member.MemberJpaRepository;
import com.loopers.infrastructure.payment.PgFeignClient;
import com.loopers.infrastructure.payment.PgPaymentRequest;
import com.loopers.infrastructure.payment.PgPaymentResponse;
import com.loopers.infrastructure.payment.PgPaymentStatusResponse;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.ProductOptionJpaRepository;
import com.loopers.interfaces.api.payment.dto.PaymentV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [E2E 테스트 - Payment V2 API]
 *
 * 대상: /api/v2/payments 결제 API 전체 흐름 (Resilience4j 적용 버전)
 * 테스트 범위: HTTP 요청 → Controller → Facade → Service → Repository → Database
 * PG 외부 호출(PgFeignClient)만 Mock 처리하고, 나머지는 실제 DB로 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Payment V2 API E2E 테스트")
class PaymentV2ApiE2ETest {

    private static final String ENDPOINT_PAYMENTS = "/api/v2/payments";
    private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
    private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private MemberJpaRepository memberJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private ProductOptionJpaRepository productOptionJpaRepository;

    @Autowired
    private PointJpaRepository pointJpaRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private PgFeignClient pgFeignClient;

    private Member member;
    private Long orderId;

    @BeforeEach
    void setUp() {
        member = saveMember();

        // 주문 생성 (결제 테스트에 필요한 최소 데이터 — protected 생성자 사용)
        Order order = createOrderStub(member.getId(), 50000);
        order = orderRepository.save(order);
        orderId = order.getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
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

    private Member saveMember() {
        Member member = new Member(
                new LoginId("testuser1"),
                passwordEncoder.encode("Password1!"),
                new MemberName("홍길동"),
                new Email("test@example.com"),
                new BirthDate("19900101"));
        return memberJpaRepository.save(member);
    }

    private HttpHeaders memberAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_LOGIN_ID, "testuser1");
        headers.set(HEADER_LOGIN_PW, "Password1!");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders noAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Nested
    @DisplayName("POST /api/v2/payments")
    class CreatePayment {

        @Test
        @DisplayName("PG 접수 성공 시 200 OK + PENDING 상태를 반환한다.")
        void returnsOkWithPending_whenPgAccepted() {
            // given
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(new PgPaymentResponse("tx-abc123", String.valueOf(orderId), "ACCEPTED", null));

            PaymentV1Dto.CreatePaymentRequest request =
                    new PaymentV1Dto.CreatePaymentRequest(orderId, "SAMSUNG", "1234-5678-9012-3456");

            // when
            ResponseEntity<ApiResponse<PaymentV1Dto.PaymentResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, memberAuthHeaders()),
                    new ParameterizedTypeReference<>() {});

            // then
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS),
                    () -> assertThat(response.getBody().data().status()).isEqualTo(PaymentStatus.PENDING),
                    () -> assertThat(response.getBody().data().orderId()).isEqualTo(orderId),
                    () -> assertThat(response.getBody().data().pgTransactionId()).isEqualTo("tx-abc123")
            );

            // DB 검증
            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        }

        @Test
        @DisplayName("PG 접수 실패 시 200 OK + FAILED 상태를 반환한다.")
        void returnsOkWithFailed_whenPgRejected() {
            // given
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(PgPaymentResponse.failed("카드 한도 초과"));

            PaymentV1Dto.CreatePaymentRequest request =
                    new PaymentV1Dto.CreatePaymentRequest(orderId, "SAMSUNG", "1234-5678-9012-3456");

            // when
            ResponseEntity<ApiResponse<PaymentV1Dto.PaymentResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, memberAuthHeaders()),
                    new ParameterizedTypeReference<>() {});

            // then
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody().data().status()).isEqualTo(PaymentStatus.FAILED),
                    () -> assertThat(response.getBody().data().failureReason()).isEqualTo("카드 한도 초과")
            );

            // DB 검증
            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("PG 타임아웃 시 200 OK + UNKNOWN 상태를 반환한다.")
        void returnsOkWithUnknown_whenPgTimeout() {
            // given
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(PgPaymentResponse.timeout());

            PaymentV1Dto.CreatePaymentRequest request =
                    new PaymentV1Dto.CreatePaymentRequest(orderId, "SAMSUNG", "1234-5678-9012-3456");

            // when
            ResponseEntity<ApiResponse<PaymentV1Dto.PaymentResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, memberAuthHeaders()),
                    new ParameterizedTypeReference<>() {});

            // then
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody().data().status()).isEqualTo(PaymentStatus.UNKNOWN)
            );

            // DB 검증
            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        }

        @Test
        @DisplayName("PG 호출 중 예외 발생 시에도 200 OK + FAILED 상태를 반환한다. (2단계 Fallback)")
        void returnsOkWithFailed_whenPgThrowsException() {
            // given
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenThrow(new RuntimeException("PG 서버 연결 불가"));

            PaymentV1Dto.CreatePaymentRequest request =
                    new PaymentV1Dto.CreatePaymentRequest(orderId, "SAMSUNG", "1234-5678-9012-3456");

            // when
            ResponseEntity<ApiResponse<PaymentV1Dto.PaymentResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, memberAuthHeaders()),
                    new ParameterizedTypeReference<>() {});

            // then
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody().data().status()).isEqualTo(PaymentStatus.FAILED)
            );
        }

        @Test
        @DisplayName("인증 없이 요청하면 401 UNAUTHORIZED를 반환한다.")
        void returnsUnauthorized_whenNoAuth() {
            // given
            PaymentV1Dto.CreatePaymentRequest request =
                    new PaymentV1Dto.CreatePaymentRequest(orderId, "SAMSUNG", "1234-5678-9012-3456");

            // when
            ResponseEntity<ApiResponse<PaymentV1Dto.PaymentResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, noAuthHeaders()),
                    new ParameterizedTypeReference<>() {});

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("POST /api/v2/payments/callback")
    class HandleCallback {

        @Test
        @DisplayName("PG 성공 콜백 수신 시 Payment는 SUCCESS, Order는 PAID가 된다.")
        void syncsSuccessCallback() {
            // given — 결제 요청 선행 (PENDING 상태 만들기)
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(new PgPaymentResponse("tx-callback-test", String.valueOf(orderId), "ACCEPTED", null));

            PaymentV1Dto.CreatePaymentRequest paymentRequest =
                    new PaymentV1Dto.CreatePaymentRequest(orderId, "SAMSUNG", "1234-5678-9012-3456");
            testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS,
                    HttpMethod.POST,
                    new HttpEntity<>(paymentRequest, memberAuthHeaders()),
                    new ParameterizedTypeReference<ApiResponse<PaymentV1Dto.PaymentResponse>>() {});

            // when — PG 성공 콜백 전송
            PaymentV1Dto.PgCallbackRequest callbackRequest =
                    new PaymentV1Dto.PgCallbackRequest("tx-callback-test", String.valueOf(orderId), "SUCCESS", null);

            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS + "/callback",
                    HttpMethod.POST,
                    new HttpEntity<>(callbackRequest, noAuthHeaders()),
                    new ParameterizedTypeReference<>() {});

            // then
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS)
            );

            // DB 검증
            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            Order order = orderRepository.findById(orderId).orElseThrow();
            assertAll(
                    () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS),
                    () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID)
            );
        }

        @Test
        @DisplayName("PG 실패 콜백 수신 시 Payment는 FAILED, Order는 FAILED가 된다.")
        void syncsFailureCallback() {
            // given — 결제 요청 선행
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(new PgPaymentResponse("tx-fail-test", String.valueOf(orderId), "ACCEPTED", null));

            PaymentV1Dto.CreatePaymentRequest paymentRequest =
                    new PaymentV1Dto.CreatePaymentRequest(orderId, "SAMSUNG", "1234-5678-9012-3456");
            testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS,
                    HttpMethod.POST,
                    new HttpEntity<>(paymentRequest, memberAuthHeaders()),
                    new ParameterizedTypeReference<ApiResponse<PaymentV1Dto.PaymentResponse>>() {});

            // when — PG 실패 콜백 전송
            PaymentV1Dto.PgCallbackRequest callbackRequest =
                    new PaymentV1Dto.PgCallbackRequest("tx-fail-test", String.valueOf(orderId), "LIMIT_EXCEEDED", "카드 한도 초과");

            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS + "/callback",
                    HttpMethod.POST,
                    new HttpEntity<>(callbackRequest, noAuthHeaders()),
                    new ParameterizedTypeReference<>() {});

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            // DB 검증
            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            Order order = orderRepository.findById(orderId).orElseThrow();
            assertAll(
                    () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED),
                    () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED)
            );
        }

        @Test
        @DisplayName("중복 콜백이 와도 멱등하게 처리된다.")
        void handlesIdempotentCallback() {
            // given — 결제 요청 선행
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(new PgPaymentResponse("tx-idempotent", String.valueOf(orderId), "ACCEPTED", null));

            PaymentV1Dto.CreatePaymentRequest paymentRequest =
                    new PaymentV1Dto.CreatePaymentRequest(orderId, "SAMSUNG", "1234-5678-9012-3456");
            testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS,
                    HttpMethod.POST,
                    new HttpEntity<>(paymentRequest, memberAuthHeaders()),
                    new ParameterizedTypeReference<ApiResponse<PaymentV1Dto.PaymentResponse>>() {});

            PaymentV1Dto.PgCallbackRequest callbackRequest =
                    new PaymentV1Dto.PgCallbackRequest("tx-idempotent", String.valueOf(orderId), "SUCCESS", null);

            // when — 첫 번째 콜백
            testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS + "/callback",
                    HttpMethod.POST,
                    new HttpEntity<>(callbackRequest, noAuthHeaders()),
                    new ParameterizedTypeReference<ApiResponse<Object>>() {});

            // when — 두 번째 중복 콜백
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS + "/callback",
                    HttpMethod.POST,
                    new HttpEntity<>(callbackRequest, noAuthHeaders()),
                    new ParameterizedTypeReference<>() {});

            // then — 여전히 SUCCESS
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        }

        @Test
        @DisplayName("orderId가 숫자가 아니면 에러 응답을 반환한다.")
        void returnsFail_whenOrderIdInvalid() {
            // given
            PaymentV1Dto.PgCallbackRequest callbackRequest =
                    new PaymentV1Dto.PgCallbackRequest("tx-123", "invalid-id", "SUCCESS", null);

            // when
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS + "/callback",
                    HttpMethod.POST,
                    new HttpEntity<>(callbackRequest, noAuthHeaders()),
                    new ParameterizedTypeReference<>() {});

            // then
            assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL);
        }
    }

    @Nested
    @DisplayName("GET /api/v2/payments/verify")
    class VerifyPayment {

        @Test
        @DisplayName("PG 조회 결과가 SUCCESS이면 결제 상태를 동기화하고 SUCCESS를 반환한다.")
        void syncsAndReturnsSuccess_whenPgCompleted() {
            // given — 결제 요청 선행 (PENDING 상태 만들기)
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(new PgPaymentResponse("tx-verify-test", String.valueOf(orderId), "ACCEPTED", null));

            PaymentV1Dto.CreatePaymentRequest paymentRequest =
                    new PaymentV1Dto.CreatePaymentRequest(orderId, "SAMSUNG", "1234-5678-9012-3456");
            testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS,
                    HttpMethod.POST,
                    new HttpEntity<>(paymentRequest, memberAuthHeaders()),
                    new ParameterizedTypeReference<ApiResponse<PaymentV1Dto.PaymentResponse>>() {});

            // PG 상태 조회 Mock
            when(pgFeignClient.getPaymentStatus(anyString(), anyString()))
                    .thenReturn(new PgPaymentStatusResponse("tx-verify-test", String.valueOf(orderId), "SUCCESS", null));

            // when
            ResponseEntity<ApiResponse<PaymentV1Dto.PaymentResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS + "/verify?orderId=" + orderId,
                    HttpMethod.GET,
                    new HttpEntity<>(memberAuthHeaders()),
                    new ParameterizedTypeReference<>() {});

            // then
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody().data().status()).isEqualTo(PaymentStatus.SUCCESS)
            );

            // DB 검증
            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            Order order = orderRepository.findById(orderId).orElseThrow();
            assertAll(
                    () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS),
                    () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID)
            );
        }

        @Test
        @DisplayName("PG 조회 실패 시 REQUESTED 상태의 결제를 FAILED로 전환한다.")
        void marksRequestedAsFailed_whenPgQueryFails() {
            // given — REQUESTED 상태의 Payment를 직접 생성 (PG 호출 전 시스템이 중단된 상황 재현)
            Payment requestedPayment = Payment.create(orderId, member.getId(), 50000, "SAMSUNG", "1234-5678-9012-3456");
            paymentRepository.save(requestedPayment);

            // PG 조회 실패 Mock
            when(pgFeignClient.getPaymentStatus(anyString(), anyString()))
                    .thenThrow(new RuntimeException("PG 서버 연결 불가"));

            // when
            ResponseEntity<ApiResponse<PaymentV1Dto.PaymentResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS + "/verify?orderId=" + orderId,
                    HttpMethod.GET,
                    new HttpEntity<>(memberAuthHeaders()),
                    new ParameterizedTypeReference<>() {});

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            // DB 검증 — REQUESTED → FAILED 전환 확인
            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertAll(
                    () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED),
                    () -> assertThat(payment.getFailureReason()).contains("PG 요청 미도달")
            );
        }

        @Test
        @DisplayName("인증 없이 요청하면 401 UNAUTHORIZED를 반환한다.")
        void returnsUnauthorized_whenNoAuth() {
            // when
            ResponseEntity<ApiResponse<PaymentV1Dto.PaymentResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PAYMENTS + "/verify?orderId=" + orderId,
                    HttpMethod.GET,
                    new HttpEntity<>(noAuthHeaders()),
                    new ParameterizedTypeReference<>() {});

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
