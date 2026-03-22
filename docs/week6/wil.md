# Week 6 — WIL (What I Learned)

## 이번 주 과제 목표

1. **PG 연동 결제 시스템 구현** — 외부 PG Simulator와 연동하는 결제 API 설계 및 구현
2. **Resilience 패턴 적용** — Timeout / Retry / Circuit Breaker / Fallback을 수동 구현(V1) + Resilience4j(V2) 두 가지 버전으로 구현
3. **트랜잭션 분리 설계** — 외부 시스템 호출을 트랜잭션 밖으로 분리하고, Saga 보상 트랜잭션 설계
4. **부하 테스트 및 모니터링** — k6 부하 테스트로 Resilience 전략 동작 검증

---

## 1. 트랜잭션 분리 — "외부 호출을 트랜잭션 안에 넣으면 안 되는 이유"

### 무엇을 했는가

기존 `OrderFacade.createOrder()`는 재고/쿠폰/포인트 차감을 하나의 `@Transactional`로 묶고 있었다. 여기에 PG 호출까지 넣으면 DB 커넥션 풀 고갈, 롤백 불일치, 락 장기 보유 등의 문제가 발생한다. 이를 해결하기 위해 결제를 별도 API(`POST /api/v1/payments`, `POST /api/v2/payments`)로 분리하고 트랜잭션을 3개로 나눴다.

### 트랜잭션 분리 구조

```
[Tx 1] OrderPlacementService — 주문 생성 + 재고/쿠폰/포인트 차감 → PENDING 상태로 커밋
[Tx 밖] PG API 호출 — Resilience 패턴 적용
[Tx 2] PaymentService — PG 응답에 따라 Payment 상태 업데이트 → 커밋
[Tx 3] OrderCompensationService — 결제 실패 시 보상 트랜잭션 (재고/쿠폰/포인트 복원)
```

### 배운 점

- **PG 호출이 트랜잭션 안에 있으면 생기는 문제들을 직접 정리해보니** 확실히 와닿았다. PG 응답 지연(1~5초) 동안 DB 커넥션을 점유하면 HikariCP 풀이 금방 고갈되고, 결제와 무관한 API까지 전부 멈춘다. "트랜잭션은 짧게"라는 말이 왜 중요한지 체감했다.
- **PaymentService에 `@Transactional`을 건 판단** — 기존 프로젝트 패턴은 Domain Service는 non-transactional, Facade에서 `@Transactional`을 거는 구조다. 하지만 결제는 Facade에서 트랜잭션을 분리해야 하므로 PaymentService 각 메서드에 개별 `@Transactional`을 부여했다. 도메인 특성이 다르면 패턴이 달라지는 것이 자연스럽다는 걸 배웠다.
- **타임아웃 ≠ PG가 처리 안 한 것** — 타임아웃이 발생해도 PG에서는 실제로 결제가 진행됐을 수 있다. 이 차이가 `UNKNOWN` 상태가 필요한 이유이며, 콜백/수동확인으로 보정하는 구조가 필요한 이유다.

---

## 2. Payment 상태 머신 설계 — "결제는 성공/실패가 아니라 상태 전이"

### 무엇을 했는가

Payment 엔티티에 5가지 상태(`REQUESTED`, `PENDING`, `SUCCESS`, `FAILED`, `UNKNOWN`)를 정의하고, 각 상태 간 전이 규칙을 설계했다. 멱등성과 동시성을 고려한 상태 머신을 구현했다.

```
REQUESTED ──PG 접수 성공──> PENDING ──콜백 성공──> SUCCESS (최종)
    │                        │
    │                        └──콜백 실패──> FAILED (최종)
    ├──PG 접수 실패──> FAILED
    └──타임아웃──> UNKNOWN ──상태 확인──> SUCCESS or FAILED
```

### 핵심 설계 결정

| 결정 | 내용 | 이유 |
|------|------|------|
| `isFinalized()` | SUCCESS/FAILED면 true | 같은 콜백이 두 번 와도 안전하게 무시 (멱등성) |
| `needsVerification()` | REQUESTED/PENDING/UNKNOWN이면 true | 수동 확인 대상 식별 |
| `@Version` | Optimistic Locking | 콜백과 verify가 동시에 도달해도 Lost Update 방지 |
| Unique(order_id) | 1:1 관계 강제 | 중복 Payment 생성 방지 |

