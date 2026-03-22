# V2 Resilience4j 기반 구현 정리

V1에서 수동으로 구현한 타임아웃/리트라이/서킷브레이커/폴백을, Resilience4j 라이브러리의 어노테이션 기반으로 전환한 내용을 정리한다.

---

## V1(수동) → V2(Resilience4j) 전환 목적

V1에서 직접 구현한 코드(ManualCircuitBreaker 130줄, ManualPgApiClient 140줄)를 **yml 설정 + 어노테이션**으로 대체하여:

- 설정 변경 시 코드 수정 없이 yml만 변경
- 스레드 안전성, 메트릭 수집 등을 라이브러리가 보장
- Micrometer 연동으로 Grafana에서 서킷 상태 모니터링 가능

---

## 전체 아키텍처

```
POST /api/v2/payments
  → PaymentV2Controller
    → PaymentFacade
      → [Tx1] PaymentService.initiatePayment()       ← 주문 검증 + Payment(REQUESTED) 저장
      → Resilience4jPaymentGateway.requestPayment()   ← 폴백 (최외곽, 2단계)
        → PgApiClient.requestPayment()
          → @Retry (최대 3회, 500ms 간격)
            → @CircuitBreaker (OPEN이면 차단)
              → @TimeLimiter (개별 호출 4초 제한)
                → pgFeignClient.requestPayment()      ← Feign 타임아웃 (1초/3초)
      → [Tx2] PaymentService.updatePaymentStatus()    ← PG 응답에 따라 상태 업데이트
```

---

## HTTP 클라이언트: RestTemplate vs OpenFeign

외부 시스템(PG)을 호출하려면 HTTP 클라이언트가 필요하다. 이 프로젝트에서는 **OpenFeign**을 선택했다.

### RestTemplate 특징

- Spring 3.0부터 제공된 동기 HTTP 클라이언트
- 직접 URL, 헤더, HttpEntity, 응답 파싱을 코드로 작성해야 함 (보일러플레이트 많음)
- Spring 5.0부터 **maintenance mode** — 신규 기능 추가 없음
- 별도 의존성 없이 Spring만으로 사용 가능

### OpenFeign 특징

- Netflix가 만든 **선언적(Declarative) HTTP 클라이언트**
- 인터페이스 + 어노테이션만으로 HTTP 호출 정의 (구현체는 프록시가 자동 생성)
- Spring MVC와 동일한 어노테이션 (`@GetMapping`, `@PostMapping`, `@RequestBody`) 사용
- Spring Cloud 의존성 필요

### Feign을 선택한 이유

1. **선언적 API 정의로 관심사 분리** — PG API 스펙이 인터페이스에 명세로 남아 Infrastructure 레이어 책임이 명확
2. **Resilience 패턴과의 조합** — `Retryer.NEVER_RETRY`로 내장 재시도를 비활성화하고, Resilience4j에 제어를 위임. `FeignException.status()`로 HTTP 상태 코드에 따른 분기가 편리
3. **테스트 용이성 (DIP)** — 인터페이스이므로 `@MockBean`으로 바로 목킹 가능
4. **Spring Cloud 생태계** — 프로젝트가 이미 Spring Cloud(2024.0.1)를 사용 중

---

## 1. 타임아웃 (Timeout)

### 개념

외부 시스템 응답을 무한정 기다리지 않고, **일정 시간 초과 시 실패로 간주하고 끊는 것**. 모든 Resilience 전략의 전제 조건이다.

대부분의 실무 장애는 "실패"가 아니라 **"지연"**에서 시작된다.

### V2에서는 두 레벨의 타임아웃을 적용한다

| 레벨 | 역할 | 설정값 |
|------|------|--------|
| **Feign 타임아웃** | 개별 HTTP 호출 1건의 connect/read 제한 | connect 1초, read 3초 |
| **Resilience4j TimeLimiter** | 개별 호출(Retry 각 시도)의 실행 시간 제한 | 4초 |

#### 왜 두 개 다 필요한가?

