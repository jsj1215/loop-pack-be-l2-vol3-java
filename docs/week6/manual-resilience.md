# V1 수동 Resilience 구현 정리

외부 PG 시스템 연동 시 발생할 수 있는 장애에 대응하기 위해, Resilience4j 없이 타임아웃/리트라이/서킷브레이커/폴백을 직접 구현한 내용을 정리한다.

---

## 왜 수동으로 구현했는가

Resilience4j가 제공하는 것과 동일한 패턴을 **라이브러리 없이 직접 구현**함으로써, 각 패턴의 동작 원리를 이해하고 나중에 Resilience4j(V2)로 전환했을 때 "어노테이션 뒤에서 무슨 일이 일어나는지"를 알기 위함이다.

---

## 전체 아키텍처

```
POST /api/v1/payments
  → PaymentV1Controller
    → ManualPaymentFacade
      → [Tx1] PaymentService.initiatePayment()    ← 주문 검증 + Payment(REQUESTED) 저장
      → callPgWithFallback()                       ← 폴백 (최외곽)
        → ManualPgApiClient.requestPayment()
          → 서킷브레이커 판단                         ← OPEN이면 즉시 fallback
          → executeWithRetry()                     ← 리트라이 (최대 3회)
            → pgFeignClient.requestPayment()       ← 타임아웃 (Feign timeout)
      → updatePaymentStatus()                      ← PG 응답에 따라 상태 분기
      → [Tx2] PaymentService.handlePgResponse()    ← Payment 상태 업데이트
```

---

## 트랜잭션 분리 설계

### 핵심 판단: PG 호출을 트랜잭션 밖에서

기존 `OrderFacade.createOrder()`는 재고/쿠폰/포인트 차감을 하나의 `@Transactional`로 묶고 있다. 여기에 PG 호출까지 넣으면:

| 문제 | 설명 |
|---|---|
| **DB 커넥션 풀 고갈** | PG 응답 지연(1~5초) 동안 DB 커넥션을 점유. HikariCP 풀 10개면 동시 10건으로 서비스 전체 마비 |
| **롤백 불일치** | PG 결제 성공 후 이후 로직에서 예외 → DB 롤백되지만 PG 결제는 그대로 |
| **타임아웃 모호성** | 타임아웃 시 PG에서 실제 결제됐는지 알 수 없음. 롤백하면 돈은 빠졌는데 주문 없음 |
| **락 장기 보유** | 비관적 락을 사용하는 재고 row가 PG 응답 대기 동안 잠겨 다른 주문 블로킹 |
| **장애 전파** | PG 지연 → 커넥션 고갈 → 결제 외 다른 API까지 전부 멈춤 |

따라서 **결제를 별도 API(`POST /api/v1/payments`)로 분리하고, 트랜잭션도 3개로 나눴다:**

```
[트랜잭션 1] Payment(REQUESTED) 저장 → 커밋
[트랜잭션 밖] PG API 호출
[트랜잭션 2] PG 응답에 따라 Payment 상태 업데이트 → 커밋
[트랜잭션 3] PG 콜백 수신 시 최종 결과 반영 → 커밋
```

### PaymentService에 @Transactional을 건 이유

기존 프로젝트 패턴은 Domain Service는 non-transactional, Facade에서 `@Transactional`을 거는 구조다. 하지만 결제는 Facade에서 트랜잭션을 분리해야 하므로, PaymentService 각 메서드에 개별 `@Transactional`을 부여했다.

실무에서 외부 시스템 연동이 있는 도메인은 Service에 `@Transactional`을 거는 것이 일반적이며, OrderService 같은 순수 내부 로직은 기존 패턴을 유지한다. 도메인 특성이 다르면 패턴이 달라지는 것이 자연스럽다.

---

## Payment 상태 전이

```
REQUESTED ──PG 접수 성공──> PENDING ──콜백 성공──> SUCCESS
    │                        │
    │                        └──콜백 실패──> FAILED
    ├──PG 접수 실패──> FAILED
    └──타임아웃──> UNKNOWN ──상태 확인──> SUCCESS or FAILED
```