### 배운 점

- **처음엔 성공/실패 두 가지만 생각했는데**, PG 호출 후 응답을 못 받는 경우(타임아웃)를 고려하니 `UNKNOWN` 상태가 반드시 필요했다. 돈이 빠졌는데 주문이 없는 상황을 방지하려면, "모른다"는 상태를 명시적으로 관리해야 한다.
- **`PENDING` 상태의 필요성** — PG가 비동기 처리 모델이라 "접수했지만 아직 결과 안 나옴" 상태가 존재한다. 동기 모델에서는 필요 없지만, 콜백 기반 비동기 PG에서는 이 중간 상태가 필수다.

---

## 3. Resilience 패턴 — "수동 구현(V1)으로 원리를 이해하고, Resilience4j(V2)로 전환"

### 무엇을 했는가

Timeout / Retry / Circuit Breaker / Fallback 4가지 패턴을 **라이브러리 없이 직접 구현(V1)**한 뒤, **Resilience4j 어노테이션 기반(V2)**으로 전환했다.

### V1 수동 구현

- `ManualCircuitBreaker` — synchronized 기반 슬라이딩 윈도우(링 버퍼) + 3상태 전이
- `ManualPgApiClient` — while 루프 리트라이 + 서킷 브레이커 판단 + 예외 분기 처리
- 코드량: ManualCircuitBreaker 130줄 + ManualPgApiClient 140줄

### V2 Resilience4j 전환

```
@Retry(name="pgRetry")           ← 최외곽: 3회 시도, 500ms 간격
  → @CircuitBreaker(name="pgCircuit")  ← OPEN이면 차단
    → @TimeLimiter(name="pgTimeLimiter")  ← 개별 호출 4초 제한
      → pgFeignClient.requestPayment()     ← Feign 타임아웃 (connect 1s, read 3s)
```

V1에서 270줄 코드로 구현한 것을 **yml 설정 + 어노테이션**으로 대체하면서도, 동일한 동작을 보장할 수 있었다.

### 배운 점

- **수동 구현을 먼저 한 것이 큰 도움이 됐다.** Resilience4j의 `@CircuitBreaker` 어노테이션 뒤에서 슬라이딩 윈도우, 상태 전이, 실패율 계산이 어떻게 동작하는지 직접 구현해봤기 때문에 설정값의 의미를 정확히 이해할 수 있었다.
- **리트라이 대상 판별이 핵심** — 결제는 돈이 걸려있으므로 무조건 재시도하면 안 된다. 연결 실패(ConnectException)와 5xx는 리트라이하지만, 응답 타임아웃(SocketTimeoutException)은 PG에 요청이 도달했을 수 있으므로 리트라이하지 않는다. 이 구분이 이중 결제를 막는 핵심이다.
- **Feign 리트라이를 `NEVER_RETRY`로 설정한 이유** — Feign 자체 리트라이(기본 3회)와 Resilience4j 리트라이가 중첩되면 최대 9회 호출이 될 수 있다. Feign 리트라이를 끄고 Resilience4j에만 위임해야 한다.

---

## 4. Circuit Breaker — "슬라이딩 윈도우와 COUNT_BASED를 선택한 이유"

### 무엇을 했는가

서킷 브레이커의 동작 원리를 전기 회로 차단기에서 출발해 소프트웨어 패턴까지 정리하고, COUNT_BASED 슬라이딩 윈도우를 선택한 근거를 문서화했다.

### 핵심 설정과 근거

| 설정 | 값 | 근거 |
|------|-----|------|
| sliding-window-type | COUNT_BASED | PG 결제는 트래픽이 산발적. TIME_BASED는 한산한 시간대에 데이터 부족으로 오판 가능 |
| sliding-window-size | 10 | 최근 10건의 성공/실패를 링 버퍼에 기록 |
| failure-rate-threshold | 50% | 10건 중 5건 이상 실패 시 OPEN |
| wait-duration-in-open-state | 10s | PG 장애 복구 시간 고려 |
| slow-call-duration-threshold | 3s | read-timeout(3s)과 맞춤 — 느린 호출도 실패로 집계 |

### 배운 점

