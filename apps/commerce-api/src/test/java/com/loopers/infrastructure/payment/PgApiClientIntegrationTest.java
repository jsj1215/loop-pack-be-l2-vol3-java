package com.loopers.infrastructure.payment;

import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PgApiClient 통합 테스트 — Resilience4j AOP 프록시가 실제 동작하는지 검증
 *
 * 단위 테스트(PgApiClientTest)에서는 리플렉션으로 fallback 로직만 검증했다.
 * 이 테스트에서는 @SpringBootTest로 실제 Spring AOP 프록시를 통해
 * @TimeLimiter, @CircuitBreaker, @Retry 어노테이션이 올바르게 적용되었는지 확인한다.
 *
 * PgFeignClient만 Mock 처리하고, PgApiClient는 실제 빈을 사용한다.
 */
@SpringBootTest
@DisplayName("PgApiClient 통합 테스트 (Resilience4j AOP)")
class PgApiClientIntegrationTest {

    @Autowired
    private PgApiClient pgApiClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private PgFeignClient pgFeignClient;

    @BeforeEach
    void resetCircuitBreaker() {
        circuitBreakerRegistry.getAllCircuitBreakers()
                .forEach(cb -> cb.reset());
    }

    private PgPaymentRequest createRequest() {
        return new PgPaymentRequest("100", "SAMSUNG", "1234-5678-9012-3456", "50000", null);
    }

    @Nested
    @DisplayName("정상 요청 시,")
    class SuccessCase {

        @Test
        @DisplayName("PG 응답을 그대로 반환한다.")
        void returnsResponse() throws Exception {
            // given
            PgPaymentResponse pgResponse = new PgPaymentResponse("tx-123", "100", "ACCEPTED", null);
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(pgResponse);

            // when
            PgPaymentResponse result = pgApiClient.requestPayment(1L, createRequest()).get();

            // then
            assertThat(result.isAccepted()).isTrue();
            assertThat(result.transactionId()).isEqualTo("tx-123");
        }
    }

    @Nested
    @DisplayName("Retry 동작 시,")
    class RetryCase {

        @Test
        @DisplayName("RetryableException 발생 시 3회 재시도 후 fallback으로 FAILED 응답을 반환한다.")
        void retriesThreeTimesOnRetryableException() throws Exception {
            // given
            feign.Request feignRequest = feign.Request.create(
                    feign.Request.HttpMethod.POST, "http://pg/api/v1/payments",
                    java.util.Map.of(), null, null, null);
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenThrow(new RetryableException(
                            -1, "Connection refused", feign.Request.HttpMethod.POST, null, (Long) null, feignRequest));

            // when
            PgPaymentResponse result = pgApiClient.requestPayment(1L, createRequest()).get();

            // then — max-attempts=3 설정에 따라 정확히 3회 호출 후 fallback 실행
            assertThat(result.isAccepted()).isFalse();
            assertThat(result.status()).isEqualTo("FAILED");
            verify(pgFeignClient, times(3)).requestPayment(anyString(), any(PgPaymentRequest.class));
        }

        @Test
        @DisplayName("5xx 서버 에러 발생 시 3회 재시도 후 fallback으로 FAILED 응답을 반환한다.")
        void retriesThreeTimesOn5xxError() throws Exception {
            // given
            feign.Request feignRequest = feign.Request.create(
                    feign.Request.HttpMethod.POST, "http://pg/api/v1/payments",
                    java.util.Map.of(), null, null, null);
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenThrow(new FeignException.InternalServerError(
                            "Internal Server Error", feignRequest, null, null));

            // when
            PgPaymentResponse result = pgApiClient.requestPayment(1L, createRequest()).get();

            // then — 5xx는 retry-exceptions에 포함되어 3회 재시도
            assertThat(result.isAccepted()).isFalse();
            assertThat(result.status()).isEqualTo("FAILED");
            verify(pgFeignClient, times(3)).requestPayment(anyString(), any(PgPaymentRequest.class));
        }

