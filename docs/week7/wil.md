# Week 7 — WIL (What I Learned)

## 이번 주 과제 목표

1. **ApplicationEvent로 경계 나누기** — 주요 로직과 부가 로직의 경계를 판단하고, 이벤트 기반으로 분리
2. **Kafka 이벤트 파이프라인 구축** — Transactional Outbox Pattern으로 At Least Once 발행 보장, Consumer 멱등 처리
3. **선착순 쿠폰 발급** — Kafka 기반 비동기 처리, 동시성 제어, Polling 기반 결과 확인

---

## 1. ApplicationEvent로 경계 나누기 — "이벤트로 분리할지 말지, 그 판단이 핵심"

### 무엇을 했는가

주문-결제 플로우에서 부가 로직(유저 행동 로깅, Kafka 발행)을 이벤트로 분리하고, 좋아요-집계 플로우에 eventual consistency를 적용했다. `@TransactionalEventListener`의 phase와 `fallbackExecution` 설정을 이벤트 특성에 따라 다르게 적용했다.

### 분리 판단 기준

| 구분 | 주요 로직 (동기, 같은 TX) | 부가 로직 (이벤트 분리) |
|------|-------------------------|----------------------|
| 주문-결제 | 주문 생성, 결제 처리, 보상 트랜잭션 | 유저 행동 로깅, Kafka 발행 |
| 좋아요 | `Like` 엔티티 상태 변경 | `likeCount` 집계 |
| 상품 조회 | 상품 데이터 반환 | 조회수 로깅 |

### 배운 점

- **"이걸 이벤트로 분리해야 하는가?"라는 질문 자체가 학습 포인트였다.** 보상 트랜잭션(`OrderCompensationService`)은 결제 실패 시 재고/쿠폰/포인트 원복이 반드시 필요하므로 이벤트로 분리하지 않았다. 무조건 이벤트 분리가 아니라, "이 부가 로직이 실패해도 주요 로직이 성공이어야 하는가?"를 기준으로 판단해야 한다.
- **`fallbackExecution` 설정을 일괄 적용하지 않은 이유** — 좋아요는 반드시 `@Transactional` 안에서만 발행되어야 하는 이벤트다. `fallbackExecution=false`로 두면 트랜잭션 없이 발행되는 버그를 즉시 감지할 수 있다. 반면 결제 후 이벤트는 PG 호출이 트랜잭션 밖에서 일어나므로 `fallbackExecution=true`가 필요하다. 설정값 하나가 "이 이벤트의 발행 컨텍스트"를 명시하는 안전장치 역할을 한다.

---

## 2. @TransactionalEventListener의 함정들 — "테스트로 증명하고, 판단을 기록하다"

### 무엇을 했는가

`@TransactionalEventListener(AFTER_COMMIT)`의 실제 동작을 6개의 테스트로 증명했다. 문서만 봐서는 알 수 없는 5가지 함정을 발견하고, 각각에 대해 어떤 전략을 선택했는지 판단 근거를 남겼다.

### 핵심 발견

| 함정 | 현상 | 선택한 전략 |
|------|------|------------|
| AFTER_COMMIT은 비동기가 아니다 | 같은 스레드에서 동기 실행, 리스너 지연이 API 응답에 그대로 반영 | Kafka 발행 리스너에 `@Async` 적용 |
| 리스너 예외 시 DB는 커밋, 클라이언트는 500 | 이미 커밋된 데이터와 에러 응답의 불일치 | 집계 리스너는 try-catch로 예외 삼킴 |
| 트랜잭션 없이 발행하면 조용히 무시 | 에러 로그 없이 리스너가 안 돌아감 | `fallbackExecution` 선택적 적용 |
| AFTER_COMMIT에서 DB 쓰기 시 REQUIRES_NEW 필수 | 기존 TX가 이미 닫혔으므로 새 TX 없이는 DB 쓰기 불가 | `LikeEventListener`에 `REQUIRES_NEW` 적용 |
| 이벤트는 유실되며 복구할 수 없다 | 리스너 실패 시 재시도 메커니즘 없음 | 유실 불허 이벤트에만 Outbox Pattern 적용 |

### 배운 점

- **"커밋 후 실행"이라는 단어만 보고 비동기라고 착각하기 쉽다.** 내부적으로 `TransactionSynchronizationManager`가 ThreadLocal로 콜백을 관리하고, 커밋 후 같은 스레드에서 `triggerAfterCommit()`을 호출하는 구조다. 이걸 이해하고 나니 모든 함정이 한 번에 설명됐다.
- **모든 함정에 대한 "정답"이 아니라 "트레이드오프"가 있었다.** try-catch로 삼키면 집계 누락을 허용하게 되고, `@Async`를 붙이면 실패 감지가 어려워진다. 각 선택에서 무엇을 포기했는지 명시적으로 기록해두니, 나중에 왜 이렇게 했는지 돌아볼 수 있었다.

---

## 3. Transactional Outbox Pattern — "유실을 허용할 수 없는 이벤트만 선별하다"