- **COUNT_BASED vs TIME_BASED** — 결제처럼 트래픽이 불규칙한 도메인에서는 "최근 N건"을 보는 COUNT_BASED가 더 안정적이다. TIME_BASED는 트래픽이 적은 시간대에 윈도우 안에 데이터가 부족해서 실패율을 오판할 수 있다.
- **Fixed Window의 경계 효과** — 고정 윈도우는 구간 경계에 실패가 걸치면 감지를 못 하는 사각지대가 있다. 슬라이딩 윈도우는 관찰 범위가 계속 이동하므로 이 문제를 줄일 수 있다.
- **Half-Open 상태의 역할** — "차단기를 살짝 올려서 전류를 조금만 흘려보는 것"이라는 비유가 직관적이었다. 시험 요청 3건 중 성공하면 CLOSED, 실패하면 다시 OPEN.

---

## 5. Saga 보상 트랜잭션 — "실패 시 원상복구"

### 무엇을 했는가

결제 실패 시 이미 차감된 재고, 쿠폰, 포인트를 자동으로 복원하는 `OrderCompensationService`를 구현했다.

```
주문 생성 (Tx 1) — 재고/쿠폰/포인트 차감, PENDING
    ↓
결제 처리 (Tx 2) — PG 호출 결과 반영
    ↓ (실패 시)
보상 트랜잭션 (Tx 3) — 재고 복원 + 쿠폰 사용 취소 + 포인트 환불 + 주문 FAILED
```

### 배운 점

- **분산 트랜잭션 대신 보상 트랜잭션** — 외부 시스템(PG)이 포함되면 하나의 트랜잭션으로 묶을 수 없다. 각 단계를 독립 트랜잭션으로 실행하고, 실패 시 역방향으로 되돌리는 Saga 패턴이 현실적인 해법이다.
- **PointType에 `RESTORE` 추가** — 기존에는 `EARN`/`USE`만 있었는데, 보상 트랜잭션에서 포인트를 돌려주려면 `RESTORE`라는 별도 타입이 필요했다. 이력 추적과 감사를 위해 기존 USE를 취소하는 게 아니라 새로운 RESTORE 이력을 생성하는 방식을 택했다.

---

## 6. 비동기 콜백 + 수동 확인 — "콜백이 유실되면?"

### 무엇을 했는가

PG의 비동기 처리 모델에 맞춰 3가지 API를 설계했다.

| API | 용도 | 특징 |
|-----|------|------|
| `POST /payments` | 결제 요청 | Payment(REQUESTED) → PG 호출 → 상태 업데이트 |
| `POST /payments/callback` | PG 콜백 수신 | S2S 호출, `@LoginMember` 불필요 |
| `GET /payments/verify` | 수동 상태 확인 | 콜백 유실 시 PG에 직접 조회하여 보정 |

### 배운 점

- **콜백은 반드시 유실될 수 있다** — 네트워크 장애, 서버 재시작, PG 측 장애 등으로 콜백이 안 올 수 있다. verify API를 통해 PG에 직접 상태를 조회하고, `PENDING`/`UNKNOWN` 상태를 보정하는 구조가 필수적이다.
- **멱등성 설계** — `isFinalized()`로 이미 최종 상태인 결제에 대한 콜백은 무시한다. 같은 콜백이 두 번 오거나, 콜백과 verify가 동시에 호출되어도 안전하다.

---

## 7. OpenFeign 선택과 설정

### 무엇을 했는가

외부 PG 호출을 위한 HTTP 클라이언트로 OpenFeign을 선택하고, 타임아웃과 리트라이 설정을 분리했다.

### 선택 이유

| 비교 항목 | RestTemplate | OpenFeign |
|----------|-------------|-----------|
| 구현 방식 | URL, 헤더, HttpEntity 직접 작성 | 인터페이스 + 어노테이션 선언 |
| 보일러플레이트 | 많음 | 적음 (프록시 자동 생성) |
| Spring 지원 | maintenance mode | Spring Cloud 적극 지원 |
| Resilience4j 연동 | 수동 래핑 | 어노테이션 조합 용이 |

### 배운 점

