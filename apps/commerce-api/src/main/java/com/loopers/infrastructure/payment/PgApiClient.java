package com.loopers.infrastructure.payment;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * Resilience4j 어노테이션 기반 PG API 클라이언트 (V2에서 사용)
 *
 * Resilience4j란:
 *   마이크로서비스 환경에서 외부 시스템 장애가 우리 시스템으로 전파(Cascading Failure)되는 것을
 *   방지하기 위한 경량 장애 허용(Fault Tolerance) 라이브러리이다.
 *   어노테이션 선언만으로 타임아웃, 서킷브레이커, 리트라이 패턴을 적용할 수 있다.
 *
 * V1(ManualPgApiClient)과의 차이:
 *   - V1: 서킷브레이커와 리트라이 로직을 직접 구현 (ManualCircuitBreaker, while 루프)
 *   - V2: Resilience4j @TimeLimiter/@CircuitBreaker/@Retry 어노테이션으로 선언적 적용
 *   → 동일한 장애 대응 전략을 훨씬 적은 코드로 구현하고, 설정 파일(application.yml)로 튜닝 가능
 */
@Slf4j
@Component
public class PgApiClient {

    private final PgFeignClient pgFeignClient;
    private final String callbackUrl;

    /**
     * PG API 호출 전용 스레드 풀 (Executor)
     *
     * CompletableFuture.supplyAsync()의 기본 스레드 풀은 ForkJoinPool.commonPool()인데,
     * 이는 JVM 전역에서 공유되므로 PG 호출이 지연되면 다른 비동기 작업까지 영향을 받는다.
     * 전용 스레드 풀을 분리하여 PG 장애가 다른 기능으로 전파되는 것을 방지한다 (Bulkhead 패턴).
     *
     * daemon=true: JVM 종료 시 이 스레드가 남아 있어도 프로세스가 종료되도록 한다.
     */
    private final Executor pgExecutor;

