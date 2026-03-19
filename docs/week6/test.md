# 6주차 부하 테스트 시나리오 & 검증 가이드

## 1. 테스트 환경

### PG Simulator 설정
| 항목 | 값 |
|------|-----|
| 요청 성공 확률 | 60% |
| 요청 지연 | 100ms ~ 500ms |
| 처리 지연 | 1s ~ 5s |
| 처리 결과 | 성공 70% / 한도 초과 20% / 잘못된 카드 10% |

### Resilience4j 설정 요약

| 컴포넌트 | 설정 | 값 |
|----------|------|-----|
| **Feign** | connect-timeout | 1s |
| **Feign** | read-timeout | 3s |
| **TimeLimiter** (pgTimeLimiter) | timeout-duration | 4s |
| **TimeLimiter** (pgTimeLimiter) | cancel-running-future | true |
| **Retry** (pgRetry) | max-attempts | 3 (원본 1회 + 재시도 2회) |
| **Retry** (pgRetry) | wait-duration | 500ms (고정 간격) |
| **Retry** (pgRetry) | retry-exceptions | `RetryableException`, `InternalServerError`, `BadGateway`, `ServiceUnavailable`, `GatewayTimeout` |
| **CircuitBreaker** (pgCircuit) | sliding-window-size | 10 |
| **CircuitBreaker** (pgCircuit) | minimum-number-of-calls | 10 |
| **CircuitBreaker** (pgCircuit) | failure-rate-threshold | 50% |
| **CircuitBreaker** (pgCircuit) | wait-duration-in-open-state | 10s |
| **CircuitBreaker** (pgCircuit) | permitted-number-of-calls-in-half-open-state | 3 |
| **CircuitBreaker** (pgCircuit) | slow-call-duration-threshold | 3s |
| **CircuitBreaker** (pgCircuit) | slow-call-rate-threshold | 50% |
| **CircuitBreaker** (pgCircuit) | ignore-exceptions | `FeignException$BadRequest`, `Forbidden`, `NotFound`, `Conflict` |

### 실행 순서 (Outermost → Innermost)
```
Retry(3회, 500ms 간격)
  → CircuitBreaker(pgCircuit)
    → TimeLimiter(4s)
      → Feign(connect 1s, read 3s)
        → PG Server
```

---

## 2. k6 부하 테스트 시나리오

### 사전 준비

```bash
# 1. 인프라 실행
docker-compose -f ./docker/infra-compose.yml up -d

# 2. 모니터링 실행
docker-compose -f ./docker/monitoring-compose.yml up -d

# 3. PG Simulator 실행
cd pg-simulator && ./gradlew bootRun

# 4. 앱 실행
./gradlew :apps:commerce-api:bootRun

# 5. 테스트 데이터 생성 (10,000건 주문)
bash docs/week6/k6-scripts/setup-test-data.sh
```

### 테스트 데이터 전략

- **주문**: 10,000건 (ID: 100000 ~ 109999, PENDING 상태)
- **orderId 할당**: `uniqueOrderId()` — VU별 고정 블록(50건) 할당으로 VU 간 충돌 방지
  - VU 1: orderId 100000~100049, VU 2: 100050~100099, ...
  - 최대 200 VU 지원 (10,000건 / 200 VU = VU당 50건)
- **각 시나리오 실행 전**: `DELETE FROM payments; UPDATE orders SET status = 'PENDING';`으로 초기화

> 기존 1,000건에서 10,000건으로 확대한 이유: orderId 재사용으로 비즈니스 에러(CONFLICT)가
> 대량 발생하여 Resilience4j 메트릭이 오염되었기 때문이다.

### 공통 실행 커맨드

```bash
k6 run -e BASE_URL=http://localhost:8080 \
       -e MIN_ORDER_ID={MIN} \
       -e MAX_ORDER_ID={MAX} \
       docs/week6/k6-scripts/<script>
```

> `{MIN}`, `{MAX}`는 `setup-test-data.sh` 실행 후 출력되는 값을 사용한다.