        @Test
        @DisplayName("첫 시도 실패 후 재시도에서 성공하면 정상 응답을 반환한다.")
        void succeedsOnSecondAttempt() throws Exception {
            // given
            feign.Request feignRequest = feign.Request.create(
                    feign.Request.HttpMethod.POST, "http://pg/api/v1/payments",
                    java.util.Map.of(), null, null, null);
            PgPaymentResponse successResponse = new PgPaymentResponse("tx-retry-ok", "100", "ACCEPTED", null);

            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenThrow(new RetryableException(
                            -1, "Connection refused", feign.Request.HttpMethod.POST, null, (Long) null, feignRequest))
                    .thenReturn(successResponse);

            // when
            PgPaymentResponse result = pgApiClient.requestPayment(1L, createRequest()).get();

            // then — 1회 실패 + 2회째 성공 = 총 2회 호출
            assertThat(result.isAccepted()).isTrue();
            assertThat(result.transactionId()).isEqualTo("tx-retry-ok");
            verify(pgFeignClient, times(2)).requestPayment(anyString(), any(PgPaymentRequest.class));
        }

        @Test
        @DisplayName("4xx 에러는 재시도하지 않고 즉시 fallback을 반환한다.")
        void doesNotRetryOn4xxError() throws Exception {
            // given
            feign.Request feignRequest = feign.Request.create(
                    feign.Request.HttpMethod.POST, "http://pg/api/v1/payments",
                    java.util.Map.of(), null, null, null);
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenThrow(new FeignException.BadRequest("Bad Request", feignRequest, null, null));

            // when
            PgPaymentResponse result = pgApiClient.requestPayment(1L, createRequest()).get();

            // then — 재시도 없이 1회만 호출 후 fallback
            assertThat(result.isAccepted()).isFalse();
            verify(pgFeignClient, times(1)).requestPayment(anyString(), any(PgPaymentRequest.class));
        }
    }

    @Nested
    @DisplayName("Fallback 동작 시,")
    class FallbackCase {

        @Test
        @DisplayName("일반 예외 시 FAILED 응답을 반환한다.")
        void returnsFailed() throws Exception {
            // given
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenThrow(new RuntimeException("PG 장애"));

            // when
            PgPaymentResponse result = pgApiClient.requestPayment(1L, createRequest()).get();

            // then
            assertThat(result.isAccepted()).isFalse();
            assertThat(result.isTimeout()).isFalse();
            assertThat(result.status()).isEqualTo("FAILED");
        }
    }

    @Nested
    @DisplayName("TimeLimiter 타임아웃 동작 시,")
    class TimeLimiterTimeoutCase {

        @Test
        @DisplayName("PG 응답이 TimeLimiter(4s)를 초과하면 타임아웃하고 TIMEOUT 응답을 반환한다.")
        void timeLimiterCutsOffSlowResponse() throws Exception {
            // given — PG가 5초 후에야 응답하는 상황 (네트워크 블랙홀, 패킷 유실 시뮬레이션)
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenAnswer(invocation -> {
                        Thread.sleep(5000);
                        return new PgPaymentResponse("tx-slow", "100", "ACCEPTED", null);
                    });

            long timeoutBefore = getTimeLimiterCount("timeout");

            // when
            long start = System.currentTimeMillis();
            PgPaymentResponse result = pgApiClient.requestPayment(1L, createRequest()).get();
            long elapsed = System.currentTimeMillis() - start;

            // then — TimeLimiter가 4초 만에 차단하고 fallback으로 TIMEOUT 응답 반환
            assertThat(result.isTimeout()).as("타임아웃 시 TIMEOUT 응답이어야 한다").isTrue();
            assertThat(result.isAccepted()).as("타임아웃이므로 accepted가 아니어야 한다").isFalse();
            assertThat(elapsed).as("TimeLimiter(4s) 근처에서 응답해야 한다 (5초 미만)")
                    .isLessThan(5000);

            long timeoutAfter = getTimeLimiterCount("timeout");
            assertThat(timeoutAfter).as("TimeLimiter timeout 메트릭이 증가해야 한다")
                    .isGreaterThan(timeoutBefore);
        }