| 상태 | 의미 | 다음 행동 |
|---|---|---|
| **REQUESTED** | Payment 생성됨, PG 호출 전 | PG 호출 진행 |
| **PENDING** | PG가 요청을 접수함 (비동기이므로 아직 결제 완료 아님) | 콜백 대기 |
| **SUCCESS** | 결제 완료 (콜백 or 수동확인) | 최종 상태 |
| **FAILED** | 결제 실패 (PG 거부, 서킷 OPEN, 리트라이 소진 등) | 최종 상태 |
| **UNKNOWN** | 타임아웃 발생. PG에서 처리됐을 수 있음 | 콜백 대기 or 수동확인 |

`isFinalized()`: SUCCESS 또는 FAILED면 true → 멱등성 보장 (같은 콜백이 두 번 와도 안전)

`needsVerification()`: PENDING 또는 UNKNOWN이면 true → 수동 확인 대상

---

## 1. 타임아웃 (Timeout)

### 개념

외부 시스템 응답을 무한정 기다리지 않고, **일정 시간 초과 시 실패로 간주하고 끊는 것**. 모든 Resilience 설계의 전제 조건이다.

대부분의 실무 장애는 "실패"가 아니라 **"지연"**에서 시작된다. PG가 멈추면 요청 스레드가 대기 → 수백 개 쌓이면 앱 전체 마비 → 결제와 무관한 API까지 영향.

### 타임아웃의 두 가지 레벨

| 레벨 | 설명 | 설정값 |
|---|---|---|
| **Connection Timeout** | TCP 연결을 맺기까지의 최대 대기 시간 | 1초 |
| **Read Timeout** | 연결 이후 응답 데이터를 받기까지의 최대 대기 시간 | 3초 |

PG Simulator의 요청 지연이 100~500ms이므로 connection 1초면 충분하고, read 3초는 요청 지연(최대 500ms) + 여유분을 고려한 값이다.

### 설정 방법 (FeignClient)

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          pgClient:
            connect-timeout: 1000
            read-timeout: 3000
```

### 핵심 고민: 타임아웃 ≠ PG가 처리 안 한 것

타임아웃이 발생해도 **PG에서는 실제로 결제가 진행됐을 수 있다.** 우리가 응답을 못 받은 것이지, PG가 요청을 안 받은 것이 아니다.

이 차이가 UNKNOWN 상태가 필요한 이유이며, 콜백/수동확인으로 보정하는 구조가 필요한 이유다.

### 예외 분기 처리

| 예외 | 의미 | 처리 | 리트라이 |
|---|---|---|---|
| `RetryableException` (내부 `SocketTimeoutException`) | 응답 타임아웃 — PG에 요청이 도달했을 수 있음 | **UNKNOWN** | X |
| `RetryableException` (내부 `ConnectException`) | 연결 실패 — PG에 요청이 도달하지 못함 | **FAILED** | O |
| `FeignException` (status >= 500) | PG 서버 5xx 에러 | **FAILED** | O |
| `FeignException` (status < 500) | PG 서버 4xx 에러 | **FAILED** | X |

---

## 2. 리트라이 (Retry)

### 개념

일시적 장애(transient fault)에서 재시도를 통해 정상 응답을 받아내는 전략. PG 서버가 일시적으로 503을 반환하거나 네트워크 패킷 손실이 발생할 때 효과적이다.

### 핵심 고민: 무조건 재시도하면 안 된다

결제는 돈이 걸려있다. **"PG에 요청이 도달하지 않았다"고 확신할 수 있는 경우에만 리트라이**해야 한다.

| 상황 | 리트라이 | 이유 |
|---|---|---|
| **연결 실패** (ConnectException) | **O** | PG에 요청이 도달하지 않았으므로 안전 |
| **5xx 서버 에러** | **O** | PG가 요청을 처리하지 못한 일시적 장애 |
| **응답 타임아웃** (SocketTimeoutException) | **X** | PG에 요청이 도달했을 수 있음 → 이중 결제 위험 |
| **4xx 클라이언트 에러** | **X** | 잘못된 요청이므로 재시도해도 같은 결과 |
| **PG 정상 응답이지만 거부** (한도초과 등) | **X** | 비즈니스 로직 실패이므로 재시도 의미 없음 |

### 설정값

- 최대 재시도: 2회 (총 3회 시도)
- 재시도 간격: 500ms (고정)

### 구현 흐름

```
executeWithRetry() 호출
  ├─ 시도 1 → 성공 → 즉시 반환
  │            SocketTimeoutException → 즉시 TIMEOUT 반환 (리트라이 안 함)
  │            4xx FeignException → 즉시 FAILED 반환 (리트라이 안 함)
  │            ConnectException → 500ms 대기 → 시도 2
  │            5xx FeignException → 500ms 대기 → 시도 2
  ├─ 시도 2 → (같은 분기 로직)
  └─ 시도 3 → 성공 → 즉시 반환
               실패 → 최종 FAILED 반환