---

### A. Timeout 증명 (`v2-01-timeout.js`)

**증명 목표**: Feign read timeout(3s)과 TimeLimiter(4s)가 응답 시간 상한으로 동작하는지 확인

| 증명 항목 | 기대 결과 | 검증 방법 |
|----------|----------|----------|
| Feign read timeout 동작 | 대부분의 응답이 3초 이내 | `within_feign_timeout_3s` Rate > 80% |
| TimeLimiter 상한 보장 | 95% 응답이 4초 이내 | `within_timelimiter_4s` Rate > 95% |
| 절대 상한 | 99% 응답이 5초 이내 | `within_absolute_max_5s` Rate > 99% |
| 타임아웃 시 상태 전이 | 타임아웃 → Payment **UNKNOWN** | `timeout_unknown_count` > 0, 3초 이상 소요 |
| Graceful 처리 | 5xx 에러 없음 | `http_req_failed` Rate < 1% |

**응답 시간 버킷 분포**:

| 구간 | 의미 | 커스텀 메트릭 |
|------|------|-------------|
| < 1s | PG 정상 응답 (100~500ms) | `bucket_under_1s` |
| 1s ~ 3s | PG 지연이나 Feign timeout 이내 | `bucket_1s_to_3s` |
| 3s ~ 4s | Feign timeout 이후, TimeLimiter 이내 | `bucket_3s_to_4s` |
| > 4s | TimeLimiter에 의해 차단되어야 함 (거의 없어야 함) | `bucket_over_4s` |

**시나리오 구성**: ramping-vus 5→20→50 VUs, 총 85초

**핵심**: PG Simulator는 비동기 결제 모델이므로 HTTP Ack를 100~500ms 내 반환한다.
"처리 지연 1~5s"는 콜백 도착 시간이지 HTTP 응답 시간이 아니다.
따라서 k6 부하 테스트에서 TimeLimiter 타임아웃이 0건인 것은 **정상**이다.
TimeLimiter는 네트워크 패킷 유실, 방화벽 블랙홀 등 인프라 장애를 방어하는 안전망이며,
통합 테스트(`PgApiClientIntegrationTest.TimeLimiterTimeoutCase`)에서 5초 지연 시 4초에 차단되는 동작을 증명한다.

---

### B. Retry 증명 (`v2-02-retry.js`)

**증명 목표**: 5xx 실패 시 재시도(최대 3회, 500ms 간격)가 최종 성공률을 높이는지 확인

| 증명 항목 | 기대 결과 | 검증 방법 |
|----------|----------|----------|
| 성공률 개선 | PG 1회 성공률(60%)보다 높음 | `final_success_rate` Rate > 60% |
| 재시도 패턴 감지 | 응답 시간에 500ms 간격 클러스터 관찰 | 재시도 횟수 추정 카운터 비교 |
| 재시도 횟수 상한 | 3회 시도 후 포기 | 응답 시간 < 10초 (retry budget) |
| Graceful 처리 | 재시도 소진 후에도 5xx 없음 | `http_req_failed` Rate < 1% |

**재시도 횟수 추정 (응답 시간 기반)**:

| 추정 재시도 | 응답 시간 범위 | 계산 근거 | 커스텀 메트릭 |
|------------|--------------|----------|-------------|
| 0회 (1회 성공) | < 600ms | PG 100~500ms | `estimated_no_retry` |
| 1회 재시도 | 600ms ~ 1200ms | 원본 + 500ms wait + 재시도 | `estimated_one_retry` |
| 2회 재시도 | 1200ms ~ 2200ms | + 500ms wait + 재시도 | `estimated_two_retries` |
| 재시도 소진 | > 2200ms 또는 FAILED | 3회 모두 실패 | `estimated_exhausted` |

**이론적 성공률**: `1 - 0.4^3 = 93.6%` (PG 실패율 40%일 때 3회 시도)

**시나리오 구성**: constant-vus 15 VUs, 60초