- Feign read timeout(3초)이 정상 동작하면 TimeLimiter(4초)는 발동하지 않음 (안전망 역할)
- Feign 타임아웃이 비정상적으로 동작하지 않는 경우(커넥션 풀 지연 등) TimeLimiter가 4초에 강제 중단
- Resilience4j 기본 실행 순서에서 TimeLimiter는 **개별 호출 단위**로 적용됨 (Retry 안쪽)
- **레이어가 다르므로 충돌하지 않는다**: Feign은 HTTP 소켓 레벨, TimeLimiter는 CompletableFuture 레벨

#### TimeLimiter와 CompletableFuture

TimeLimiter는 비동기 실행이 필요하다. 동기 메서드에 `@TimeLimiter`를 붙이면 동작하지 않으므로, 반환 타입을 `CompletableFuture`로 감싸야 한다:

```java
// 동기 → 동작하지 않음
@TimeLimiter(name = "pgTimeLimiter")
public PgPaymentResponse requestPayment(...) { ... }

// CompletableFuture로 감싸야 함
@TimeLimiter(name = "pgTimeLimiter")
public CompletableFuture<PgPaymentResponse> requestPayment(...) {
    return CompletableFuture.supplyAsync(() -> pgFeignClient.requestPayment(...));
}
```

Facade에서는 `.join()`으로 결과를 가져온다. Fallback이 PgApiClient에서 처리되므로 `.join()` 시점에는 항상 안전한 응답이 반환된다.

### V1과 V2 비교

| 항목 | V1 (수동) | V2 (Resilience4j) |
|------|-----------|-------------------|
| Feign 타임아웃 | `PgFeignConfig`에서 `Request.Options` 설정 | 동일 (같은 PgFeignClient 사용) |
| 개별 호출 시간 제한 | 없음 (Feign timeout에만 의존) | `TimeLimiter` 4초 (Feign 3초의 안전망) |
| 타임아웃 시 예외 | `RetryableException`(SocketTimeoutException) 직접 분기 | `TimeoutException` → fallback에서 분기 |

### 설정

```yaml
# Feign 레벨
spring.cloud.openfeign.client.config.pgClient:
  connect-timeout: 1000    # 1초
  read-timeout: 3000       # 3초

# Resilience4j TimeLimiter
resilience4j.timelimiter.instances.pgTimeLimiter:
  timeout-duration: 4s           # 전체 실행 시간 상한
  cancel-running-future: true    # 타임아웃 시 Future 취소
```

### 핵심 판단: 타임아웃 ≠ PG가 처리 안 한 것

타임아웃이 발생해도 PG에서는 실제로 결제가 진행됐을 수 있다. "우리가 응답을 못 받은 것"이지 "PG가 처리를 안 한 것"이 아니다.

→ 타임아웃 시 FAILED가 아닌 **UNKNOWN** 상태로 처리하고, 콜백/수동확인으로 보정한다.

---

## 2. 리트라이 (Retry)

### 개념

일시적 장애(transient fault)에서 재시도를 통해 정상 응답을 받아내는 전략. PG 요청 성공률이 60%이므로 재시도 효과가 크다.

### 핵심 판단: 무조건 재시도하면 안 된다

결제는 돈이 걸려있다. **PG에 요청이 도달하지 않았다고 확신할 수 있는 경우에만** 재시도해야 한다.

| 상황 | 재시도 | 이유 |
|------|--------|------|
| **연결 실패** (RetryableException) | O | PG에 요청 미도달 → 안전 |
| **5xx 서버 에러** | O | PG 일시적 장애 |
| **응답 타임아웃** (SocketTimeoutException) | **X** | PG에 요청 도달 가능 → 이중 결제 위험 |
| **4xx 클라이언트 에러** | **X** | 잘못된 요청 → 재시도해도 동일 결과 |

### 대기 시간 전략 선택

| 전략 | 동작 | 선택 여부 |
|------|------|-----------|
| Fixed | 매번 같은 시간 대기 | **선택 (500ms)** |
| Exponential | 점점 늘어남 (500→1000→2000ms) | 미선택 |
| Random | 범위 내 랜덤 | 미선택 |

**Fixed 500ms를 선택한 이유:**
- PG Simulator에 우리 서비스만 호출하므로 동시 재시도 분산(Jitter)이 불필요
- TimeLimiter 4초 안에 3회 시도를 확보하기에 안전 (Exponential이면 대기가 길어져 3차 시도 불가능)
- 단순하고 예측 가능