```

### 리트라이 효과 (PG 요청 성공률 60% 기준)

| 시도 횟수 | 최종 성공률 | 계산 |
|---|---|---|
| 1회 (리트라이 없음) | 60% | 0.6 |
| 2회 (리트라이 1회) | 84% | 0.6 + 0.4 × 0.6 |
| 3회 (리트라이 2회) | 93.6% | 0.6 + 0.4 × 0.6 + 0.16 × 0.6 |

---

## 3. 서킷브레이커 (Circuit Breaker)

### 개념

외부 시스템이 반복적으로 실패하면 **일시적으로 호출 자체를 차단**하여 시스템을 보호하는 패턴. 누전 차단기처럼 계속 실패하는 요청을 끊고 전체 시스템을 보호한다.

PG 요청 성공률이 60%밖에 안 될 때 연속 실패 시 불필요한 요청을 계속 보내면 우리 시스템만 손해다. 빠르게 실패(fail-fast)하여 스레드/커넥션 자원을 보호해야 한다.

### 3가지 상태

```
CLOSED ──실패율 50% 이상──> OPEN ──10초 경과──> HALF_OPEN
   ↑                                              │
   └──2건 모두 성공──────────────────────────────────┤
                                                    └──1건이라도 실패──> OPEN
```

| 상태 | 동작 |
|---|---|
| **CLOSED** | 정상 상태, 모든 호출 허용. 슬라이딩 윈도우(최근 10건)로 실패율 추적 |
| **OPEN** | 호출 차단. PG 호출 없이 즉시 fallback 반환. 스레드/커넥션 자원 보호 |
| **HALF_OPEN** | 제한된 호출만 허용(2건). 성공 시 CLOSED 복구, 실패 시 다시 OPEN |

### 설정값

| 항목 | 값 | 설명 |
|---|---|---|
| 슬라이딩 윈도우 크기 | 10 | 최근 10건의 요청으로 실패율 계산 |
| 실패율 임계치 | 50% | 10건 중 5건 이상 실패 시 OPEN |
| OPEN 유지 시간 | 10초 | OPEN 후 10초 경과 시 HALF_OPEN으로 전이 |
| HALF_OPEN 허용 호출 | 2건 | 2건 모두 성공해야 CLOSED로 복구 |

### 핵심 고민: 타임아웃을 실패로 기록할 것인가?

**기록하지 않는다.** 이유:

- 타임아웃은 PG 도달 여부가 불확실하다
- 타임아웃을 실패로 기록하면, 실제로는 PG가 정상인데 네트워크 지연만으로 서킷이 열릴 수 있다
- 서킷이 불필요하게 열리면 정상 요청까지 차단되어 오히려 서비스 가용성이 떨어진다

### 구현: ManualCircuitBreaker

Resilience4j 없이 직접 구현. 슬라이딩 윈도우(ring buffer)로 최근 N건의 성공/실패를 추적하고, 실패율이 임계치를 넘으면 OPEN으로 전이한다.

- `isCallPermitted()`: 현재 상태에서 호출이 가능한지 판단. OPEN → 대기시간 경과 확인 → HALF_OPEN 전이
- `recordSuccess()`: 성공 기록. HALF_OPEN에서 허용된 호출이 모두 성공하면 CLOSED 복구
- `recordFailure()`: 실패 기록. HALF_OPEN에서 하나라도 실패하면 다시 OPEN

### ManualPgApiClient 통합 흐름

```
requestPayment() 호출
  ├─ circuitBreaker.isCallPermitted()?
  │   ├─ NO (OPEN) → 즉시 fallback 반환 (PG 호출 안 함)
  │   └─ YES (CLOSED/HALF_OPEN) → PG 호출 (리트라이 포함)
  │       ├─ 성공 → circuitBreaker.recordSuccess()
  │       ├─ 실패 → circuitBreaker.recordFailure()
  │       └─ 타임아웃 → 기록 안 함 (도달 여부 불확실)