**핵심**: Retry를 통한 성공률 개선 효과를 정량적으로 측정한다.
단, retry-exceptions에 5xx만 포함되어 있으므로 4xx(BAD_REQUEST 등)는 재시도하지 않는다.

---

### C. CircuitBreaker 증명 (`v2-03-circuit-breaker.js`)

**증명 목표**: 서킷 브레이커의 CLOSED → OPEN → HALF_OPEN → CLOSED 라이프사이클 동작 확인

| 증명 항목 | 기대 결과 | 검증 방법 |
|----------|----------|----------|
| CLOSED → OPEN 전이 | 실패율 50% 초과 시 서킷 OPEN | `circuit_open_fast_fail` Count > 0 |
| OPEN fast-fail | PG 호출 없이 즉시 응답 (< 100ms) | fast-fail 응답 시간 < 100ms check |
| slow call 감지 | 3초 이상 응답이 50% 넘으면 OPEN | `circuit_slow_call` 카운터 확인 |
| 시스템 보호 | OPEN이 5xx를 차단 | `no_server_error` Rate > 99% |

**서킷 상태 탐지 기준**:

| 추정 상태 | 근거 | 커스텀 메트릭 |
|----------|------|-------------|
| CLOSED (정상) | 응답 시간 100ms+ (PG 실제 호출) | `circuit_closed_responses` |
| OPEN (차단) | 응답 시간 < 100ms (PG 호출 없이 즉시 fallback) | `circuit_open_fast_fail` |
| slow call | 응답 시간 >= 3s (slow-call-duration-threshold) | `circuit_slow_call` |

**시나리오 구성 (4단계)**:

| Phase | VUs | 시간 | 목적 |
|-------|-----|------|------|
| Phase 1 | 3 | 30s | CLOSED 상태 기준선 관찰 |
| Phase 2 | 60 | 40s | 고부하 → 실패/지연 유발 → OPEN 전이 |
| Phase 3 | 40 | 30s | OPEN ↔ HALF_OPEN 사이클 반복 관찰 |
| Phase 4 | 5 | 20s | 부하 감소 → CLOSED 복구 확인 |

**서킷 라이프사이클 예상 흐름**:

```
Phase 1 (저부하)       Phase 2 (고부하)         Phase 3 (유지)          Phase 4 (쿨다운)
CLOSED ──────────── → OPEN ──(10s 대기)──→ HALF_OPEN ──(3건 시험)──→ CLOSED 복구
  정상 응답              fast-fail (<100ms)     3건만 PG 호출           정상 복귀
```

**핵심**: 서킷이 OPEN되면 PG에 요청을 보내지 않아(fail-fast) 불필요한 자원 소모를 방지.
서킷 OPEN의 원인은 "고부하"가 아니라 `slidingWindowSize=10`에서의 **통계적 분산**이다.
PG 에러율 40%에서 10건 중 5건 이상 실패할 확률은 이항분포로 `P(X≥5|n=10,p=0.4) ≈ 16.6%`이다.
부하 수준(VU 수)과 무관하게 이 확률로 서킷이 열린다.
Grafana에서 `resilience4j_circuitbreaker_state{name="pgCircuit"}` 으로 상태 전이 타임라인을 시각적으로 확인.

---

### D. Fallback 증명 (`v2-04-fallback.js`)

**증명 목표**: 모든 장애 유형에서 fallback이 5xx 없이 유효한 상태를 반환하는지 확인

| 증명 항목 | 기대 결과 | 검증 방법 |
|----------|----------|----------|
| 5xx 에러 0건 | 어떤 장애에서도 서버 에러 없음 | `no_5xx_error` Rate > 99% |
| 유효한 상태 반환 | 모든 200 응답에 의미 있는 상태 포함 | `has_valid_status` Rate > 95% |
| 200 응답 비율 | 압도적 다수가 200 | `graceful_response` Rate > 95% |

**Fallback 유형 분류**:

