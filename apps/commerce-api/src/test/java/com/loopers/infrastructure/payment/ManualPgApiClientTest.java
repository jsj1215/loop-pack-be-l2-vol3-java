package com.loopers.infrastructure.payment;

import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ManualPgApiClient 단위 테스트 (수동 Retry + CircuitBreaker)")
class ManualPgApiClientTest {

    @Mock
    private PgFeignClient pgFeignClient;

    private ManualPgApiClient manualPgApiClient;
    private ManualCircuitBreaker circuitBreaker;

    private static final String CALLBACK_URL = "http://localhost:8080/api/v1/payments/callback";
    private static final Long MEMBER_ID = 1L;

    @BeforeEach
    void setUp() {
        circuitBreaker = new ManualCircuitBreaker(10, 50.0, 10000, 2);
        manualPgApiClient = new ManualPgApiClient(pgFeignClient, CALLBACK_URL, circuitBreaker);
    }

    private PgPaymentRequest createRequest() {
        return new PgPaymentRequest("100", "SAMSUNG", "1234-5678-9012-3456", "50000", null);
    }

    private RetryableException createRetryableException(Throwable cause) {
        return new RetryableException(
                -1, "Connection refused", Request.HttpMethod.POST, cause, (Date) null,
                Request.create(Request.HttpMethod.POST, "/api/v1/payments",
                        Collections.emptyMap(), null, null, null));
    }

    private FeignException createFeignException(int status) {
        return FeignException.errorStatus("requestPayment",
                feign.Response.builder()
                        .status(status)
                        .reason("Error")
                        .request(Request.create(Request.HttpMethod.POST, "/api/v1/payments",
                                Collections.emptyMap(), null, null, null))
                        .headers(Collections.emptyMap())
                        .build());
    }

    @Nested
    @DisplayName("PG 결제 요청 시,")
    class RequestPayment {

        @Test
        @DisplayName("PG가 정상 응답하면 결제 접수 응답을 반환한다.")
        void returnsAcceptedResponse() {
            // given
            PgPaymentRequest request = createRequest();
            PgPaymentResponse pgResponse =
                    new PgPaymentResponse("20250316:TR:abc123", "100", "ACCEPTED", null);
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(pgResponse);

            // when
            PgPaymentResponse result = manualPgApiClient.requestPayment(MEMBER_ID, request);

            // then
            assertThat(result.isAccepted()).isTrue();
            assertThat(result.transactionId()).isEqualTo("20250316:TR:abc123");
            verify(pgFeignClient, times(1)).requestPayment(anyString(), any());
        }
    }

    @Nested
    @DisplayName("리트라이 동작 시,")
    class RetryBehavior {

        @Test
        @DisplayName("연결 실패(RetryableException) 시 최대 3회 재시도한다.")
        void retriesOnConnectionFailure() {
            // given
            PgPaymentRequest request = createRequest();
            RetryableException retryableException = createRetryableException(new Exception("Connection refused"));

            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenThrow(retryableException);

            // when
            PgPaymentResponse result = manualPgApiClient.requestPayment(MEMBER_ID, request);

            // then
            assertThat(result.isAccepted()).isFalse();
            assertThat(result.isTimeout()).isFalse();
            verify(pgFeignClient, times(3)).requestPayment(anyString(), any());
        }

        @Test
        @DisplayName("SocketTimeoutException(읽기 타임아웃) 시 재시도하지 않고 TIMEOUT을 반환한다.")
        void doesNotRetryOnReadTimeout() {
            // given
            PgPaymentRequest request = createRequest();
            RetryableException timeoutException =
                    createRetryableException(new SocketTimeoutException("Read timed out"));

            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenThrow(timeoutException);

            // when
            PgPaymentResponse result = manualPgApiClient.requestPayment(MEMBER_ID, request);

            // then
            assertThat(result.isTimeout()).isTrue();
            verify(pgFeignClient, times(1)).requestPayment(anyString(), any());
        }

        @Test
        @DisplayName("5xx 서버 에러 시 최대 3회 재시도한다.")
        void retriesOnServerError() {
            // given
            PgPaymentRequest request = createRequest();
            FeignException serverError = createFeignException(500);

            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenThrow(serverError);

            // when
            PgPaymentResponse result = manualPgApiClient.requestPayment(MEMBER_ID, request);

            // then
            assertThat(result.isAccepted()).isFalse();
            verify(pgFeignClient, times(3)).requestPayment(anyString(), any());
        }

        @Test
        @DisplayName("4xx 클라이언트 에러 시 재시도하지 않고 즉시 FAILED를 반환한다.")
        void doesNotRetryOnClientError() {
            // given
            PgPaymentRequest request = createRequest();
            FeignException clientError = createFeignException(400);

            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenThrow(clientError);

            // when
            PgPaymentResponse result = manualPgApiClient.requestPayment(MEMBER_ID, request);

            // then
            assertThat(result.isAccepted()).isFalse();
            assertThat(result.message()).contains("400");
            verify(pgFeignClient, times(1)).requestPayment(anyString(), any());
        }