```

---

## 4. 폴백 (Fallback)

### 개념

타임아웃, 리트라이 소진, 서킷브레이커 OPEN 등 **어떤 이유로든 PG 호출이 실패했을 때 사용자에게 반환하는 안전한 응답**. 모든 장애 대응 패턴의 최종 단계다.

핵심 원칙: **PG 장애가 우리 시스템의 에러(HTTP 500)로 전파되지 않도록 한다.**

### 폴백이 적용되는 시점

| 시점 | 폴백 내용 | Payment 상태 |
|---|---|---|
| 서킷브레이커 OPEN | `PgPaymentResponse.failed("서킷 브레이커 OPEN: PG 호출 차단")` | FAILED |
| 타임아웃 | `PgPaymentResponse.timeout()` | UNKNOWN |
| 리트라이 소진 | 마지막 실패 응답 반환 | FAILED |
| PG 정상 응답이지만 거부 | PG 응답 그대로 전달 | FAILED |
| 예상치 못한 예외 | `PgPaymentResponse.failed("결제 처리 중 오류 발생")` | FAILED |

### 구현: 두 단계 폴백

**1단계 — ManualPgApiClient (인프라 레벨)**

서킷브레이커 OPEN 시 PG 호출 없이 즉시 실패 응답을 반환한다. 리트라이 소진 시에도 마지막 실패 응답을 반환한다. 이 단계에서는 예외를 던지지 않고 항상 `PgPaymentResponse`를 반환한다.

**2단계 — ManualPaymentFacade (애플리케이션 레벨)**

`callPgWithFallback()` 메서드가 최외곽에서 try-catch로 감싸, 1단계에서 예상치 못한 예외가 발생해도 HTTP 500 대신 안전한 실패 응답을 반환한다.

```java
private PgPaymentResponse callPgWithFallback(Long memberId, Payment payment) {
    try {
        PgPaymentRequest pgRequest = PgPaymentRequest.from(payment, manualPgApiClient.getCallbackUrl());
        return manualPgApiClient.requestPayment(memberId, pgRequest);
    } catch (Exception e) {
        log.error("PG 호출 중 예상치 못한 예외 발생 paymentId={}", payment.getId(), e);
        return PgPaymentResponse.failed("결제 처리 중 오류 발생");
    }
}
```

### 폴백 후 상태 분기 (updatePaymentStatus)

PG 응답(폴백 포함)에 따라 Payment 상태를 결정한다:

```java
if (pgResponse.isAccepted()) {
    // PG 접수 성공 → PENDING (콜백 대기)
} else if (pgResponse.isTimeout()) {
    // 타임아웃 → UNKNOWN (PG에서 처리됐을 수 있으므로 확인 필요)
} else {
    // 기타 실패 → FAILED (서킷 OPEN, 리트라이 소진, PG 거부 등)
}
```

모든 경우에 HTTP 200 + 결제 상태를 반환하므로, **클라이언트는 `status` 필드를 확인하여 다음 행동을 결정**할 수 있다.

---

## 4가지 패턴이 합쳐진 전체 흐름

```
POST /api/v1/payments 요청
  │
  ├─ [Tx1] Payment(REQUESTED) 저장 → DB 커밋
  │
  ├─ callPgWithFallback()                         ← 폴백 (최외곽, 예상치 못한 예외 보호)
  │   │
  │   └─ ManualPgApiClient.requestPayment()
  │       │
  │       ├─ 서킷브레이커 판단
  │       │   └─ OPEN → 즉시 FAILED 반환           ← 서킷브레이커
  │       │
  │       └─ executeWithRetry()                    ← 리트라이
  │           │
  │           ├─ 시도 1: pgFeignClient 호출         ← 타임아웃 (Feign timeout)
  │           │   ├─ 성공 → 반환
  │           │   ├─ 타임아웃 → 즉시 TIMEOUT 반환
  │           │   ├─ 4xx → 즉시 FAILED 반환
  │           │   └─ 연결실패/5xx → 500ms 대기
  │           │
  │           ├─ 시도 2: (같은 로직)
  │           └─ 시도 3: (같은 로직) → 최종 FAILED 반환
  │
  ├─ updatePaymentStatus()
  │   ├─ 접수 성공 → PENDING
  │   ├─ 타임아웃 → UNKNOWN
  │   └─ 기타 실패 → FAILED
  │
  ├─ [Tx2] Payment 상태 업데이트 → DB 커밋
  │
  └─ 200 OK + PaymentResponse 반환 (절대 500이 아님)