| Fallback 유형 | 원인 | 탐지 기준 | 결과 상태 | 커스텀 메트릭 |
|--------------|------|----------|----------|-------------|
| 서킷 OPEN fast-fail | `CallNotPermittedException` | 응답 시간 < 100ms | FAILED/UNKNOWN | `fallback_circuit_open` |
| 타임아웃 | `TimeoutException` / Feign timeout | 3~5초 + UNKNOWN | UNKNOWN | `fallback_timeout` |
| 재시도 소진 | 3회 5xx 후 포기 | FAILED 상태 | FAILED | `fallback_retry_exhausted` |
| 정상 성공 | fallback 미개입 | PENDING/REQUESTED | PENDING | `normal_success` |

**Fallback 동작 경로**:

```
PgApiClient.requestPayment()
  ├── 정상 응답 → PENDING
  ├── TimeoutException → paymentFallback() → TIMEOUT 응답 → Payment UNKNOWN
  ├── CircuitBreaker OPEN → CallNotPermittedException → paymentFallback()
  ├── FeignException(5xx) → Retry 시도 → 소진 시 paymentFallback()
  └── FeignException(4xx) → Retry 안 함 → paymentFallback()
```

**시나리오 구성**: ramping-vus 10→30→80→100 VUs, 총 105초 (극한 부하로 모든 장애 유형 동시 유발)

**핵심**: 2단계 fallback 구조
- 1단계: `PgApiClient.paymentFallback()` — Resilience4j 어노테이션 fallback
- 2단계: `AbstractPaymentFacade`의 try-catch — `.join()` 과정에서 발생하는 예외 처리

---

### E. 종합 Resilience 증명 (`v2-05-combined-resilience.js`)

**증명 목표**: Timeout + Retry + CircuitBreaker + Fallback 4개 패턴이 협력하여 극한 상황에서도 시스템을 보호하는지 종합 검증

| 증명 항목 | 기대 결과 | 검증 방법 |
|----------|----------|----------|
| 5xx 에러 0건 | 전체 테스트 동안 서버 에러 없음 | `no_5xx_error` Rate > 99% |
| 시스템 안정성 | 90% 이상 유효 응답 | `system_stability` Rate > 90% |
| 저부하 정상 동작 | Phase 1에서 높은 성공률 | `phase1_low_load_success` Rate > 50% |
| 패턴 협력 | 부하에 따라 개입 패턴이 변화 | 패턴별 카운터 분포 변화 |

**Resilience 패턴 개입 순서 (부하 증가에 따른 예상)**:

```
저부하 (5 VUs)    → 대부분 pattern_none_normal (Resilience 미개입)
중부하 (30 VUs)   → pattern_retry_detected 증가 (일시적 5xx를 재시도로 복구)
고부하 (80 VUs)   → pattern_timeout 증가 + pattern_circuit_fast_fail 시작
극한 (120 VUs)    → pattern_circuit_fast_fail 지배적 (서킷이 PG 차단)
쿨다운 (5 VUs)    → pattern_none_normal 복귀 (서킷 CLOSED 회복)
```

**시나리오 구성 (5단계)**:

| Phase | VUs | 시간 | 관찰 포인트 |
|-------|-----|------|------------|
| Phase 1 | 5 | 30s | 기준선 — 모든 패턴 비활성, 정상 성공률 측정 |
| Phase 2 | 30 | 30s | Retry 간헐적 개입, 타임아웃 시작 |
| Phase 3 | 80 | 30s | 서킷 OPEN, fast-fail 다수 발생 |
| Phase 4 | 120 | 30s | 모든 패턴 최대 동작, 극한 안정성 |
| Phase 5 | 5 | 30s | 쿨다운 — 서킷 복구, 정상 처리 재개 |

**패턴 분류 기준**:

| 분류 | 조건 | 커스텀 메트릭 |
|------|------|-------------|
| 정상 (패턴 미개입) | 100ms~3s + PENDING/REQUESTED | `pattern_none_normal` |
| Retry 감지 | 600ms~2.2s + PENDING (재시도 후 성공) | `pattern_retry_detected` |
| Timeout | >= 3s + UNKNOWN | `pattern_timeout` |
| Circuit fast-fail | < 100ms (PG 미호출) | `pattern_circuit_fast_fail` |
| Fallback FAILED | FAILED 상태 | `pattern_fallback_failed` |

