package com.loopers.infrastructure.payment;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PgApiClient 단위 테스트
 *
 * Resilience4j 어노테이션(@TimeLimiter, @CircuitBreaker, @Retry)은 Spring AOP 프록시를 통해 동작하므로,
 * 순수 단위 테스트에서는 어노테이션이 적용되지 않는다.
 *
 * 이 테스트에서는:
 * - fallback 메서드의 분기 로직 (TimeoutException vs 일반 예외)을 직접 호출하여 검증
 * - PgFeignClient 호출 및 callbackUrl 설정 등 PgApiClient 자체 로직을 검증
 *
 * Resilience4j의 실제 동작 (Retry 횟수, CircuitBreaker 상태 전이 등)은
 * 통합 테스트(@SpringBootTest)에서 검증해야 한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PgApiClient 단위 테스트")
class PgApiClientTest {

    @Mock
    private PgFeignClient pgFeignClient;

    private PgApiClient pgApiClient;

    private static final String CALLBACK_URL = "http://localhost:8080/api/v2/payments/callback";
    private static final Long MEMBER_ID = 1L;

    @BeforeEach
    void setUp() {
        pgApiClient = new PgApiClient(pgFeignClient, CALLBACK_URL, 2);
    }

    private PgPaymentRequest createRequest() {
        return new PgPaymentRequest("100", "SAMSUNG", "1234-5678-9012-3456", "50000", null);
    }

    @Nested
    @DisplayName("PG 결제 요청 시,")
    class RequestPayment {

        @Test
        @DisplayName("PG가 정상 응답하면 결제 접수 응답을 반환한다.")
        void returnsAcceptedResponse_whenPgSucceeds() {
            // given
            PgPaymentRequest request = createRequest();
            PgPaymentResponse pgResponse =
                    new PgPaymentResponse("20250316:TR:abc123", "100", "ACCEPTED", null);
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(pgResponse);

            // when
            CompletableFuture<PgPaymentResponse> future = pgApiClient.requestPayment(MEMBER_ID, request);
            PgPaymentResponse result = future.join();

            // then
            assertThat(result.isAccepted()).isTrue();
            assertThat(result.transactionId()).isEqualTo("20250316:TR:abc123");
        }

        @Test
        @DisplayName("callbackUrl을 포함하여 PG에 요청한다.")
        void includesCallbackUrl() {
            // given
            PgPaymentRequest request = createRequest();
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenAnswer(invocation -> {
                        PgPaymentRequest actualRequest = invocation.getArgument(1);
                        assertThat(actualRequest.callbackUrl()).isEqualTo(CALLBACK_URL);
                        return new PgPaymentResponse("tx-1", "100", "ACCEPTED", null);
                    });

            // when
            pgApiClient.requestPayment(MEMBER_ID, request).join();

            // then
            verify(pgFeignClient).requestPayment(anyString(), any(PgPaymentRequest.class));
        }
    }

    @Nested
    @DisplayName("Fallback 분기 로직 검증")
    class FallbackLogic {

        @Test
        @DisplayName("TimeoutException이 발생하면 TIMEOUT 응답을 반환한다.")
        void returnsTimeoutResponse_whenTimeoutException() throws Exception {
            // given
            TimeoutException timeoutException = new TimeoutException("TimeLimiter timeout");
            PgPaymentRequest request = createRequest();

            // when — fallback 메서드를 리플렉션으로 직접 호출
            java.lang.reflect.Method fallbackMethod = PgApiClient.class.getDeclaredMethod(
                    "requestPaymentFallback", Long.class, PgPaymentRequest.class, Throwable.class);
            fallbackMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            CompletableFuture<PgPaymentResponse> result =
                    (CompletableFuture<PgPaymentResponse>) fallbackMethod.invoke(pgApiClient, MEMBER_ID, request, timeoutException);

            // then
            PgPaymentResponse response = result.join();
            assertThat(response.isTimeout()).isTrue();
            assertThat(response.isAccepted()).isFalse();
        }

        @Test
        @DisplayName("일반 예외가 발생하면 FAILED 응답을 반환한다.")
        void returnsFailedResponse_whenGeneralException() throws Exception {
            // given
            RuntimeException exception = new RuntimeException("Connection refused");
            PgPaymentRequest request = createRequest();

            // when
            java.lang.reflect.Method fallbackMethod = PgApiClient.class.getDeclaredMethod(
                    "requestPaymentFallback", Long.class, PgPaymentRequest.class, Throwable.class);
            fallbackMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            CompletableFuture<PgPaymentResponse> result =
                    (CompletableFuture<PgPaymentResponse>) fallbackMethod.invoke(pgApiClient, MEMBER_ID, request, exception);

            // then
            PgPaymentResponse response = result.join();
            assertThat(response.isTimeout()).isFalse();
            assertThat(response.isAccepted()).isFalse();
            assertThat(response.message()).contains("Connection refused");
        }

        @Test
        @DisplayName("CircuitBreaker OPEN 예외가 발생하면 FAILED 응답을 반환한다.")
        void returnsFailedResponse_whenCircuitBreakerOpen() throws Exception {
            // given
            CircuitBreakerConfig config = CircuitBreakerConfig.custom().build();
            CircuitBreaker circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("test");
            circuitBreaker.transitionToOpenState();
            CallNotPermittedException cbException = CallNotPermittedException.createCallNotPermittedException(circuitBreaker);
            PgPaymentRequest request = createRequest();

            // when
            java.lang.reflect.Method fallbackMethod = PgApiClient.class.getDeclaredMethod(
                    "requestPaymentFallback", Long.class, PgPaymentRequest.class, Throwable.class);
            fallbackMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            CompletableFuture<PgPaymentResponse> result =
                    (CompletableFuture<PgPaymentResponse>) fallbackMethod.invoke(pgApiClient, MEMBER_ID, request, cbException);

            // then
            PgPaymentResponse response = result.join();
            assertThat(response.isTimeout()).isFalse();
            assertThat(response.isAccepted()).isFalse();
        }
    }

    @Nested
    @DisplayName("PG 상태 조회 시,")
    class GetPaymentStatus {

        @Test
        @DisplayName("PG 상태를 정상적으로 조회한다.")
        void returnsPaymentStatus() {
            // given
            PgPaymentStatusResponse pgStatus =
                    new PgPaymentStatusResponse("tx-123", "100", "SUCCESS", null);
            when(pgFeignClient.getPaymentStatus("1", "100")).thenReturn(pgStatus);

            // when
            Optional<PgPaymentStatusResponse> result = pgApiClient.getPaymentStatus(MEMBER_ID, "100");

            // then
            assertThat(result).isPresent();
            assertThat(result.get().status()).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("PG 조회 실패 시 예외를 전파하지 않고 빈 Optional을 반환한다.")
        void returnsEmpty_whenPgQueryFails() {
            // given
            when(pgFeignClient.getPaymentStatus("1", "100"))
                    .thenThrow(new RuntimeException("Connection refused"));

            // when
            Optional<PgPaymentStatusResponse> result = pgApiClient.getPaymentStatus(MEMBER_ID, "100");

            // then
            assertThat(result).isEmpty();
        }
    }
}