        @Test
        @DisplayName("첫 시도 실패 후 재시도에서 성공하면 접수 응답을 반환한다.")
        void succeedsOnRetry() {
            // given
            PgPaymentRequest request = createRequest();
            RetryableException retryableException = createRetryableException(new Exception("Connection refused"));
            PgPaymentResponse successResponse =
                    new PgPaymentResponse("tx-123", "100", "ACCEPTED", null);

            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenThrow(retryableException)
                    .thenReturn(successResponse);

            // when
            PgPaymentResponse result = manualPgApiClient.requestPayment(MEMBER_ID, request);

            // then
            assertThat(result.isAccepted()).isTrue();
            verify(pgFeignClient, times(2)).requestPayment(anyString(), any());
        }
    }

    @Nested
    @DisplayName("서킷브레이커 연동 시,")
    class CircuitBreakerIntegration {

        @Test
        @DisplayName("서킷이 OPEN이면 PG 호출 없이 즉시 FAILED를 반환한다.")
        void returnsFailedWhenCircuitOpen() {
            // given - 서킷을 OPEN으로 만듦
            PgPaymentRequest request = createRequest();
            FeignException serverError = createFeignException(500);
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenThrow(serverError);

            for (int i = 0; i < 10; i++) {
                manualPgApiClient.requestPayment(MEMBER_ID, request);
            }
            assertThat(circuitBreaker.getState()).isEqualTo(ManualCircuitBreaker.State.OPEN);

            // Feign 호출 카운트 초기화 (30회 = 10 × 3 리트라이)
            int previousCalls = 30;

            // when - OPEN 상태에서 호출
            PgPaymentResponse result = manualPgApiClient.requestPayment(MEMBER_ID, request);

            // then
            assertThat(result.isAccepted()).isFalse();
            assertThat(result.message()).contains("서킷 브레이커 OPEN");
            verify(pgFeignClient, times(previousCalls)).requestPayment(anyString(), any());
        }

        @Test
        @DisplayName("성공 응답은 서킷브레이커에 성공으로 기록된다.")
        void recordsSuccessToCircuitBreaker() {
            // given
            PgPaymentRequest request = createRequest();
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(new PgPaymentResponse("tx-1", "100", "ACCEPTED", null));

            // when
            manualPgApiClient.requestPayment(MEMBER_ID, request);

            // then
            assertThat(circuitBreaker.getState()).isEqualTo(ManualCircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("타임아웃 응답은 서킷브레이커에 실패로 기록하지 않는다.")
        void doesNotRecordTimeoutAsFailure() {
            // given - 9번 실패 후 1번 타임아웃 (타임아웃이 실패로 기록되면 OPEN으로 전이)
            PgPaymentRequest request = createRequest();
            FeignException serverError = createFeignException(500);
            RetryableException timeoutException =
                    createRetryableException(new SocketTimeoutException("Read timed out"));

            // 4번 실패 (4xx로 리트라이 없이)
            FeignException clientError = createFeignException(400);
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenThrow(clientError);
            for (int i = 0; i < 4; i++) {
                manualPgApiClient.requestPayment(MEMBER_ID, request);
            }

            // 5번 성공
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(new PgPaymentResponse("tx-1", "100", "ACCEPTED", null));
            for (int i = 0; i < 5; i++) {
                manualPgApiClient.requestPayment(MEMBER_ID, request);
            }

            // 윈도우: 5성공 + 4실패 = 9건 (실패율 44%, 임계치 50% 미만)
            // 1번 타임아웃 → 실패로 기록 안 하면 CLOSED 유지
            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenThrow(timeoutException);
            manualPgApiClient.requestPayment(MEMBER_ID, request);

            // then - 타임아웃이 실패로 기록되지 않았으므로 CLOSED 유지
            assertThat(circuitBreaker.getState()).isEqualTo(ManualCircuitBreaker.State.CLOSED);
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
            Optional<PgPaymentStatusResponse> result = manualPgApiClient.getPaymentStatus(MEMBER_ID, "100");

            // then
            assertThat(result).isPresent();
            assertThat(result.get().status()).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("PG 조회 실패 시 예외를 전파하지 않고 빈 Optional을 반환한다.")
        void returnsEmptyOnFailure() {
            // given
            when(pgFeignClient.getPaymentStatus("1", "100"))
                    .thenThrow(new RuntimeException("Connection refused"));

            // when
            Optional<PgPaymentStatusResponse> result = manualPgApiClient.getPaymentStatus(MEMBER_ID, "100");

            // then
            assertThat(result).isEmpty();
        }
    }
}