**핵심**: 개별 패턴 테스트에서 확인한 각 동작이 실제로 협력하여 시스템을 보호하는지 종합 증명.
teardown에서 각 메트릭의 해석 가이드가 출력된다.

---

## 3. 보상(Compensation) 시나리오

> 아래 시나리오는 k6 부하 테스트가 아닌, **단위/통합 테스트 및 HTTP 수동 테스트**로 검증한다.

| # | 시나리오 | 기대 결과 | 검증 포인트 |
|---|---------|----------|------------|
| V-1 | 타임아웃 후 PG에서는 결제 성공 → verify 호출 | UNKNOWN → SUCCESS로 동기화 | 결제가 실제로 됐는데 우리만 모르는 상황 복구 |
| V-2 | 콜백 유실 (PG가 callback 전달 실패) | verify API로 수동 상태 동기화 | PENDING 상태가 영원히 남지 않는지 |
| V-3 | 콜백 중복 수신 | 이미 SUCCESS인 Payment에 재콜백 → 무시 | 멱등성 보장 (상태 변경 없음) |
| V-4 | 위조 콜백 (잘못된 pgTransactionId) | 예외 발생, 상태 변경 거부 | pgTransactionId 검증 로직 동작 확인 |

**테스트 커맨드**:
```bash
# Payment 서비스 통합 테스트 (보상 시나리오 포함)
./gradlew :apps:commerce-api:test --tests "com.loopers.application.payment.*"
./gradlew :apps:commerce-api:test --tests "com.loopers.domain.payment.*"
```

---

## 4. 단위/통합 테스트

Mock 기반으로 각 시나리오별 상태 전이를 검증한다.

```bash
# Payment Facade 단위 테스트
./gradlew :apps:commerce-api:test --tests "com.loopers.application.payment.PaymentFacadeTest"

# 주문-결제 통합 테스트 (Testcontainers + PgFeignClient Mock)
./gradlew :apps:commerce-api:test --tests "com.loopers.application.order.OrderPaymentIntegrationTest"

# Payment 도메인 테스트
./gradlew :apps:commerce-api:test --tests "com.loopers.domain.payment.*"

# PgApiClient Resilience4j AOP 통합 테스트 (TimeLimiter 타임아웃 증명 포함)
./gradlew :apps:commerce-api:test --tests "com.loopers.infrastructure.payment.PgApiClientIntegrationTest"
```

### TimeLimiter 타임아웃 동작 증명 (통합 테스트)

k6 부하 테스트에서는 비동기 PG 모델 특성상 TimeLimiter 타임아웃이 발생하지 않으므로,
**통합 테스트에서 PG 응답 지연을 시뮬레이션하여 TimeLimiter 동작을 직접 증명**한다.

| 테스트 | 시나리오 | 검증 포인트 |
|--------|---------|------------|
| `timeLimiterCutsOffSlowResponse` | PG 응답 5초 지연 | TimeLimiter(4s)가 차단 → TIMEOUT 응답 + timeout 메트릭 증가 |
| `normalResponseWithinTimeLimiter` | PG 응답 1초 지연 | 정상 완료 → successful 메트릭 증가 + timeout 미증가 |

---

## 5. HTTP 수동 테스트

`http/commerce-api/payments-v1.http`로 개별 API를 호출하여 응답을 확인한다.

**테스트 흐름**:
1. `POST /api/v2/payments` → 결제 요청 → 응답에서 상태 확인 (PENDING / UNKNOWN / FAILED)
2. `POST /api/v2/payments/callback` → PG 콜백 시뮬레이션 → 상태 전이 확인
3. `GET /api/v2/payments/verify?orderId={id}` → 상태 동기화 확인

---

## 6. 모니터링 (Grafana + Prometheus)