    public PgApiClient(
            PgFeignClient pgFeignClient,
            @Value("${pg.callback-url.v2}") String callbackUrl,
            @Value("${pg.executor.pool-size:10}") int poolSize) {
        this.pgFeignClient = pgFeignClient;
        this.callbackUrl = callbackUrl;
        this.pgExecutor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "pg-executor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * PG 결제 요청 — Resilience4j 어노테이션 기반 장애 대응
     *
     * Resilience4j 기본 실행 순서 (바깥 → 안쪽, 데코레이터 패턴):
     *   Retry → CircuitBreaker → TimeLimiter → 실제 Feign HTTP 호출
     *
     *   Spring AOP에서 order 값이 작을수록 바깥쪽에서 감싼다.
     *   Resilience4j 기본 order: Retry(MAX-5) < CircuitBreaker(MAX-4) < TimeLimiter(MAX-2)
     *   → 어노테이션 선언 순서와 무관하게, 위 order 값에 의해 실행 순서가 결정된다.
     *
     * ──────────────────────────────────────────────────────────────────
     * [Retry] pgRetry — 가장 바깥, 일시적 장애에 대한 자동 재시도
     *
     *   Retry란: 일시적(transient) 장애에 대해 자동으로 재시도하는 패턴이다.
     *   네트워크 순간 끊김, 서버 일시 과부하 등 곧 복구될 수 있는 문제에 효과적이다.
     *
     *   가장 바깥에 위치하므로 안쪽(CB + TL + Feign) 전체를 재시도한다.
     *   각 재시도 시도는 CircuitBreaker에 개별 기록된다.
     *
     *   설정:
     *     - 최대 3회 시도 (첫 시도 1회 + 재시도 2회), 간격 500ms (Fixed backoff)
     *     - RetryableException(연결 실패), 5xx 서버 에러만 재시도한다.
     *
     *   재시도하지 않는 경우:
     *     - SocketTimeoutException(읽기 타임아웃): PG가 요청을 이미 수신했을 가능성이 있어
     *       재시도하면 이중 결제(Double Payment)가 발생할 수 있다.
     *     - FeignException 4xx(클라이언트 에러): 재시도해도 같은 결과이므로 불필요
     *     - CallNotPermittedException(서킷 Open): retry-exceptions에 없으므로 재시도 안 함
     *
     * ──────────────────────────────────────────────────────────────────
     * [CircuitBreaker] pgCircuit — 중간, 연속 실패 시 호출 차단
     *
     *   서킷브레이커(Circuit Breaker)란: 전기 회로의 차단기에서 따온 패턴이다.
     *   외부 시스템이 지속적으로 실패하면, 무의미한 호출을 차단하여 우리 시스템의 리소스를 보호한다.
     *
     *   3가지 상태:
     *     CLOSED   (정상)    — 모든 호출을 허용하고 실패율을 모니터링
     *     OPEN     (차단)    — 호출을 즉시 차단하고 fallback 실행 (fail-fast)
     *     HALF_OPEN(시험)    — 제한된 수의 호출만 허용하여 복구 여부를 확인
     *
     *   Retry 안쪽에 위치하므로, Retry의 각 시도가 개별적으로 서킷에 기록된다.
     *   예: Retry 3회 중 2회 실패 → 서킷에 실패 2건 + 성공 1건 기록
     *
     * ──────────────────────────────────────────────────────────────────
     * [TimeLimiter] pgTimeLimiter — 가장 안쪽, 개별 호출 시간 제한 (4초)
     *
     *   TimeLimiter란: 비동기 작업의 실행 시간에 상한을 두는 패턴이다.
     *   가장 안쪽에 위치하므로, Retry 포함 전체가 아닌 개별 호출의 시간을 제한한다.
     *   Feign read timeout(3초)의 안전망 역할이며, Feign이 정상적으로 타임아웃하면 동작하지 않는다.
     *
     *   왜 CompletableFuture를 반환하는가:
     *     @TimeLimiter는 동기 메서드에서 동작하지 않는다.
     *     내부적으로 별도 스레드에서 작업을 실행하고, 지정 시간 초과 시 인터럽트한다.
     *     이를 위해 반드시 CompletableFuture(또는 CompletionStage)를 반환해야 한다.
     *
     *   타임아웃 초과 시: TimeoutException 발생 → CircuitBreaker에 실패로 기록 → fallback 분기
     *
     * ──────────────────────────────────────────────────────────────────
     * [Feign 타임아웃] — 개별 HTTP 호출 레벨의 타임아웃 (PgFeignConfig에서 설정)
     *   - connect timeout: 1초 (TCP 3-way handshake 완료까지 대기)
     *   - read timeout: 3초 (요청 전송 후 응답 수신까지 대기)
     *   - Feign 3초 + TimeLimiter 4초로 개별 호출을 이중 보호한다.
     */
    @TimeLimiter(name = "pgTimeLimiter")
    @CircuitBreaker(name = "pgCircuit")
    @Retry(name = "pgRetry", fallbackMethod = "requestPaymentFallback")
    public CompletableFuture<PgPaymentResponse> requestPayment(Long memberId, PgPaymentRequest request) {
        // callbackUrl을 포함한 새 요청 객체를 생성하여 PG에 전달
        // PG는 결제 처리 완료 후 이 callbackUrl로 결과를 Webhook 전송한다
        PgPaymentRequest requestWithCallback = new PgPaymentRequest(
                request.orderId(),
                request.cardType(),
                request.cardNo(),
                request.amount(),
                callbackUrl);

        // supplyAsync(task, pgExecutor): 전용 스레드 풀에서 비동기 실행
        // @TimeLimiter가 이 Future의 완료를 감시하며, 4초 초과 시 cancel(true)로 인터럽트한다
        return CompletableFuture.supplyAsync(() ->
                pgFeignClient.requestPayment(String.valueOf(memberId), requestWithCallback), pgExecutor);
    }

    /**
     * Resilience4j Fallback 메서드 — 모든 장애 상황의 최종 대체 처리
     *
     * Fallback이란: 주요 기능이 실패했을 때 실행되는 대체 로직이다.
     * TimeLimiter, CircuitBreaker, Retry 어노테이션에서 지정한 fallbackMethod가 이 메서드이다.
     * 즉, 세 패턴 중 어디서든 예외가 발생하면 이 메서드가 호출된다.
     *
     * Fallback 메서드 규약 (Resilience4j):
     *   - 원본 메서드와 동일한 파라미터 + 마지막에 Throwable 파라미터를 추가
     *   - 반환 타입도 원본과 동일해야 한다 (CompletableFuture<PgPaymentResponse>)
     *
     * 타임아웃과 일반 실패를 구분하는 이유:
     *   - TimeoutException → 네트워크 응답만 못 받은 것이지, PG가 요청을 수신했을 수 있다
     *     → TIMEOUT 응답 반환 → Facade에서 UNKNOWN 상태로 처리 → verify API로 확인 가능
     *   - 그 외(CircuitBreaker OPEN, 연결 실패 등) → PG에 요청이 도달하지 않은 것이 확실
     *     → FAILED 응답 반환 → Facade에서 FAILED 상태로 처리
     */
    private CompletableFuture<PgPaymentResponse> requestPaymentFallback(
            Long memberId, PgPaymentRequest request, Throwable t) {

        if (t instanceof TimeoutException) {
            log.warn("PG 결제 요청 타임아웃 - fallback 실행. memberId={}, orderId={}",
                    memberId, request.orderId());
            return CompletableFuture.completedFuture(PgPaymentResponse.timeout());
        }

        log.warn("PG 결제 요청 실패 - fallback 실행. memberId={}, orderId={}, error={}",
                memberId, request.orderId(), t.getMessage());
        return CompletableFuture.completedFuture(
                PgPaymentResponse.failed("PG 시스템 장애: " + t.getMessage()));
    }

    /**
     * PG에 결제 상태를 직접 조회한다 (Polling).
     * verify API에서 콜백 유실 시 보상(Compensation) 용도로 호출한다.
     * Resilience4j를 적용하지 않은 이유: 조회는 부수 효과(Side Effect)가 없으므로
     * 실패해도 결제 상태에 영향을 주지 않는다.
     */
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
}