### V1과 V2 비교

| 항목 | V1 (수동) | V2 (Resilience4j) |
|------|-----------|-------------------|
| 최대 시도 | 3회 (코드 상수 `MAX_RETRY_COUNT`) | 3회 (yml `max-attempts`) |
| 대기 시간 | 500ms Fixed (`Thread.sleep`) | 500ms Fixed (yml `wait-duration`) |
| 재시도 대상 | 연결 실패, 5xx (if문 분기) | `RetryableException` + 5xx 예외 4종 (yml `retry-exceptions`) |
| 재시도 제외 | 타임아웃, 4xx (코드 분기) | `retry-exceptions`에 포함되지 않은 예외는 자동 제외 |
| 설정 변경 | 코드 수정 + 재배포 | **yml 수정만으로 가능** |

### 설정

```yaml
resilience4j.retry.instances.pgRetry:
  max-attempts: 3               # 총 3회 시도
  wait-duration: 500ms          # Fixed 500ms 간격
  retry-exceptions:             # 재시도 대상 (연결 실패 + 5xx 서버 에러)
    - feign.RetryableException
    - feign.FeignException$InternalServerError
    - feign.FeignException$BadGateway
    - feign.FeignException$ServiceUnavailable
    - feign.FeignException$GatewayTimeout
  fail-after-max-attempts: true # 최종 실패 시 예외 → fallback 연결
```

### 리트라이 효과 (PG 요청 성공률 60% 기준)

| 시도 횟수 | 최종 성공률 | 계산 |
|-----------|------------|------|
| 1회 (리트라이 없음) | 60% | 0.6 |
| 2회 (리트라이 1회) | 84% | 1 - 0.4² |
| 3회 (리트라이 2회) | 93.6% | 1 - 0.4³ |

---

## 3. 서킷브레이커 (Circuit Breaker)

### 개념

외부 시스템이 반복적으로 실패하면 **아예 호출을 차단해서 시스템을 보호**하는 패턴. "더 이상 보내지 말자"를 결정한다.

### 3가지 상태

```
CLOSED ──실패율 50% 이상──> OPEN ──10초 경과──> HALF_OPEN
   ↑                                              │
   └──3건 모두 성공──────────────────────────────────┤
                                                    └──1건이라도 실패──> OPEN
```

| 상태 | 동작 |
|------|------|
| **CLOSED** | 정상 상태, 모든 호출 허용. sliding window(10건)로 실패율 추적 |
| **OPEN** | 호출 차단. PG 호출 없이 즉시 fallback 반환 (fail-fast) |
| **HALF_OPEN** | 제한된 호출만 허용(3건). 성공 시 CLOSED, 실패 시 다시 OPEN |

### V1과 V2 비교

| 항목 | V1 (수동) | V2 (Resilience4j) |
|------|-----------|-------------------|
| 구현 | `ManualCircuitBreaker` 130줄 직접 구현 | **yml 설정 6줄 + `@CircuitBreaker`** |
| sliding window | `boolean[]` 배열 + `AtomicInteger` | 라이브러리 내부 관리 |
| 스레드 안전 | `AtomicReference`, `AtomicInteger` 직접 처리 | 라이브러리가 보장 |
| **느린 호출 감지** | 없음 | `slow-call-duration-threshold: 3s` |
| 타임아웃 처리 | 코드에서 명시적으로 제외 | 예외 타입으로 자동 판단 |
| 메트릭 | 없음 | **Micrometer 자동 연동** (Grafana 확인 가능) |
| 설정 변경 | 코드 수정 + 재배포 | **yml 수정만으로 가능** |

V2에서 추가된 `slow-call` 감지: 응답은 왔지만 3초 이상 걸린 호출도 실패로 취급 가능. PG가 죽진 않았지만 느려지고 있는 징후를 잡아낸다. (Feign read timeout과 동일한 3초 기준)

### 설정