| 도구 | URL | 인증 |
|------|-----|------|
| **Grafana** | http://localhost:3000 | admin / admin |
| **Prometheus** | http://localhost:9090 | - |

### 핵심 메트릭 & PromQL 쿼리

#### CircuitBreaker 메트릭

| 메트릭 | PromQL | 확인 포인트 |
|--------|--------|------------|
| 서킷 상태 | `resilience4j_circuitbreaker_state{name="pgCircuit"}` | 0=CLOSED, 1=OPEN, 2=HALF_OPEN 전이 |
| 실패율 | `resilience4j_circuitbreaker_failure_rate{name="pgCircuit"}` | 50% 넘는 시점에 OPEN 전환 |
| 호출 결과 | `resilience4j_circuitbreaker_calls_total{name="pgCircuit"}` | successful / failed / not_permitted 비율 |
| slow call 비율 | `resilience4j_circuitbreaker_slow_call_rate{name="pgCircuit"}` | 3초 초과 응답 비율 |

#### Retry 메트릭

| 메트릭 | PromQL | 확인 포인트 |
|--------|--------|------------|
| 재시도 결과 | `resilience4j_retry_calls_total{name="pgRetry"}` | successful_with_retry vs failed_with_retry 비율 |

#### TimeLimiter 메트릭

| 메트릭 | PromQL | 확인 포인트 |
|--------|--------|------------|
| 타임아웃 발생 | `resilience4j_timelimiter_calls_total{name="pgTimeLimiter"}` | timeout vs successful 비율 |

#### HTTP 요청 메트릭

| 메트릭 | PromQL | 확인 포인트 |
|--------|--------|------------|
| 응답 시간 분포 | `http_server_requests_seconds_bucket` | p95 / p99 latency 변화 |
| 요청 수 | `http_server_requests_seconds_count` | 초당 처리량 변화 |

### Grafana 스크린샷 촬영 가이드

| 시점 | 촬영 내용 | 확인 포인트 |
|------|----------|------------|
| **부하 전** | 서킷 CLOSED 상태, 메트릭 baseline | 정상 상태에서의 기준값 |
| **부하 중** | 서킷 OPEN 전환, Retry 횟수 증가, 실패율 변화 | Resilience 패턴이 실시간 동작하는 모습 |
| **부하 후** | HALF_OPEN → CLOSED 복귀 과정 | 외부 시스템 복구 시 자동 정상화 |

---

## 7. 보고서 구성

실제 테스트 결과는 `docs/week6/report.md`에 기록한다.

```
1. 테스트 환경
   - PG Simulator 설정 (성공률 60%, 지연 100~500ms)
   - Resilience4j 설정값 요약 + 실행 순서

2. k6 부하 테스트 결과
   ├── v2-01-timeout     : 타임아웃 경계 → Feign 3초 / TimeLimiter 4초 검증
   ├── v2-02-retry        : 재시도 효과 → 성공률 개선, 재시도 횟수 분포
   ├── v2-03-circuit-breaker : 서킷 라이프사이클 → CLOSED→OPEN→HALF_OPEN→CLOSED
   ├── v2-04-fallback     : Fallback 완전성 → 극한 부하에서 5xx 0건
   └── v2-05-combined     : 종합 → 4개 패턴 협력 동작 증명

3. 설정별 검증 결과 및 최종 권장
   - 현재 설정값 vs 테스트 결과 기반 권장값

4. 종합 평가 및 최종 의사결정
   - 장애 격리 / 타임아웃 보호 / 서킷 브레이커 / 리트라이 / 극한 부하 안정성
   - Fallback 구조 확정, 서킷 브레이커 동작 확인

5. (선택) 모니터링 대시보드 (Grafana 스크린샷)
   ├── 서킷 상태 전이 타임라인
   ├── 실패율 / slow call 비율 변화
   └── HTTP latency (p95/p99) 변화

6. (선택) 보상 메커니즘 검증
   └── verify API를 통한 UNKNOWN → SUCCESS 동기화
```