        @Test
        @DisplayName("PG 응답이 TimeLimiter(4s) 이내이면 정상 처리된다.")
        void normalResponseWithinTimeLimiter() throws Exception {
            // given — PG가 1초 후 응답 (TimeLimiter 4s 이내)
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenAnswer(invocation -> {
                        Thread.sleep(1000);
                        return new PgPaymentResponse("tx-normal", "100", "ACCEPTED", null);
                    });

            long successBefore = getTimeLimiterCount("successful");
            long timeoutBefore = getTimeLimiterCount("timeout");

            // when
            PgPaymentResponse result = pgApiClient.requestPayment(1L, createRequest()).get();

            // then — 정상 응답, 타임아웃 아님
            assertThat(result.isAccepted()).isTrue();
            assertThat(result.transactionId()).isEqualTo("tx-normal");

            long successAfter = getTimeLimiterCount("successful");
            long timeoutAfter = getTimeLimiterCount("timeout");
            assertThat(successAfter).as("TimeLimiter successful 메트릭이 증가해야 한다")
                    .isGreaterThan(successBefore);
            assertThat(timeoutAfter).as("TimeLimiter timeout 메트릭은 증가하지 않아야 한다")
                    .isEqualTo(timeoutBefore);
        }

        private long getTimeLimiterCount(String kind) {
            return meterRegistry.find("resilience4j.timelimiter.calls")
                    .tag("name", "pgTimeLimiter")
                    .tag("kind", kind)
                    .counters()
                    .stream()
                    .mapToLong(counter -> (long) counter.count())
                    .sum();
        }
    }

    @Nested
    @DisplayName("TimeLimiter 메트릭 기록 시,")
    class TimeLimiterMetricsCase {

        @Test
        @DisplayName("PG 성공 응답 시 TimeLimiter successful 메트릭이 기록된다.")
        void recordsSuccessfulMetric() throws Exception {
            // given
            PgPaymentResponse pgResponse = new PgPaymentResponse("tx-metric", "100", "ACCEPTED", null);
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(pgResponse);

            long successBefore = getTimeLimiterCount("successful");
            long failedBefore = getTimeLimiterCount("failed");

            // when
            PgPaymentResponse result = pgApiClient.requestPayment(1L, createRequest()).get();

            // then — TimeLimiter successful 메트릭이 증가해야 함
            assertThat(result.isAccepted()).isTrue();

            long successAfter = getTimeLimiterCount("successful");
            long failedAfter = getTimeLimiterCount("failed");

            assertThat(successAfter).as("TimeLimiter successful 메트릭이 증가해야 한다")
                    .isGreaterThan(successBefore);
            assertThat(failedAfter).as("TimeLimiter failed 메트릭은 증가하지 않아야 한다")
                    .isEqualTo(failedBefore);
        }

        @Test
        @DisplayName("PG 5xx 응답 시 TimeLimiter failed 메트릭이 기록된다.")
        void recordsFailedMetric() throws Exception {
            // given
            feign.Request feignRequest = feign.Request.create(
                    feign.Request.HttpMethod.POST, "http://pg/api/v1/payments",
                    java.util.Map.of(), null, null, null);
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenThrow(new FeignException.InternalServerError(
                            "Internal Server Error", feignRequest, null, null));

            long failedBefore = getTimeLimiterCount("failed");

            // when
            pgApiClient.requestPayment(1L, createRequest()).get();

            // then — TimeLimiter failed 메트릭이 증가해야 함 (Retry 3회 → failed 3 증가)
            long failedAfter = getTimeLimiterCount("failed");
            assertThat(failedAfter).as("TimeLimiter failed 메트릭이 증가해야 한다")
                    .isGreaterThan(failedBefore);
        }

        private long getTimeLimiterCount(String kind) {
            return meterRegistry.find("resilience4j.timelimiter.calls")
                    .tag("name", "pgTimeLimiter")
                    .tag("kind", kind)
                    .counters()
                    .stream()
                    .mapToLong(counter -> (long) counter.count())
                    .sum();
        }
    }
}