```yaml
resilience4j.circuitbreaker.instances.pgCircuit:
  sliding-window-size: 10                          # 최근 10건 기준
  minimum-number-of-calls: 10                      # 최소 10건 이후 실패율 평가 시작
  failure-rate-threshold: 50                        # 실패율 50% -> OPEN
  wait-duration-in-open-state: 10s                  # OPEN 10초 유지
  permitted-number-of-calls-in-half-open-state: 3   # HALF_OPEN에서 3건
  slow-call-duration-threshold: 3s                  # 3초 초과 = 느린 호출 (Feign read timeout과 동일)
  slow-call-rate-threshold: 50                      # 느린 호출 비율 50% -> OPEN
```

---

## 4. 폴백 (Fallback)

### 개념

타임아웃, 리트라이 소진, 서킷 OPEN 등 **어떤 이유로든 PG 호출이 실패했을 때 안전한 응답을 반환**하는 것. 본질적으로 **try-catch**이며, Resilience4j는 이걸 어노테이션으로 해준다.

### Fallback의 동작 원리

```java
// Resilience4j가 내부적으로 하는 일 (개념적 코드)
public CompletableFuture<PgPaymentResponse> requestPayment_proxy(...) {

    // 1. CircuitBreaker OPEN → 호출 안 하고 바로 fallback
    if (circuitBreaker.isOpen()) {
        return requestPaymentFallback(..., new CallNotPermittedException());
    }

    // 2. 원본 메서드 실행
    try {
        CompletableFuture<PgPaymentResponse> result = requestPayment(...);
        circuitBreaker.recordSuccess();
        return result;
    } catch (Exception e) {
        // 3. 예외 → 실패 기록 후 fallback
        circuitBreaker.recordFailure();
        return requestPaymentFallback(..., e);
    }
}
```

**어디서 실패하든 결국 fallback 메서드가 호출된다.** `Throwable t` 파라미터로 실패 원인을 구분할 수 있다.

### V2의 2단계 Fallback

**1단계 — PgApiClient (Resilience4j fallback)**

```java
private CompletableFuture<PgPaymentResponse> requestPaymentFallback(
        Long memberId, PgPaymentRequest request, Throwable t) {

    if (t instanceof TimeoutException) {
        return CompletableFuture.completedFuture(PgPaymentResponse.timeout());
    }
    return CompletableFuture.completedFuture(
            PgPaymentResponse.failed("PG 시스템 장애: " + t.getMessage()));
}
```

- `TimeoutException` → `TIMEOUT` 응답 (Facade에서 UNKNOWN 처리)
- 그 외 → `FAILED` 응답

**2단계 — Resilience4jPaymentGateway (최외곽 방어, DIP 적용)**

```java
// Resilience4jPaymentGateway.java — PaymentGateway 인터페이스 구현체
@Override
public PaymentGatewayResponse requestPayment(Long memberId, Payment payment) {
    try {
        PgPaymentRequest pgRequest = PgPaymentRequest.from(payment, pgApiClient.getCallbackUrl());
        PgPaymentResponse pgResponse = pgApiClient.requestPayment(memberId, pgRequest).join();
        return toGatewayResponse(pgResponse);
    } catch (Exception e) {
        return PaymentGatewayResponse.failed("결제 처리 중 오류 발생");
    }
}
```

- DIP 적용: PaymentFacade → PaymentGateway(Domain 인터페이스) ← Resilience4jPaymentGateway(Infrastructure)
- PG 전용 응답(`PgPaymentResponse`)을 도메인 응답(`PaymentGatewayResponse`)으로 변환
- 1단계에서 예상치 못한 예외 (NullPointerException, 직렬화 오류 등)까지 방어
- 어떤 예외든 HTTP 500으로 사용자에게 전파하지 않음

### 폴백 후 3분기 상태 업데이트

```java
// PaymentFacade에서 PaymentGatewayResponse 기반으로 상태를 판단한다
PaymentGatewayResponse response = paymentGateway.requestPayment(memberId, payment);
paymentService.updatePaymentStatus(payment.getId(), response);

// PaymentService.updatePaymentStatus() 내부에서 3분기 처리:
//   response.isAccepted() → PENDING (콜백 대기)
//   response.isTimeout()  → UNKNOWN (PG에서 처리됐을 수 있음 → 상태 확인 필요)
//   그 외               → FAILED (서킷 OPEN, 리트라이 소진, PG 거부 등)
```