- **PgFeignConfig를 `@Configuration`으로 등록하지 않은 이유** — `@Configuration`으로 만들면 모든 Feign 클라이언트에 적용된다. PG 전용 설정(타임아웃 1s/3s)이 다른 Feign 클라이언트에 영향을 주지 않도록 일반 클래스로 만들고 `@FeignClient(configuration = ...)`으로 지정했다.

---

## 8. 부하 테스트 — k6로 Resilience 전략 검증

### 무엇을 했는가

PG Simulator(성공률 60%, 지연 100ms~500ms, 처리 지연 1s~5s)와 연동된 결제 API에 k6 부하테스트를 수행하여 4가지 Resilience 전략의 동작을 검증했다.

### PG Simulator 설정

| 항목 | 값 |
|------|-----|
| 요청 성공 확률 | 60% |
| 처리 결과 - 성공 | 70% / 한도 초과 20% / 잘못된 카드 10% |
| 요청 지연 | 100ms ~ 500ms |
| 처리 지연 | 1s ~ 5s |

### 배운 점

- **실제 부하 상황에서 서킷이 OPEN되는 것을 눈으로 확인** — PG 실패율이 50%를 넘자 서킷이 OPEN되고, 이후 요청은 PG를 호출하지 않고 즉시 Fallback이 반환됐다. 10초 후 Half-Open에서 시험 요청을 보내는 것까지 확인.
- **Grafana 모니터링 연동** — Micrometer + Prometheus + Grafana로 서킷 브레이커 상태, 실패율, 응답 시간을 실시간 대시보드로 확인할 수 있었다.

---

## 9. 아키텍처 설계 — DIP와 레이어 분리

### 무엇을 했는가

`PaymentGateway` 인터페이스를 Domain 계층에 정의하고, 구현체(`Resilience4jPaymentGateway`, `ManualPaymentGateway`)를 Infrastructure 계층에 배치했다. V1/V2 전환이 코드 변경 없이 `@Qualifier`만으로 가능하도록 설계했다.

```
Application (Facade) → PaymentGateway (Domain 인터페이스)
                              ↑
Infrastructure (Resilience4jPaymentGateway / ManualPaymentGateway)
```

### 배운 점

- **DIP 덕분에 V1 → V2 전환이 간단했다** — Domain 계층의 `PaymentGateway` 인터페이스만 바라보니, 구현체를 바꿔도 Application/Domain 코드는 변경할 필요가 없었다.
- **Template Method 패턴 활용** — `AbstractPaymentFacade`에 공통 로직(processPayment, handleCallback, verifyPayment)을 두고, V1/V2가 PaymentGateway 구현체만 다르게 주입받는 구조로 코드 중복을 제거했다.

---

## 10. 이번 주를 돌아보며

### 가장 크게 배운 것

"외부 시스템 연동"은 기능 구현 자체보다 **실패 시나리오 대응**이 핵심이라는 것. PG가 죽으면, 느리면, 응답이 안 오면 — 이 세 가지 상황에 대한 답을 먼저 설계해야 했다.

### 수동 구현(V1) → 라이브러리(V2)의 학습 순서가 효과적이었다

처음부터 Resilience4j를 쓰면 "어노테이션 붙이면 되네"로 끝났을 것이다. 수동으로 슬라이딩 윈도우를 구현하고, 리트라이 루프를 짜보고, 상태 전이를 관리해본 뒤에 라이브러리로 전환하니 각 설정값이 어떤 동작으로 이어지는지 명확히 이해할 수 있었다.

### 트랜잭션 분리 설계의 중요성

단순히 `@Transactional`을 붙이는 것이 아니라, 외부 호출이 포함될 때 트랜잭션 경계를 어디에 두느냐가 시스템 전체의 안정성을 결정한다는 것을 깨달았다. DB 커넥션 풀 고갈, 롤백 불일치, 락 장기 보유 — 이런 문제는 기능 테스트로는 잡히지 않고, 부하 상황에서만 드러난다.

### 향후 개선 포인트

- 스케줄러 기반 미결제 건 자동 verify (PENDING/UNKNOWN 상태가 일정 시간 지속 시)
- Bulkhead 패턴으로 PG 호출 전용 스레드 풀 격리 강화
- Dead Letter Queue를 활용한 실패 콜백 재처리
- 결제 취소(환불) API 구현 및 보상 트랜잭션 확장
