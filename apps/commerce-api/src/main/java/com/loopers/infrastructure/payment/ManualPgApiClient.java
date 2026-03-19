package com.loopers.infrastructure.payment;

import feign.FeignException;
import feign.RetryableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.util.Optional;

@Slf4j
@Component
public class ManualPgApiClient {

    private static final int MAX_RETRY_COUNT = 2;
    private static final long RETRY_DELAY_MS = 500;

    private final PgFeignClient pgFeignClient;
    private final String callbackUrl;
    private final ManualCircuitBreaker circuitBreaker;

    @Autowired
    public ManualPgApiClient(
            PgFeignClient pgFeignClient,
            @Value("${pg.callback-url.v1}") String callbackUrl) {
        this.pgFeignClient = pgFeignClient;
        this.callbackUrl = callbackUrl;
        this.circuitBreaker = new ManualCircuitBreaker(
                10,    // slidingWindowSize
                50.0,  // failureRateThreshold (%)
                10000, // waitDurationInOpenStateMs (10초)
                2      // permittedCallsInHalfOpen
        );
    }

    ManualPgApiClient(PgFeignClient pgFeignClient, String callbackUrl,
                      ManualCircuitBreaker circuitBreaker) {
        this.pgFeignClient = pgFeignClient;
        this.callbackUrl = callbackUrl;
        this.circuitBreaker = circuitBreaker;
    }

    public PgPaymentResponse requestPayment(Long memberId, PgPaymentRequest request) {
        // 서킷브레이커: OPEN 상태면 PG 호출 없이 즉시 fallback
        if (!circuitBreaker.isCallPermitted()) {
            log.warn("서킷 브레이커 OPEN — PG 호출 차단 memberId={}, orderId={}",
                    memberId, request.orderId());
            return PgPaymentResponse.failed("서킷 브레이커 OPEN: PG 호출 차단");
        }

        PgPaymentRequest requestWithCallback = new PgPaymentRequest(
                request.orderId(),
                request.cardType(),
                request.cardNo(),
                request.amount(),
                callbackUrl);

        PgPaymentResponse response = executeWithRetry(memberId, requestWithCallback);

        // 서킷브레이커: 결과 기록
        if (response.isAccepted()) {
            circuitBreaker.recordSuccess();
        } else if (!response.isTimeout()) {
            // 타임아웃은 PG 도달 여부가 불확실하므로 서킷브레이커에 실패로 기록하지 않음
            circuitBreaker.recordFailure();
        }

        return response;
    }

    private PgPaymentResponse executeWithRetry(Long memberId, PgPaymentRequest request) {
        PgPaymentResponse lastResponse = null;
        int attempt = 0;

        while (attempt <= MAX_RETRY_COUNT) {
            attempt++;

            try {
                return pgFeignClient.requestPayment(String.valueOf(memberId), request);

            } catch (RetryableException e) {
                if (e.getCause() instanceof SocketTimeoutException) {
                    log.warn("PG 응답 타임아웃 (리트라이 안 함) memberId={}, orderId={}, attempt={}",
                            memberId, request.orderId(), attempt);
                    return PgPaymentResponse.timeout();
                }

                log.warn("PG 연결 실패 memberId={}, orderId={}, attempt={}/{}",
                        memberId, request.orderId(), attempt, MAX_RETRY_COUNT + 1);
                lastResponse = PgPaymentResponse.failed("PG 연결 실패: " + e.getMessage());

            } catch (FeignException e) {
                if (e.status() >= 500) {
                    log.warn("PG 서버 에러 memberId={}, orderId={}, status={}, attempt={}/{}",
                            memberId, request.orderId(), e.status(), attempt, MAX_RETRY_COUNT + 1);
                    lastResponse = PgPaymentResponse.failed("PG 서버 에러: " + e.status());
                } else {
                    log.warn("PG 요청 에러 (리트라이 안 함) memberId={}, orderId={}, status={}",
                            memberId, request.orderId(), e.status());
                    return PgPaymentResponse.failed("PG 요청 에러: " + e.status());
                }
            }

            if (attempt <= MAX_RETRY_COUNT) {
                sleep(RETRY_DELAY_MS);
            }
        }

        log.warn("PG 결제 요청 최종 실패 memberId={}, orderId={}, 총 시도={}",
                memberId, request.orderId(), MAX_RETRY_COUNT + 1);
        return lastResponse;
    }

    public Optional<PgPaymentStatusResponse> getPaymentStatus(Long memberId, String orderId) {
        try {
            return Optional.ofNullable(pgFeignClient.getPaymentStatus(String.valueOf(memberId), orderId));
        } catch (Exception e) {
            log.warn("PG 상태 조회 실패 memberId={}, orderId={}", memberId, orderId, e);
            return Optional.empty();
        }
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public ManualCircuitBreaker.State getCircuitBreakerState() {
        return circuitBreaker.getState();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