### V1과 V2 비교

| 항목 | V1 (수동) | V2 (Resilience4j) |
|------|-----------|-------------------|
| 1단계 fallback | `ManualPgApiClient` if문 분기 | `@Retry(fallbackMethod)` |
| 2단계 fallback | `ManualPaymentFacade.callPgWithFallback()` | `Resilience4jPaymentGateway.requestPayment()` try-catch |
| 타임아웃 구분 | `PgPaymentResponse.isTimeout()` | `t instanceof TimeoutException` → `PgPaymentResponse.timeout()` |
| 상태 분기 | 3분기 (PENDING/UNKNOWN/FAILED) | 3분기 (동일) |

---

## Resilience4j 어노테이션 실행 순서

```
요청 흐름 (바깥 → 안쪽):

[Retry (최대 3회, 500ms 간격)]
  └─ [CircuitBreaker (OPEN이면 차단)]
       └─ [TimeLimiter (개별 호출 4초 제한)]
            └─ [Feign 타임아웃 (connect 1초, read 3초)]
                 └─ PG 서버

Spring AOP order: Retry(MAX-5) < CircuitBreaker(MAX-4) < TimeLimiter(MAX-2)
→ order 값이 작을수록 바깥에서 감싸므로, Retry가 가장 바깥이다.
```

### 시나리오별 동작

| 시나리오 | 동작 | 최종 응답 | Payment 상태 |
|----------|------|-----------|-------------|
| PG 정상 응답 | 1회 호출 성공 | 접수 응답 | PENDING |
| PG 일시 장애 (503) | Retry 3회 중 성공 | 접수 응답 | PENDING |
| PG 서버 다운 | Retry 3회 모두 실패 → fallback | FAILED | FAILED |
| PG 응답 지연 | Feign read timeout(3초) → TimeLimiter(4초) 안전망 → Retry 재시도 → fallback | TIMEOUT | UNKNOWN |
| 서킷 OPEN | CircuitBreaker가 즉시 차단 → Retry 대상 아님 → fallback | FAILED | FAILED |
| PG 4xx 응답 | Retry 대상 아님 → 즉시 fallback | FAILED | FAILED |

---

## 설정 전체 요약

```yaml
# Feign 타임아웃
spring.cloud.openfeign.client.config.pgClient:
  connect-timeout: 1000
  read-timeout: 3000

# Resilience4j
resilience4j:
  timelimiter:
    instances:
      pgTimeLimiter:
        timeout-duration: 4s
        cancel-running-future: true
  retry:
    instances:
      pgRetry:
        max-attempts: 3
        wait-duration: 500ms
        retry-exceptions:
          - feign.RetryableException
          - feign.FeignException$InternalServerError
          - feign.FeignException$BadGateway
          - feign.FeignException$ServiceUnavailable
          - feign.FeignException$GatewayTimeout
        fail-after-max-attempts: true
  circuitbreaker:
    instances:
      pgCircuit:
        sliding-window-size: 10
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        slow-call-duration-threshold: 3s
        slow-call-rate-threshold: 50
```

---

## 전체 동작 흐름도