```

---

## 비동기 결제 연동 (콜백 + 수동 확인)

PG Simulator는 비동기 결제이므로, **요청 성공 ≠ 결제 완료**. 최종 결과는 별도로 확인해야 한다.

### PG Simulator 동작 방식

1. `POST /api/v1/payments` → 요청 접수 (성공률 60%, 지연 100~500ms)
2. PG 내부에서 비동기 처리 (처리 지연 1~5초)
3. 처리 완료 시 `callbackUrl`로 결과 전송 (성공 70% / 한도초과 20% / 잘못된카드 10%)

### 콜백 엔드포인트

```
POST /api/v1/payments/callback
```

PG가 결제 결과를 전송하면 `PaymentService.syncPaymentResult()`로 Payment와 Order 상태를 최종 갱신한다. 인증 없이 접근 가능하도록 `WebMvcConfig`에서 제외했다.

### 수동 상태 확인

```
GET /api/v1/payments/verify?orderId={orderId}
```

콜백이 유실됐거나 UNKNOWN 상태인 결제를 PG API로 직접 조회하여 동기화한다.

### 멱등성 보장

`syncPaymentResult()`에서 `payment.isFinalized()`를 먼저 확인한다. 이미 처리 완료된 결제면 무시하므로, 같은 콜백이 두 번 와도 안전하다.

---

## 파일 구조

```
com.loopers
├── domain/payment/
│   ├── Payment.java              # 엔티티 (상태 전이 로직 캡슐화)
│   ├── PaymentStatus.java        # enum: REQUESTED, PENDING, SUCCESS, FAILED, UNKNOWN
│   ├── PaymentService.java       # 비즈니스 로직 (각 메서드에 @Transactional)
│   └── PaymentRepository.java    # 도메인 인터페이스
│
├── domain/order/
│   ├── OrderStatus.java          # enum: PENDING, PAID, FAILED, CANCELLED
│   └── Order.java                # status 필드 + markPaid(), markFailed() 추가
│
├── infrastructure/payment/
│   ├── ManualPgApiClient.java    # V1 수동 (타임아웃 + 리트라이 + 서킷브레이커)
│   ├── ManualCircuitBreaker.java # 수동 서킷브레이커 구현
│   ├── PgFeignClient.java        # FeignClient 인터페이스
│   ├── PgPaymentRequest.java     # PG 요청 DTO
│   ├── PgPaymentResponse.java    # PG 응답 DTO (timeout(), failed() 팩토리)
│   ├── PgPaymentStatusResponse.java
│   ├── PaymentJpaRepository.java
│   └── PaymentRepositoryImpl.java
│
├── application/payment/
│   ├── ManualPaymentFacade.java  # V1 Facade (트랜잭션 분리 + 폴백)
│   └── PaymentInfo.java          # 응답 DTO (record)
│
└── interfaces/api/payment/
    ├── PaymentV1Controller.java  # V1 REST API
    └── dto/PaymentV1Dto.java     # Request/Response DTO
```