### 무엇을 했는가

모든 이벤트에 Outbox를 적용하지 않고, 유실이 허용되지 않는 **쿠폰 발급 요청**에만 Outbox Pattern을 적용했다. 통계성 이벤트(catalog/order)는 `KafkaEventPublishListener`에서 AFTER_COMMIT 직접 발행으로 처리했다.

### 이벤트별 발행 전략

| 이벤트 | 발행 방식 | 이유 |
|--------|----------|------|
| `ProductLikedEvent`, `ProductViewedEvent` | AFTER_COMMIT 직접 Kafka 발행 | 통계성, 유실 허용 |
| `OrderPaidEvent` | AFTER_COMMIT 직접 Kafka 발행 | 통계성, 유실 허용 |
| 쿠폰 발급 요청 | Outbox Pattern (같은 TX에 INSERT + 커밋 후 발행 + 스케줄러 재시도) | 유실 불허 |

### Outbox 구조

```
[같은 TX] MemberCoupon INSERT (REQUESTED) + OutboxEvent INSERT
    → 커밋 후: OutboxEventListener가 즉시 Kafka 발행
    → 실패 시: OutboxScheduler(10초 폴링)가 미발행 건 재시도
```

### 배운 점

- **"왜 모든 이벤트에 Outbox를 안 쓰는가?"에 대한 답** — Outbox는 비즈니스 TX마다 DB에 추가 INSERT가 발생한다. 좋아요 집계처럼 유실돼도 배치로 보정 가능한 통계성 이벤트까지 Outbox로 처리하면 DB 쓰기 부하만 늘어난다. "이 이벤트가 유실되면 비즈니스에 어떤 영향이 있는가?"를 기준으로 선별하는 것이 핵심이다.
- **Outbox 없이 Kafka만 쓰면 생기는 문제** — DB 커밋은 됐는데 Kafka 발행이 실패하면 이벤트가 유실된다. 반대로 Kafka는 발행됐는데 DB가 롤백되면 존재하지 않는 데이터에 대한 이벤트가 전파된다. 같은 DB 트랜잭션에 이벤트를 기록하는 Outbox만이 원자성을 보장할 수 있다.

---

## 4. Kafka Producer / Consumer 파이프라인 — "순서 보장과 멱등 처리"

### 무엇을 했는가

`commerce-api` -> Kafka -> `commerce-streamer` 구조로 3개 토픽(`catalog-events`, `order-events`, `coupon-issue-requests`)을 설계하고, Consumer에서 `event_handled` 테이블 기반 멱등 처리를 구현했다.

### Producer 설정

| 설정 | 값 | 이유 |
|------|-----|------|
| `acks` | `all` | 모든 ISR 복제본에 기록 확인 후 응답 |
| `enable.idempotence` | `true` | Producer 재시도 시 중복 메시지 방지 |
| `retries` | `3` | 네트워크 일시 장애 대응 |

### Consumer 처리 전략

- **Manual ACK** — 자동 커밋을 끄고(`enable-auto-commit=false`, `ack-mode=manual`), 비즈니스 로직 처리 후 수동으로 ACK
- **멱등 처리** — `event_handled` 테이블에 `event_id`를 PK로 INSERT. `DataIntegrityViolationException` 발생 시 중복 이벤트로 간주하여 ACK 전송
- **Partition Key 기반 순서 보장** — `catalog-events`는 productId, `order-events`는 orderId, `coupon-issue-requests`는 couponId를 키로 사용하여 같은 엔티티에 대한 이벤트가 동일 파티션에서 순차 처리

### 배운 점

- **`event_handled` 테이블과 로그 테이블을 분리하는 이유** — `event_handled`는 멱등 판단을 위한 최소한의 테이블(event_id PK)이고, 로그는 감사/디버깅용이다. 한 테이블에 합치면 로그 데이터 증가가 멱등 조회 성능에 영향을 줄 수 있다.
- **Manual ACK의 위치가 중요하다** — TX 커밋이 완료된 후에 ACK를 보내야 한다. TX 커밋 전에 ACK를 보내면 Consumer가 죽었을 때 처리됐다고 판단하지만 실제로는 커밋이 안 된 상태가 될 수 있다.

---

## 5. 선착순 쿠폰 발급 — "API는 접수만, Consumer가 처리"

### 무엇을 했는가

API는 발급 요청을 수신하고 즉시 202 ACCEPTED를 반환하며, Kafka Consumer가 비동기로 수량 체크 후 승인/거절을 처리하는 구조를 구현했다. Polling API로 발급 결과를 확인할 수 있도록 했다.

### 동시성 제어 전략

- **Partition Key = couponId** — 같은 쿠폰에 대한 모든 발급 요청이 동일 파티션에서 순차 처리되므로, Consumer 단에서 race condition이 발생하지 않는다.
- **중복 요청 방어** — API 레벨 조회 + DB UK(member_id, coupon_id) + Consumer `event_handled` 멱등 처리의 3중 방어