```
 Client (POST /api/v2/payments)
   │
   ▼
 ┌──────────────────────────────────────────────────────────────────────┐
 │  ① PaymentV2Controller                                              │
 │     - 입력 검증 (orderId 파싱 실패 → BAD_REQUEST)                      │
 │     - memberId 헤더 추출                                             │
 └──────────────────────────────┬───────────────────────────────────────┘
                                │
                                ▼
 ┌──────────────────────────────────────────────────────────────────────┐
 │  ② PaymentFacade.processPayment()  — 3-Phase 트랜잭션 분리            │
 │                                                                      │
 │  ┌────────────────────────────────────────────────────────────────┐  │
 │  │ Phase 1 (@Transactional)  PaymentService.initiatePayment()    │  │
 │  │  - 주문 존재/소유권 검증                                         │  │
 │  │  - 중복 결제 방지 (FAILED만 재시도 허용, resetForRetry)            │  │
 │  │  - Payment 생성 → 상태: REQUESTED                               │  │
 │  └────────────────────────────────────────────────────────────────┘  │
 │                                │                                     │
 │                                ▼                                     │
 │  ┌────────────────────────────────────────────────────────────────┐  │
 │  │ Phase 2 (트랜잭션 없음)  Resilience4jPaymentGateway             │  │
 │  │  - PaymentGateway 인터페이스 통해 DIP 적용                        │  │
 │  │  - PG API 호출 (CompletableFuture + 전용 스레드 풀)              │  │
 │  │  - Resilience4j 전체 스택 적용 (아래 데코레이터 스택 참조)          │  │
 │  │  - 2단계 Fallback으로 모든 예외 안전 처리                          │  │
 │  └────────────────────────────────────────────────────────────────┘  │
 │                                │                                     │
 │                                ▼                                     │
 │  ┌────────────────────────────────────────────────────────────────┐  │
 │  │ Phase 3 (@Transactional)  PaymentService.updatePaymentStatus()│  │
 │  │  - PG 응답에 따라 상태 결정:                                      │  │
 │  │    ✅ PG 접수 → PENDING (콜백 대기)                               │  │
 │  │    ⏱️ Timeout → UNKNOWN (보상 필요)                               │  │
 │  │    ❌ 실패    → FAILED (재시도 가능)                               │  │
 │  └────────────────────────────────────────────────────────────────┘  │
 └──────────────────────────────────────────────────────────────────────┘
```

---

## Resilience4j 데코레이터 스택 (Phase 2 상세)

Resilience4j 기본 AOP order에 따라 바깥 → 안쪽 순서: Retry → CircuitBreaker → TimeLimiter

```
 요청 ──→ ┌──────────────────────────────────────────────────────┐
          │  🔄 Retry (3회, 500ms 간격) — 가장 바깥              │
          │     ✅ 재시도: 연결실패, 5xx                           │
          │     ❌ 제외:   4xx, CallNotPermittedException         │
          │     → 3회 소진 시 예외 → Fallback                     │
          │                                                       │
          │  ┌──────────────────────────────────────────────┐     │
          │  │  🔌 CircuitBreaker (pgCircuit)                │     │
          │  │     CLOSED: 정상, sliding window 10건 추적     │     │
          │  │     OPEN:  50%+ 실패 시 즉시 차단 (10s 유지)   │     │
          │  │     HALF_OPEN: 3건 시험 호출 후 복구 판단       │     │
          │  │     → CallNotPermittedException → FAILED       │     │
          │  │                                                │     │
          │  │  ┌──────────────────────────────────────┐      │     │
          │  │  │  🕐 TimeLimiter (4s) — 개별 호출 제한  │      │     │
          │  │  │     Feign 3초의 안전망 역할             │      │     │
          │  │  │     → TimeoutException → Fallback      │      │     │
          │  │  │                                       │      │     │
          │  │  │  ┌────────────────────────────────┐   │      │     │
          │  │  │  │  🌐 Feign HTTP Call             │   │      │     │
          │  │  │  │     connect: 1s / read: 3s     │   │      │     │
          │  │  │  │     전용 ThreadPool 10개 (격리)  │   │      │     │
          │  │  │  │           │                     │   │      │     │
          │  │  │  │     ┌─────▼──────┐              │   │      │     │
          │  │  │  │     │  PG Server │              │   │      │     │
          │  │  │  │     └────────────┘              │   │      │     │
          │  │  │  └────────────────────────────────┘   │      │     │
          │  │  └──────────────────────────────────────┘      │     │
          │  └──────────────────────────────────────────────┘     │
          └──────────────────────────────────────────────────────┘
```

---

## Payment 상태 전이 다이어그램