### MemberCoupon 상태 흐름

```
요청 접수: REQUESTED → (Consumer 처리) → AVAILABLE (승인) 또는 FAILED (수량 초과 등)
재요청:   FAILED → retryRequest() → REQUESTED → (Consumer 재처리)
```

### 배운 점

- **Redis vs Kafka — 선착순 쿠폰에서의 차이** — Redis(`INCR` + `DECR`)는 즉시 응답이 가능하지만 단일 장애점이 될 수 있고, 발급 로직이 API 서버에 남는다. Kafka는 비동기라 즉시 결과를 줄 수 없지만, Consumer가 순차 처리하므로 동시성 제어가 자연스럽고, Consumer 장애 시에도 메시지가 유실되지 않는다. 요구사항에 따라 "즉시 응답이 필요한가?"가 선택 기준이 된다.
- **`retryRequest()`로 기존 레코드 재사용** — FAILED 상태인 MemberCoupon을 다시 REQUESTED로 전환하여 UK 위반 없이 재시도가 가능하도록 했다. 새 레코드를 만들면 UK(member_id, coupon_id) 제약에 걸리기 때문이다.
- **100장 한정 쿠폰에 1만 명이 동시에 요청하면?** — Kafka에 요청이 쌓이고, Consumer가 순차적으로 처리하면서 101번째부터는 FAILED로 거절된다. API는 모두 202를 받으므로 서버 부하가 급증하지 않는다. 동시성 테스트로 수량 초과가 발생하지 않는 것을 검증했다.

---

## 6. 아키텍처 설계 — commerce-api와 commerce-streamer의 역할 분리

### 무엇을 했는가

`commerce-api`는 Producer 역할로 이벤트를 발행하고, `commerce-streamer`는 Consumer 역할로 집계와 쿠폰 발급을 처리하는 구조로 분리했다.

```
commerce-api (Producer)           Kafka              commerce-streamer (Consumer)
├─ catalog-events    ──────>  catalog-events  ──────>  CatalogEventConsumer (집계)
├─ order-events      ──────>  order-events    ──────>  OrderEventConsumer (집계)
└─ coupon-issue-req  ──────>  coupon-issue-   ──────>  CouponIssueConsumer (발급)
                               requests
```

### 배운 점

- **ApplicationEvent만으로 충분한 경계 vs Kafka가 필요한 경계** — 같은 프로세스 안에서 부가 로직을 분리하는 것은 ApplicationEvent로 충분하다. 하지만 다른 애플리케이션(commerce-streamer)이 이벤트를 소비해야 하거나, 서버가 죽어도 이벤트가 유실되면 안 되는 경우에는 Kafka가 필요하다. "시스템 간 경계"와 "내구성(durability)"이 판단 기준이다.
- **Consumer Group 분리의 의미** — 같은 토픽이라도 Consumer Group이 다르면 각각 독립적으로 메시지를 소비한다. 집계와 알림이 같은 이벤트를 구독하더라도 서로 영향 없이 처리할 수 있다.

---

## 7. 이번 주를 돌아보며

### 가장 크게 배운 것

"이벤트로 분리하면 끝"이 아니라는 것. `@TransactionalEventListener`의 실제 동작을 테스트로 증명해보니, 비동기가 아니라는 것, 리스너 예외가 호출자에게 전파된다는 것, 이벤트가 유실되면 복구할 수 없다는 것을 알게 됐다. 각 함정에 대해 "어떤 트레이드오프를 선택했는가"를 기록한 것이 이번 주의 핵심이다.

### ApplicationEvent -> Kafka -> Outbox의 계단식 확장

처음에는 ApplicationEvent로 프로세스 내부의 경계를 나눈다. 그다음 시스템 간 전파가 필요한 이벤트를 Kafka로 확장한다. 마지막으로 유실이 허용되지 않는 이벤트에만 Outbox Pattern을 적용한다. 모든 이벤트에 동일한 전략을 쓰는 게 아니라, 이벤트의 중요도와 특성에 따라 전략을 선택하는 판단력이 중요하다는 것을 배웠다.

### 트레이드오프를 명시적으로 기록하는 습관

이번 주는 코드를 쓰는 것만큼 "왜 이렇게 했는가"를 기록하는 데 시간을 들였다. try-catch로 예외를 삼킬 때 무엇을 포기하는지, `@Async`를 붙일 때 무엇을 잃는지, Outbox를 모든 이벤트에 적용하지 않는 이유가 무엇인지. 이런 판단 근거가 없으면 나중에 코드만 보고는 "왜 이렇게 됐지?"를 알 수 없다.

### 향후 개선 포인트

- DLQ(Dead Letter Queue) 구성으로 Consumer 처리 실패 이벤트 격리 및 재처리
- Consumer 배치 처리로 집계 성능 최적화
- 쿠폰 발급 결과 실시간 알림 (SSE 또는 WebSocket) 도입 검토
- Outbox 테이블 파티셔닝 또는 TTL 기반 정리 전략