```
                    ┌──────────┐
         ┌─────────│ REQUESTED │──────────────────────────────┐
         │         └──────────┘                               │
         │              │                                     │
    PG 접수 성공     Timeout 발생                   Circuit OPEN /
         │              │                         Retry 소진 / PG 거부
         ▼              ▼                                     ▼
    ┌─────────┐    ┌──────────┐                         ┌────────┐
    │ PENDING │    │ UNKNOWN  │──verify API 보상조회──→   │ FAILED │
    └────┬────┘    └──────────┘                         └───┬────┘
         │                                                  │
    PG Callback                                       resetForRetry()
    (비동기 웹훅)                                       → REQUESTED 복귀
    ┌────┴────┐                                        (재시도 가능)
    │         │
    ▼         ▼
 ┌─────────┐ ┌────────┐
 │ SUCCESS │ │ FAILED │    ← 최종 상태 (변경 불가, 멱등성 보장)
 └─────────┘ └────────┘
```

---

## 보상 트랜잭션 (Callback 유실 대비)

PENDING/UNKNOWN 상태가 정체될 경우, 두 가지 경로로 최종 상태를 확정한다:

| 방식 | 엔드포인트 | 동작 | 비고 |
|------|-----------|------|------|
| **Pull (폴링)** | `GET /api/v2/payments/verify?orderId=xxx` | PG에 직접 상태 조회 → Payment 갱신 | Resilience 미적용 (조회는 부작용 없음) |
| **Push (웹훅)** | `POST /api/v2/payments/callback` | PG가 비동기로 결과 전송 | transactionId 일치 검증 (위변조 방지) |

---

## 장애 시나리오별 대응 요약

| 장애 시나리오 | 대응 패턴 | 결과 상태 | 후속 조치 |
|---|---|---|---|
| PG 일시적 5xx | **Retry** (3회, 500ms 간격) | 성공 시 PENDING | 자동 복구 |
| PG 연결 실패 | **Retry** → 소진 시 Fallback | FAILED | 사용자 재시도 |
| PG 응답 지연 (>3s) | Feign read-timeout → **Retry** | 재시도 후 판단 | - |
| 개별 호출 >4s | **TimeLimiter** 강제 중단 (per-attempt) | UNKNOWN | verify API 보상 |
| PG 연속 장애 (50%+) | **CircuitBreaker** OPEN → 즉시 차단 | FAILED | 10s 후 자동 복구 시도 |
| PG 느린 응답 (>3s, 50%+) | **CircuitBreaker** slow-call 감지 → OPEN | FAILED | 10s 후 자동 복구 시도 |
| PG 4xx (잘못된 요청) | Retry 제외, 즉시 실패 | FAILED | 요청 수정 필요 |
| PG callback 유실 | - | PENDING/UNKNOWN 정체 | verify API 폴링 |
| PG 장애 → 타 기능 전파 | **Bulkhead** (전용 스레드풀 10개) | - | 격리됨, 전파 차단 |

---

## 파일 구조 (V2)

```
com.loopers
├── domain/payment/
│   ├── PaymentGateway.java             # DIP: 도메인 인터페이스 (Application → Domain 의존)
│   ├── PaymentGatewayResponse.java     # 도메인 레벨 PG 응답 DTO
│   ├── PaymentGatewayStatusResponse.java
│   ├── Payment.java                    # 결제 엔티티
│   └── PaymentService.java            # 결제 도메인 서비스
│
├── infrastructure/payment/
│   ├── PgApiClient.java               # V2: @TimeLimiter + @CircuitBreaker + @Retry
│   ├── Resilience4jPaymentGateway.java # PaymentGateway 구현체 (2단계 폴백 + 응답 변환, @Primary)
│   ├── ManualPaymentGateway.java       # V1 수동 구현 PaymentGateway 구현체
│   ├── PgFeignClient.java             # FeignClient 인터페이스 (V1/V2 공유)
│   ├── PgFeignConfig.java             # Feign 타임아웃 + Retryer.NEVER_RETRY
│   ├── PgPaymentRequest.java
│   ├── PgPaymentResponse.java         # timeout(), failed() 팩토리, isTimeout() 분기
│   └── PgPaymentStatusResponse.java
│
├── application/payment/
│   ├── PaymentFacade.java             # V2 Facade (PaymentGateway 통해 PG 호출 + 3분기 상태 업데이트)
│   └── PaymentInfo.java
│
└── interfaces/api/payment/
    ├── PaymentV2Controller.java       # V2 REST API (/api/v2/payments)
    └── dto/PaymentV1Dto.java          # Request/Response DTO (V1/V2 공유)
```
