# 📝 Round 7 Quests

---

## 💻 Implementation Quest

> 이벤트 기반 아키텍처의 **Why → How → Scale** 을 한 주에 관통합니다.
Spring `ApplicationEvent`로 **경계를 나누는 감각**을 익히고,
Kafka로 **이벤트 파이프라인**을 구축한 뒤, **선착순 쿠폰 발급**에 적용합니다
>

<aside>
🎯

**Must-Have (이번 주에 무조건 가져가야 좋을 것-**무조건 ****하세요**)**

- Event vs Command
- Application Event
- Kafka Producer / Consumer 기본 파이프라인
- Transactional Outbox Pattern
- Kafka 기반 선착순 쿠폰 발급

**Nice-To-Have (부가적으로 가져가면 좋을 것-**시간이 ****허락하면 ****꼭 ****해보세요**)**

- Consumer Group 분리를 통한 관심사별 처리
- Consumer 배치 처리
- DLQ 구성
</aside>

### 📋 과제 정보

**Step 1 — ApplicationEvent로 경계 나누기**

- **무조건 이벤트 분리**가 아니라, 주요 로직과 부가 로직의 경계를 판단한다.
- 주문–결제 플로우에서 부가 로직(유저 행동 로깅, 알림 등)을 이벤트로 분리한다.
- 좋아요–집계 플로우에서 eventual consistency를 적용한다.
- 트랜잭션 결과와의 상관관계에 따라 적절한 리스너(`@TransactionalEventListener` phase 등)를 활용한다.
- "이걸 이벤트로 분리해야 하는가?" 에 대한 **판단 기준** 자체가 학습 포인트다.

**Step 2 — Kafka 이벤트 파이프라인**

- `commerce-api` → Kafka → `commerce-collector` 구조로 확장한다.
- Step 1에서 분리한 이벤트 중, **시스템 간 전파가 필요한 것**을 Kafka로 발행한다.
- Producer는 **Transactional Outbox Pattern**으로 At Least Once 발행을 보장한다.
- Consumer는 이벤트를 수취해 집계(좋아요 수 / 판매량 / 조회 수)를 `product_metrics`에 upsert한다.

**Step 3 — Kafka 기반 선착순 쿠폰 발급**

- Step 2에서 익힌 Kafka를 **실전 시나리오**에 적용한다.
- API는 발급 요청을 Kafka에 발행만 하고, Consumer가 실제 쿠폰을 발급한다.
- 발급 수량 제한(e.g. 선착순 100명)에 대한 **동시성 제어**를 구현한다.

**토픽 설계** (예시)

- `catalog-events` (상품/재고/좋아요 이벤트, key=productId)
- `order-events` (주문/결제 이벤트, key=orderId)
- `coupon-issue-requests` (쿠폰 발급 요청, key=couponId)

**Producer, Consumer 필수 처리**

- **Producer**
    - acks=all, idempotence=true 설정
- **Consumer**
    - **manual Ack** 처리
    - `event_handled(event_id PK)` (DB or Redis) 기반의 멱등 처리
    - `version` 또는 `updated_at` 기준으로 최신 이벤트만 반영

> *왜 이벤트 핸들링 테이블과 로그 테이블을 분리하는 걸까? 에 대해 고민해보자*
>

---

## ✅ Checklist

### 🧾 Step 1 — ApplicationEvent

- [x]  주문–결제 플로우에서 부가 로직을 이벤트 기반으로 분리한다.
- [x]  좋아요 처리와 집계를 이벤트 기반으로 분리한다. (집계 실패와 무관하게 좋아요는 성공)
- [x]  유저 행동(조회, 클릭, 좋아요, 주문 등)에 대한 서버 레벨 로깅을 이벤트로 처리한다.
- [x]  동작의 주체를 적절하게 분리하고, 트랜잭션 간의 연관관계를 고민해 봅니다.

### 🎾 Step 2 — Kafka Producer / Consumer

- [x]  Step 1의 ApplicationEvent 중 **시스템 간 전파가 필요한 이벤트**를 Kafka로 발행한다.
- [x]  `acks=all`, `idempotence=true` 설정
- [x]  **Transactional Outbox Pattern** 구현
- [x]  PartitionKey 기반 이벤트 순서 보장
- [x]  Consumer가 Metrics 집계 처리 (product_metrics upsert)
- [x]  `event_handled` 테이블을 통한 멱등 처리 구현
- [x]  manual Ack + `version`/`updated_at` 기준 최신 이벤트만 반영

### 🎫 Step 3 — 선착순 쿠폰 발급

- [x]  쿠폰 발급 요청 API → Kafka 발행 (비동기 처리)
- [x]  Consumer에서 선착순 수량 제한 + 중복 발급 방지 구현
- [x]  발급 완료/실패 결과를 유저가 확인할 수 있는 구조 설계 (polling or callback)
- [x]  동시성 테스트 — 수량 초과 발급이 발생하지 않는지 검증

---

## ✍️ Technical Writing Quest

> 이번 주에 학습한 내용, 과제 진행을 되돌아보며
**"내가 어떤 판단을 하고 왜 그렇게 구현했는지"** 를 글로 정리해봅니다.
>
>
> **좋은 블로그 글은 내가 겪은 문제를, 타인도 공감할 수 있게 정리한 글입니다.**
>
> 이 글은 단순 과제가 아니라, **향후 이직에 도움이 될 수 있는 포트폴리오** 가 될 수 있어요.
>

### 📚 Technical Writing Guide

### ✅ 작성 기준

| 항목 | 설명 |
| --- | --- |
| **형식** | 블로그 |
| **길이** | 제한 없음, 단 꼭 **1줄 요약 (TL;DR)** 을 포함해 주세요 |
| **포인트** | “무엇을 했다” 보다 **“왜 그렇게 판단했는가”** 중심 |
| **예시 포함** | 코드 비교, 흐름도, 리팩토링 전후 예시 등 자유롭게 |
| **톤** | 실력은 보이지만, 자만하지 않고, **고민이 읽히는 글**예: “처음엔 mock으로 충분하다고 생각했지만, 나중에 fake로 교체하게 된 이유는…” |

---

### ✨ 좋은 톤은 이런 느낌이에요

> 내가 겪은 실전적 고민을 다른 개발자도 공감할 수 있게 풀어내자
>

| 특징 | 예시 |
| --- | --- |
| 🤔 내 언어로 설명한 개념 | Stub과 Mock의 차이를 이번 주문 테스트에서 처음 실감했다 |
| 💭 판단 흐름이 드러나는 글 | 처음엔 도메인을 나누지 않았는데, 테스트가 어려워지며 분리했다 |
| 📐 정보 나열보다 인사이트 중심 | 테스트는 작성했지만, 구조는 만족스럽지 않다. 다음엔… |

### ❌ 피해야 할 스타일

| 예시 | 이유 |
| --- | --- |
| 많이 부족했고, 반성합니다… | 회고가 아니라 일기처럼 보입니다 |
| Stub은 응답을 지정하고… | 내 생각이 아닌 요약문처럼 보입니다 |
| 테스트가 진리다 | 너무 단정적이거나 오만해 보입니다 |

### 🎯 Feature Suggestions

- ApplicationEvent만으로 충분한 경계 vs Kafka가 필요한 경계, 그 기준은?
- 트랜잭션 안에 다 넣을 수 있지만, 굳이 나누는 이유
- 좋아요는 동기, 집계는 비동기 — 상품의 좋아요 수가 바로 반영되어야 할까?
- Outbox Pattern 없이 Kafka만 쓰면 어떤 일이 벌어질까?
- 선착순 쿠폰을 Redis로 처리하는 것과 Kafka로 처리하는 것의 차이
- 100장 한정 쿠폰에 1만 명이 동시에 요청하면?
- 멱등 처리를 DB로 할 때와 Redis로 할 때의 트레이드오프

---

## 구현 완료 요약

### 전체 아키텍처

```
┌─ commerce-api (Producer) ────────────────────────────────────────────────┐
│                                                                           │
│  Step 1: ApplicationEvent 내부 이벤트 분리                                 │
│  ├─ OrderPaidEvent / OrderFailedEvent                                    │
│  ├─ ProductLikedEvent / ProductViewedEvent                               │
│  └─ Listeners: OrderEventListener, LikeEventListener,                   │
│                UserActionEventListener                                   │
│                                                                           │
│  Step 2: Kafka 직접 발행 + Transactional Outbox Pattern (쿠폰만)          │
│  ├─ KafkaEventPublishListener (AFTER_COMMIT에서 catalog/order 직접 발행)  │
│  ├─ OutboxEvent 엔티티 (DB에 이벤트 저장, 쿠폰 발급 요청 전용)              │
│  ├─ OutboxEventListener (Published 이벤트 처리, AFTER_COMMIT 즉시 발행)   │
│  ├─ OutboxScheduler (10초 폴링, LIMIT 100, 쿠폰 재시도 안전망)             │
│  └─ OrderTransactionService (Outbox 없는 순수 TX 래핑)                   │
│                                                                           │
│  Step 3: 선착순 쿠폰 발급 요청 API                                        │
│  ├─ POST /api/v1/coupons/{couponId}/issue-request → 202 ACCEPTED        │
│  └─ GET  /api/v1/coupons/{couponId}/issue-status  → Polling 결과 확인    │
│                                                                           │
└───────────────┬───────────────┬───────────────┬──────────────────────────┘
                │               │               │
         catalog-events   order-events   coupon-issue-requests
                │               │               │
         ┌──────▼───────────────▼───────────────▼──────────────────────────┐
         │                        Kafka                                     │
         └──────┬───────────────┬───────────────┬──────────────────────────┘
                │               │               │
┌─ commerce-streamer (Consumer) ┼───────────────┼──────────────────────────┐
│               │               │               │                           │
│  CatalogEventConsumer    OrderEventConsumer   CouponIssueConsumer        │
│  ├─ like_count 집계      ├─ order_count 집계  ├─ 수량 체크               │
│  └─ view_count 집계      └─ (per product)     ├─ REQUESTED → AVAILABLE   │
│                                                └─ REQUESTED → FAILED     │
│                                                                           │
│  공통: event_handled 테이블 멱등 처리 + Manual ACK                        │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

### Step 1 — ApplicationEvent로 경계 나누기

주요 로직과 부가 로직의 경계를 판단하여, 부가 로직(로깅, 집계)을 이벤트 기반으로 분리했다.

| 이벤트 | 발행 시점 | 리스너 | 전략 |
|--------|----------|--------|------|
| `OrderPaidEvent` | 결제 성공 (전액 할인 / PG 결제) | `OrderEventListener`, `UserActionEventListener` | `AFTER_COMMIT`, `fallbackExecution=true` |
| `OrderFailedEvent` | 결제 실패 | `OrderEventListener`, `UserActionEventListener` | `AFTER_COMMIT`, `fallbackExecution=true` |
| `ProductLikedEvent` | 좋아요/취소 | `LikeEventListener`, `UserActionEventListener` | `AFTER_COMMIT` + `REQUIRES_NEW` (집계 실패해도 좋아요는 성공) |
| `ProductViewedEvent` | 상품 조회 | `UserActionEventListener` | `AFTER_COMMIT`, `fallbackExecution=true` |

### Step 2 — Kafka 직접 발행 + Transactional Outbox Pattern (쿠폰만)

통계성 이벤트(catalog/order)는 유실을 허용하여 `@TransactionalEventListener(AFTER_COMMIT)`에서 `KafkaEventPublishListener`가 직접 발행한다. 유실이 허용되지 않는 쿠폰 발급 요청만 Outbox Pattern을 적용한다.

**이벤트별 발행 방식**:

| 이벤트 | 발행 방식 | 이유 |
|--------|----------|------|
| `ProductLikedEvent`, `ProductViewedEvent` | `KafkaEventPublishListener` — AFTER_COMMIT 직접 발행 | 통계성, 유실 허용 |
| `OrderPaidEvent` | `KafkaEventPublishListener` — AFTER_COMMIT 직접 발행 | 통계성, 유실 허용 |
| 쿠폰 발급 요청 | Outbox Pattern — MemberCoupon INSERT + OutboxEvent INSERT 같은 TX, 커밋 후 즉시 발행 + 스케줄러 재시도 | 유실 불허 |

**Kafka 토픽 설계**:

| 토픽 | Partition Key | 발행 이벤트 | Consumer 처리 |
|------|-------------|-----------|-------------|
| `catalog-events` | productId | `ProductLikedEvent`, `ProductViewedEvent` | like_count, view_count 집계 |
| `order-events` | orderId | `OrderPaidEvent` | order_count 집계 (상품별) |
| `coupon-issue-requests` | couponId | 쿠폰 발급 요청 | 선착순 수량 체크 + 승인/거절 |

**Producer 설정**: `acks=all`, `enable.idempotence=true`, `retries=3`

**Consumer 설정**: `value-deserializer=ByteArrayDeserializer`, `enable-auto-commit=false`, `ack-mode=manual`

**Consumer 처리**: Manual ACK, `event_handled` 테이블(event_id PK)로 멱등 처리, TX는 Service 레이어에 위임 (ACK는 TX 커밋 후).
`DataIntegrityViolationException`(event_handled UNIQUE 위반) 발생 시 중복 이벤트로 간주하여 ACK 전송 (불필요한 재전달 방지)

### Step 3 — Kafka 기반 선착순 쿠폰 발급

API는 발급 요청을 수신하고 즉시 202 ACCEPTED를 반환. Consumer가 비동기로 수량 체크 후 승인/거절 처리.

**동시성 제어**: Partition Key = couponId → 같은 쿠폰 요청은 같은 파티션에서 순차 처리 (race condition 방지)

**상태 흐름**:
```
요청 접수: REQUESTED → (Consumer 처리) → AVAILABLE (승인) 또는 FAILED (수량 초과 등)
재요청:   FAILED → retryRequest() → REQUESTED → (Consumer 재처리)
```

**MemberCoupon 엔티티 확장**:
- 신규 상태: `REQUESTED` (비동기 처리 대기), `FAILED` (발급 거절)
- 신규 필드: `failReason` (거절 사유)
- 정적 팩토리 메서드: `createRequested()`, 상태 전이: `approve()`, `reject(reason)`, `retryRequest()`
- `retryRequest()`: FAILED → REQUESTED 전환, failReason 초기화 (UK 위반 없이 기존 레코드 재사용)

**중복 요청 방어**: API 레벨 조회 + DB UK(member_id, coupon_id) + Consumer `event_handled` 멱등 처리

### 구현 파일 목록

#### commerce-api 신규 파일

| 레이어 | 파일 | 역할 |
|--------|------|------|
| domain/event | `OrderPaidEvent`, `OrderFailedEvent`, `ProductLikedEvent`, `ProductViewedEvent` | 도메인 이벤트 record |
| domain/outbox | `OutboxEvent`, `OutboxEventRepository`, `OutboxEventPublisher` | Outbox 엔티티 + DIP 인터페이스 (쿠폰 전용) |
| domain/coupon | `CouponIssueService` | 선착순 발급 요청 도메인 로직 (FAILED 재요청 시 기존 레코드 재사용) |
| application/event | `OrderEventListener`, `LikeEventListener`, `UserActionEventListener`, `KafkaEventPublishListener`, `OutboxEventListener` | 이벤트 리스너 (KafkaEventPublishListener는 null memberId에 대해 Optional 기반 null-safety 적용) |
| application/order | `OrderTransactionService` | Outbox 없는 순수 TX 래핑 (결제 성공/전액 할인 경로) |
| application/outbox | `OutboxScheduler` | 미발행 Outbox 폴링 발행 (10초, 쿠폰 재시도 안전망) |
| application/coupon | `CouponIssueFacade`, `CouponIssueStatusInfo` | 선착순 쿠폰 Facade + 응답 DTO |
| infrastructure/outbox | `KafkaOutboxEventPublisher`, `OutboxEventJpaRepository`, `OutboxEventRepositoryImpl` | Outbox 인프라 구현체 |
| interfaces/api/coupon | `CouponIssueV1Controller`, `CouponIssueV1Dto` | 선착순 쿠폰 API |

#### commerce-api 수정 파일

| 파일 | 변경 내용 |
|------|----------|
| `CommerceApiApplication` | `@EnableAsync`, `@EnableScheduling` 추가 |
| `build.gradle.kts` | `modules:kafka` 의존성 추가 |
| `OrderPaymentFacade` | `OrderTransactionService` 사용, `OrderPaidEvent` 발행으로 Kafka 위임. TX 밖 이벤트 발행에 대한 주의사항 주석 추가 |
| `ProductFacade` | Outbox 관련 코드 제거, ObjectMapper 의존 제거. `ProductLikedEvent`/`ProductViewedEvent` 발행만 수행 |
| `Coupon` | `isLimitedIssue()` 메서드 추가 (maxIssueCount > 0) |
| `MemberCoupon` | `REQUESTED`/`FAILED` 상태, `failReason` 필드, `createRequested()`/`approve()`/`reject()`/`retryRequest()` 추가 |
| `MemberCouponStatus` | `REQUESTED`, `FAILED` 상태 추가 |
| `MemberCouponRepository` | `findByMemberIdAndCouponIdIncludingDeleted()` 쿼리 추가 |

#### commerce-streamer 신규 파일

| 레이어 | 파일 | 역할 |
|--------|------|------|
| domain/event | `EventHandled`, `EventHandledRepository` | 멱등 처리 엔티티 |
| domain/metrics | `ProductMetrics`, `ProductMetricsRepository` | 상품 메트릭 집계 엔티티 |
| domain/coupon | `Coupon`, `MemberCoupon` (복제) | 같은 DB 공유, 쿠폰 발급 처리용 |
| application/metrics | `MetricsEventService` | 집계 비즈니스 로직 (TX 관리) |
| application/coupon | `CouponIssueEventService` | 선착순 발급 승인/거절 로직 |
| interfaces/consumer | `CatalogEventConsumer`, `OrderEventConsumer`, `CouponIssueConsumer` | Kafka Consumer 3개 |
| infrastructure | Repository 구현체들 | JPA 구현 |

### 테스트 현황

| 유형 | 파일 수 | 메서드 수 | 주요 검증 대상 |
|------|---------|----------|--------------|
| Unit (이벤트 리스너) | 3 | 11 | 리스너 동작, 예외 격리 |
| Unit (Domain/Application) | 6+ | 30+ | 쿠폰 발급, Facade, Outbox 로직 |
| Integration (Testcontainers) | 4 | 22 | DB 연동, UK 제약, 멱등 처리 |
| 동시성 | 2 | 5+ | 같은 멤버 중복 요청, 수량 초과 방지 |
| E2E | 1 | 6 | API 전체 흐름 (202, 409, 401 응답) |

---

## 구현 계획

### Step 1 — ApplicationEvent로 경계 나누기

**판단 기준**: 주요 로직(트랜잭션 성공이 필수)과 부가 로직(실패해도 주요 로직에 영향 없음)을 분리

#### 분리 판단 기준표

| 구분 | 주요 로직 (동기, 같은 TX) | 부가 로직 (이벤트 분리) |
|------|-------------------------|----------------------|
| 주문-결제 | 주문 생성, 결제 처리, 보상 트랜잭션 | 유저 행동 로깅, 알림, Kafka 발행(Step2) |
| 좋아요 | `Like` 엔티티 상태 변경 (Y/N) | `likeCount` 집계 (eventual consistency) |
| 상품 조회 | 상품 데이터 반환 | 조회수 로깅 |

> **보상 트랜잭션(`OrderCompensationService`)은 이벤트로 분리하지 않는다.**
> 결제 실패 시 재고/쿠폰/포인트 원복은 주요 로직이므로 동기적으로 유지한다.

#### 1-1. 이벤트 클래스 정의

모두 Java `record`로 정의 (POJO, Spring 비종속)

```
com.loopers.domain.event/
├── OrderPaidEvent        (orderId, memberId, totalAmount, List<OrderedProduct>)
├── OrderFailedEvent      (orderId, memberId, reason)
├── ProductLikedEvent     (productId, memberId, liked: boolean)
├── ProductViewedEvent    (productId, memberId)
```

#### 1-2. 주문-결제 플로우 이벤트 분리

**현재 코드** (`OrderPaymentFacade.processPayment()`):
- 결제 성공/실패 후 모든 후속 처리를 `OrderPaymentFacade` 내에서 직접 수행
- 보상 트랜잭션도 동기적으로 호출

**변경**:
- `OrderPaymentFacade`에 `ApplicationEventPublisher` 주입
- 결제 성공 시 → `OrderPaidEvent` 발행
- 결제 실패 시 → `OrderFailedEvent` 발행
- `@TransactionalEventListener(phase = AFTER_COMMIT)` 으로 부가 로직 처리
  - 유저 행동 로깅 (도메인 이벤트를 로깅 리스너가 직접 구독)
  - 알림 발송 (향후 확장)
  - Kafka 이벤트 발행 (Step 2 연결)

#### 1-2-1. `@EventListener` vs `@TransactionalEventListener`

**`@TransactionalEventListener`가 등장한 이유**

`@EventListener`만 있던 시절의 문제를 보면 이해가 쉽다.

```java
@Transactional
public void createOrder(OrderCommand cmd) {
    Order order = orderRepository.save(new Order(cmd));   // ① INSERT
    eventPublisher.publishEvent(new OrderCreatedEvent(order.getId()));  // ②
    // ... 나머지 로직 ③
}

@EventListener
public void sendNotification(OrderCreatedEvent event) {
    notificationService.send(event.getOrderId());  // ④ 알림 발송!
}
```

```
시간축 ──────────────────────────────────────────────────►

  ① INSERT (아직 커밋 안 됨)
  │
  ② publishEvent() 호출
  │
  ④ @EventListener 즉시 실행 → 알림 발송!!
  │    └─ 문제: DB에는 아직 주문이 커밋되지 않은 상태
  │             수신자가 주문을 조회하면? → 없음 ❌
  │
  ③ 나머지 로직 실행
  │
  ⑤ 트랜잭션 커밋 (또는 ③에서 예외 → 롤백!)
       └─ 롤백되면? → 알림은 이미 발송됨 ❌❌
```

핵심 문제 2가지:
1. **리스너 실행 시점에 데이터가 아직 커밋되지 않음** → 다른 트랜잭션에서 조회 불가
2. **트랜잭션이 롤백되어도 리스너는 이미 실행됨** → 존재하지 않는 주문에 대한 알림 발송

이 문제를 해결하기 위해 Spring 4.2에서 `@TransactionalEventListener`가 도입되었다.

**동작 방식 비교**

```
                    @EventListener                    @TransactionalEventListener
                    ──────────────                    ───────────────────────────

publishEvent() ──► 즉시 실행                          콜백만 등록 (실행 안 함)
                    │                                        │
                    ▼                                        │ (대기)
               리스너 로직 수행                                  │
                    │                                        │
                    ▼                                        │
              나머지 비즈니스 로직                                │
                    │                                        │
                    ▼                                        ▼
              트랜잭션 커밋                              트랜잭션 커밋
                                                            │
                                                            ▼
                                                      리스너 로직 수행 (AFTER_COMMIT)
```

**특성 비교**

| 항목 | `@EventListener` | `@TransactionalEventListener` |
|------|-------------------|-------------------------------|
| **실행 시점** | `publishEvent()` 호출 즉시 | 트랜잭션 phase에 따라 (기본: 커밋 후) |
| **트랜잭션 없을 때** | 항상 실행 | 실행 안 됨 (`fallbackExecution=true`면 실행) |
| **같은 트랜잭션 참여** | O (호출자의 트랜잭션 안에서 실행) | X (커밋 후 실행이므로 트랜잭션 이미 종료) |
| **리스너 예외 시** | 호출자 트랜잭션 롤백 | 호출자 트랜잭션에 영향 없음 (이미 커밋됨) |
| **리스너에서 DB 쓰기** | 현재 트랜잭션에 포함됨 | 새 트랜잭션 필요 (`REQUIRES_NEW`) |
| **데이터 가시성** | 커밋 전 → 다른 TX에서 못 봄 | 커밋 후 → 다른 TX에서 볼 수 있음 |

**언제 어떤 것을 선택하는가?**

`@EventListener` — 리스너의 성공/실패가 호출자의 트랜잭션에 영향을 줘야 할 때:

```java
// 주문 생성 시 재고 차감은 반드시 같은 트랜잭션에서 성공해야 한다
@EventListener
public void deductStock(OrderCreatedEvent event) {
    stockService.deduct(event.getProductId(), event.getQuantity());
    // 재고 부족 → 예외 → 주문 트랜잭션도 롤백
}
```

`@TransactionalEventListener` — 주요 트랜잭션은 보호하면서 부가 로직을 안전하게 실행할 때:

```java
// 주문은 성공해야 하고, 알림 실패가 주문을 롤백시키면 안 된다
@TransactionalEventListener(phase = AFTER_COMMIT)
public void sendNotification(OrderCreatedEvent event) {
    notificationService.send(event.getOrderId());
    // 실패해도 주문은 이미 커밋됨 → 안전
}
```

**판단 플로우차트**

```
이벤트 리스너를 추가하려 한다
│
├─ 리스너 실패 시 호출자 트랜잭션도 롤백되어야 하는가?
│   │
│   ├─ YES → @EventListener
│   │         (같은 트랜잭션, 예외 전파)
│   │
│   └─ NO ──┐
│            │
│            ├─ 리스너가 커밋된 데이터를 참조해야 하는가?
│            │   │
│            │   ├─ YES → @TransactionalEventListener(AFTER_COMMIT)
│            │   │         (외부 시스템이 데이터를 조회할 수 있어야 함)
│            │   │
│            │   └─ NO ──┐
│            │            │
│            │            ├─ 트랜잭션 결과와 무관하게 항상 실행?
│            │            │   │
│            │            │   ├─ YES → @EventListener 또는
│            │            │   │        @TransactionalEventListener
│            │            │   │          (fallbackExecution = true)
│            │            │   │
│            │            │   └─ NO → @TransactionalEventListener
│            │            │           (phase 선택)
```

**트레이드오프 정리**

`@EventListener`:

| 장점 | 단점 |
|------|------|
| 단순하고 직관적 | 커밋 전 실행 → 데이터 가시성 문제 |
| 같은 트랜잭션 참여 가능 | 리스너 예외가 호출자를 롤백시킴 |
| 트랜잭션 없어도 동작 | 롤백되어도 부수효과(알림 등)는 되돌릴 수 없음 |

`@TransactionalEventListener`:

| 장점 | 단점 |
|------|------|
| 커밋 후 실행 → 데이터 정합성 보장 | 리스너에서 DB 쓰기 시 새 트랜잭션 필요 |
| 리스너 실패가 비즈니스에 영향 없음 | 트랜잭션 없으면 실행 안 됨 (기본값) |
| 롤백 시 리스너 실행 안 함 → 안전 | 리스너 실패 시 재시도 메커니즘 별도 필요 |
| phase로 세밀한 제어 가능 | 이벤트 유실 가능 (리스너 실패 → 끝) |

**`@TransactionalEventListener`의 한계와 Outbox Pattern**

`@TransactionalEventListener(AFTER_COMMIT)`의 리스너가 실패하면 **이벤트가 유실**된다. 트랜잭션은 이미 커밋되었고, 리스너 재시도 메커니즘이 없기 때문이다. 이것이 바로 **Step 2의 Transactional Outbox Pattern이 필요한 이유**다.

```
@TransactionalEventListener 한계        Outbox Pattern으로 해결
─────────────────────────────          ─────────────────────────

  TX 커밋 ✅                             TX 커밋 ✅
     │                                      │
     ▼                                      ├─ outbox_event INSERT (같은 TX)
  리스너 실행                                  │
     │                                      ▼
     ▼                                 스케줄러가 outbox 조회
  Kafka 발행 실패 ❌                          │
     │                                      ▼
     └─ 이벤트 유실 (복구 불가)              Kafka 발행 실패 ❌
                                             │
                                             └─ 다음 스케줄에 재시도 ✅
                                                (published=false 유지)
```

#### 1-2-2. `@TransactionalEventListener`는 어떤 트랜잭션인지 어떻게 아는가? (내부 원리)

`@TransactionalEventListener`가 "커밋 이후에 실행"될 수 있는 원리는 **ThreadLocal + TransactionSynchronization 콜백** 조합이다.

**핵심 메커니즘**: `TransactionSynchronizationManager`

Spring은 `TransactionSynchronizationManager`를 통해 **ThreadLocal**로 현재 스레드의 트랜잭션 상태를 관리한다. 이벤트가 발행되는 시점에 이 ThreadLocal을 조회하여 현재 활성 트랜잭션을 식별한다.

**동작 흐름**:

```
Thread-1
  │
  ├─ @Transactional 메서드 시작
  │    └─ TransactionSynchronizationManager (ThreadLocal)
  │         → 현재 트랜잭션 활성 상태 저장
  │
  ├─ publishEvent(OrderPaidEvent)
  │    └─ ApplicationListenerMethodTransactionalAdapter
  │         → TransactionSynchronizationManager.isSynchronizationActive() == true
  │         → registerSynchronization(afterCommit → 리스너 실행 콜백 등록)
  │
  ├─ 트랜잭션 커밋
  │    └─ AbstractPlatformTransactionManager.commit()
  │         → triggerAfterCommit()
  │              → 등록된 리스너 실행
  │
  └─ 완료
```

**정리하면**:

1. `publishEvent()` 호출 시, 리스너를 즉시 실행하지 않고 현재 스레드의 트랜잭션에 `TransactionSynchronization` 콜백을 등록한다.
2. 트랜잭션이 커밋되면 `AbstractPlatformTransactionManager`가 등록된 콜백의 `afterCommit()`을 호출한다.
3. "어떤 트랜잭션인지"는 별도 ID 매칭이 아니라 **같은 스레드 = 같은 트랜잭션**이라는 ThreadLocal 기반으로 판단한다.

**주의점**:
- 트랜잭션이 없는 상태에서 이벤트를 발행하면, `fallbackExecution = true`가 아닌 이상 리스너가 실행되지 않고 무시된다.
- `phase` 설정(`AFTER_COMMIT`, `AFTER_ROLLBACK`, `AFTER_COMPLETION`, `BEFORE_COMMIT`)에 따라 각각 `TransactionSynchronization`의 해당 콜백에 매핑된다.

#### 1-2-3. TransactionSynchronization 인터페이스 구조

```
┌─────────────────────────────────────────────────────┐
│          TransactionSynchronization (interface)       │
├─────────────────────────────────────────────────────┤
│                                                       │
│  + suspend()                                          │
│  + resume()                                           │
│  + flush()                                            │
│  + beforeCommit(readOnly: boolean)    ◄── BEFORE_COMMIT phase │
│  + beforeCompletion()                                 │
│  + afterCommit()                      ◄── AFTER_COMMIT phase  │
│  + afterCompletion(status: int)       ◄── AFTER_COMPLETION /  │
│                                           AFTER_ROLLBACK      │
│     status = STATUS_COMMITTED (0)                     │
│            | STATUS_ROLLED_BACK (1)                   │
│            | STATUS_UNKNOWN (2)                       │
│                                                       │
└─────────────────────────────────────────────────────┘
```

#### 1-2-4. ThreadLocal 기반 트랜잭션 격리

각 스레드는 독립된 ThreadLocal 저장소를 가지며, 서로의 Synchronization 목록에 접근할 수 없다.

```
┌─────────────── JVM ──────────────────────────────────────────┐
│                                                               │
│  Thread-1 (주문 요청)              Thread-2 (좋아요 요청)       │
│  ┌──────────────────────┐         ┌──────────────────────┐    │
│  │ ThreadLocal 저장소     │         │ ThreadLocal 저장소     │    │
│  │                      │         │                      │    │
│  │ synchronizations:    │         │ synchronizations:    │    │
│  │  ┌────────────────┐  │         │  ┌────────────────┐  │    │
│  │  │ Sync #1        │  │         │  │ Sync #A        │  │    │
│  │  │ afterCommit →  │  │         │  │ afterCommit →  │  │    │
│  │  │ OrderPaid      │  │         │  │ ProductLiked   │  │    │
│  │  │ Listener       │  │         │  │ Listener       │  │    │
│  │  ├────────────────┤  │         │  └────────────────┘  │    │
│  │  │ Sync #2        │  │         │                      │    │
│  │  │ afterCommit →  │  │         │ currentTransaction:  │    │
│  │  │ UserAction     │  │         │  [TX-B]              │    │
│  │  │ Listener       │  │         └──────────────────────┘    │
│  │  └────────────────┘  │                                     │
│  │                      │         ※ 서로의 Sync 목록에 접근 불가  │
│  │ currentTransaction:  │           (ThreadLocal 격리)         │
│  │  [TX-A]              │                                     │
│  └──────────────────────┘                                     │
└───────────────────────────────────────────────────────────────┘
```

#### 1-2-5. 전체 동작 흐름 (시간 순서)

```
@Transactional 메서드 호출
│
▼
┌──────────────────────────────────────────────────────────────┐
│ ① 트랜잭션 시작                                                │
│    AbstractPlatformTransactionManager.getTransaction()        │
│    └─ TransactionSynchronizationManager                      │
│         .initSynchronization()                               │
│         → ThreadLocal<List<TransactionSynchronization>>      │
│           = new ArrayList<>()     ← 빈 리스트 초기화            │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ ② 비즈니스 로직 실행                                            │
│    orderRepository.save(order);                              │
│    paymentService.pay(order);                                │
│                                                              │
│    applicationEventPublisher.publishEvent(                   │
│        new OrderPaidEvent(orderId, memberId, amount)         │
│    );                                                        │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ ③ 이벤트 발행 시 내부 동작                                       │
│                                                              │
│    ApplicationListenerMethodTransactionalAdapter             │
│    │                                                         │
│    ├─ TransactionSynchronizationManager                      │
│    │    .isSynchronizationActive()  → true ✓                 │
│    │                                                         │
│    └─ TransactionSynchronizationManager                      │
│         .registerSynchronization(                            │
│             new TransactionSynchronization() {               │
│                 @Override                                     │
│                 void afterCommit() {                          │
│                     invokeListener(event);  // ⑤에서 실행됨     │
│                 }                                            │
│             }                                                │
│         )                                                    │
│                                                              │
│    ThreadLocal 리스트에 추가:                                    │
│    synchronizations = [ Sync#1(→OrderPaidListener) ]         │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ ④ 트랜잭션 커밋                                                 │
│    AbstractPlatformTransactionManager.commit()                │
│    │                                                         │
│    ├─ doCommit()           ← 실제 DB 커밋                      │
│    │                                                         │
│    ├─ triggerAfterCommit() ← 커밋 성공 후                       │
│    │   └─ TransactionSynchronizationUtils                    │
│    │        .invokeAfterCommit(synchronizations)             │
│    │        ┌─────────────────────────────────┐              │
│    │        │  for (sync : synchronizations)  │              │
│    │        │      sync.afterCommit(); ──────────── ⑤로      │
│    │        └─────────────────────────────────┘              │
│    │                                                         │
│    └─ triggerAfterCompletion(STATUS_COMMITTED)               │
│        └─ 각 sync.afterCompletion(0) 호출                     │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ ⑤ 리스너 실행                                                  │
│                                                              │
│    @TransactionalEventListener(phase = AFTER_COMMIT)         │
│    public void handleOrderPaid(OrderPaidEvent event) {       │
│        log.info("주문 완료 로깅: {}", event.getOrderId());      │
│        // 알림 발송, Kafka 발행 등 부가 로직                      │
│    }                                                         │
│                                                              │
│    ⚠️ 이 시점에서는 트랜잭션이 이미 커밋됨                          │
│       → 여기서 DB 쓰기가 필요하면 새 트랜잭션 필요                   │
│         (@Transactional(propagation = REQUIRES_NEW))         │
└──────────────────────────────────────────────────────────────┘
```

#### 1-2-6. phase별 콜백 매핑

```
트랜잭션 라이프사이클         TransactionSynchronization       @TransactionalEventListener
─────────────────          ──────────────────────           ──────────────────────────

  TX 시작
    │
    ▼
  비즈니스 로직
    │
    ▼
  ┌─ beforeCommit() ─────────────────────────────── phase = BEFORE_COMMIT
  │
  ├─ beforeCompletion()
  │
  ├─ DB COMMIT ✅
  │
  ├─ afterCommit() ──────────────────────────────── phase = AFTER_COMMIT (기본값)
  │
  └─ afterCompletion(STATUS_COMMITTED) ──────────── phase = AFTER_COMPLETION
                                                          (커밋/롤백 모두)

  ── 만약 롤백이면 ──

  ├─ DB ROLLBACK ❌
  │
  ├─ (afterCommit 호출 안 됨)
  │
  └─ afterCompletion(STATUS_ROLLED_BACK) ────────── phase = AFTER_ROLLBACK
                                                    phase = AFTER_COMPLETION
```

#### 1-3. 좋아요-집계 이벤트 분리

**현재 코드** (`ProductFacade.like()`):
```java
boolean changed = likeService.like(memberId, productId);
if (changed) {
    productService.incrementLikeCount(productId);  // 같은 TX에서 동기 집계
}
```

**변경**:
- 좋아요/취소 상태 변경 후 `ProductLikedEvent` 발행
- `@TransactionalEventListener(AFTER_COMMIT)` 리스너가 `likeCount` 업데이트
- **핵심**: 집계 실패해도 좋아요 자체는 성공 (eventual consistency)
- `AFTER_COMMIT`는 같은 스레드에서 동기 실행되므로 사실상 거의 즉시 반영되지만,
  리스너 실패 시 likeCount가 불일치할 수 있다. 이는 `refreshLikeSummary()` 배치로 보정 가능.

#### 1-4. 유저 행동 로깅 이벤트

- 별도 `UserActionEvent`를 만들지 않고, 도메인 이벤트(`OrderPaidEvent`, `ProductViewedEvent` 등)를 **로깅 전용 리스너가 직접 구독**
- 이벤트 종류별 핸들러 메서드를 `UserActionEventListener`에 정의
- `@TransactionalEventListener(AFTER_COMMIT)` 기반, 트랜잭션 없는 조회는 `fallbackExecution = true`
- 로깅 실패가 비즈니스에 영향 없도록 분리

> **왜 범용 UserActionEvent를 만들지 않는가?**
> 도메인 이벤트를 이미 발행하는데 같은 정보를 담은 별도 이벤트를 또 발행하는 건 중복이다.
> `Map<String, Object> metadata` 같은 자유형 구조는 타입 안전성이 떨어지고, 문자열 actionType 오타를 컴파일 타임에 잡을 수 없다.

#### 1-5. 수정/신규 파일 정리

**수정 대상**:
- `OrderPaymentFacade.java` — `ApplicationEventPublisher` 주입, 결제 성공/실패 시 이벤트 발행
- `ProductFacade.java` — 좋아요 집계를 이벤트로 분리, 상품 조회 시 `ProductViewedEvent` 발행

**신규 파일**:
- `domain/event/` — 이벤트 record 클래스들 (`OrderPaidEvent`, `OrderFailedEvent`, `ProductLikedEvent`, `ProductViewedEvent`)
- `application/event/` — 이벤트 리스너들 (`OrderEventListener`, `LikeEventListener`, `UserActionEventListener`)

---

### Step 2 — Kafka Producer / Consumer 파이프라인

#### 2-00. Producer와 Consumer 개념

**메시징 시스템에서의 역할**

```
Producer = 편지를 쓰는 사람 (발신자)
Kafka    = 우체국 (전달 시스템)
Consumer = 편지를 받는 사람 (수신자)
```

Producer는 메시지를 **만들어서 보내는 쪽**, Consumer는 메시지를 **받아서 처리하는 쪽**이다.

**우리 프로젝트에서의 매핑**

```
┌─────────────────┐         ┌─────────────┐         ┌─────────────────────┐
│  commerce-api   │         │    Kafka    │         │ commerce-streamer   │
│  (Producer)     │────────►│   (Broker)  │────────►│ (Consumer)          │
│                 │         │             │         │                     │
│  "이벤트 발생했다" │  발행    │  "메시지 보관" │  구독    │ "이벤트 처리하겠다"   │
└─────────────────┘         └─────────────┘         └─────────────────────┘
```

| 역할 | 애플리케이션 | 하는 일 |
|------|------------|--------|
| **Producer** | commerce-api | 주문 완료, 좋아요 등 이벤트 발생 시 Kafka에 메시지 발행 |
| **Broker** | Kafka | 메시지를 토픽별로 보관하고 Consumer에게 전달 |
| **Consumer** | commerce-streamer | 메시지를 수신하여 집계, 쿠폰 발급 등 처리 |

**Producer (생산자)**

역할: 이벤트가 발생했을 때 메시지를 만들어서 Kafka 토픽에 보낸다.

```
Producer가 하는 일
│
├─ ① 이벤트 데이터를 메시지로 변환 (직렬화)
│     OrderPaidEvent → JSON 문자열
│
├─ ② 어떤 토픽에 보낼지 결정
│     "order-events" 토픽
│
├─ ③ Partition Key 지정 (순서 보장)
│     key = orderId → 같은 주문의 이벤트는 같은 파티션으로
│
└─ ④ Kafka에 전송
      kafkaTemplate.send("order-events", orderId, payload)
```

Producer의 핵심 설정:

```
acks=all           → 모든 브로커 복제본이 저장 확인해야 성공으로 간주
                     (메시지 유실 방지)

idempotence=true   → 네트워크 재시도로 같은 메시지가 중복 전송되어도
                     Kafka가 1번만 저장 (Producer → Kafka 구간 중복 방지)
```

**Consumer (소비자)**

역할: Kafka 토픽을 구독하고, 메시지가 도착하면 가져와서 처리한다.

```
Consumer가 하는 일
│
├─ ① Kafka 토픽 구독 (subscribe)
│     @KafkaListener(topics = "order-events")
│
├─ ② 메시지 수신
│     Kafka가 새 메시지를 push (또는 Consumer가 poll)
│
├─ ③ 멱등성 확인
│     event_handled에 event_id 존재? → YES면 skip
│
├─ ④ 비즈니스 로직 처리
│     product_metrics upsert, 쿠폰 발급 등
│
└─ ⑤ ACK (처리 완료 알림)
      acknowledgment.acknowledge()
      → Kafka에게 "이 메시지 처리 끝났어" 알림
      → 다음 메시지 받을 준비
```

Manual ACK를 왜 수동으로 하는가:

```
Auto ACK (자동)                         Manual ACK (수동)
──────────────                         ─────────────────

메시지 수신 → 즉시 ACK → 처리 시작      메시지 수신 → 처리 시작 → 처리 완료 → ACK
                │                                              │
                ▼                                              ▼
         처리 중 실패하면?                               처리 중 실패하면?
         이미 ACK 보냄                                  ACK 안 보냄
         → Kafka는 처리 완료로 인식                      → Kafka가 다시 전달
         → 메시지 유실 ❌                               → 재처리 가능 ✅
```

**Consumer Group**

같은 토픽을 여러 Consumer가 나눠서 처리할 수 있다.

```
                              ┌─────────────────────┐
                              │   order-events 토픽   │
                              │                     │
                              │  Partition 0: ■■■■  │
                              │  Partition 1: ■■■■  │
                              │  Partition 2: ■■■■  │
                              └──────┬──────────────┘
                                     │
                    ┌────────────────┼────────────────┐
                    ▼                ▼                ▼
            ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
            │ Consumer A   │ │ Consumer B   │ │ Consumer C   │
            │ (Partition 0)│ │ (Partition 1)│ │ (Partition 2)│
            └──────────────┘ └──────────────┘ └──────────────┘
            └──────────── Consumer Group "metrics-group" ─────┘

  → 같은 그룹 내에서 하나의 파티션은 하나의 Consumer만 담당
  → 병렬 처리로 처리량(throughput) 향상
```

Consumer Group이 다르면 같은 메시지를 각각 받는다:

```
                    order-events 토픽
                         │
              ┌──────────┼──────────┐
              ▼                     ▼
    ┌──────────────────┐  ┌──────────────────┐
    │ Group: "metrics" │  │ Group: "notify"  │
    │ → 집계 처리        │  │ → 알림 발송        │
    │                  │  │                  │
    │ 같은 메시지를      │  │ 같은 메시지를      │
    │ 각각 독립 수신     │  │ 각각 독립 수신     │
    └──────────────────┘  └──────────────────┘
```

**Partition Key와 순서 보장**

```
Producer가 key = orderId로 발행하면:

  orderId=1 → hash(1) % 3 = Partition 0
  orderId=2 → hash(2) % 3 = Partition 1
  orderId=1 → hash(1) % 3 = Partition 0  ← 같은 파티션!

  Partition 0: [주문1 생성] → [주문1 결제] → [주문1 완료]
                    순서 보장 ✅

  Partition 1: [주문2 생성] → [주문2 결제]
                    순서 보장 ✅

  ※ Partition 0과 1 사이의 순서는 보장 안 됨
    → 같은 주문의 이벤트끼리만 순서가 중요하므로 문제없음
```

**전체 흐름 한눈에 보기 (order-events 직접 발행 방식)**

```
사용자 주문 요청
│
▼
┌─ commerce-api (Producer) ───────────────────────────────────────┐
│                                                                  │
│  OrderTransactionService (@Transactional)                        │
│  ├─ orderService.completeOrder() (orders UPDATE)                 │
│  └─ publishEvent(OrderPaidEvent)                                 │
│                                                                  │
│  KafkaEventPublishListener (@TransactionalEventListener          │
│                              AFTER_COMMIT)                       │
│  └─ kafkaTemplate.send("order-events", orderId, payload)         │
│       └─ 실패 시 이벤트 유실 (통계성이므로 허용)                    │
│                                                                  │
└──────────────────────────────┬───────────────────────────────────┘
                               │
                        ┌──────▼──────┐
                        │    Kafka    │
                        │             │
                        │ order-events│
                        │ [P0][P1][P2]│
                        └──────┬──────┘
                               │
┌─ commerce-streamer (Consumer)┼──────────────────────────────────┐
│                              ▼                                   │
│  @KafkaListener(topics = "order-events")                         │
│  │                                                               │
│  ├─ 메시지 수신                                                    │
│  ├─ event_handled 확인 → 중복이면 skip                             │
│  ├─ product_metrics upsert (집계)                                 │
│  ├─ event_handled INSERT                                         │
│  └─ acknowledgment.acknowledge() (Manual ACK)                    │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

---

#### 2-0. Transactional Outbox Pattern 개념

**해결하려는 문제: Dual Write Problem**

비즈니스 로직에서 **DB 저장**과 **메시지 발행**을 동시에 해야 하는 상황이 자주 발생한다.

```java
@Transactional
public void createOrder(OrderCommand cmd) {
    orderRepository.save(order);              // ① DB 저장
    kafkaTemplate.send("order-events", event); // ② Kafka 발행
}
```

이 두 작업은 **서로 다른 시스템**이라 하나의 트랜잭션으로 묶을 수 없다. 여기서 실패 시나리오가 발생한다.

```
시나리오 1: 둘 다 성공 ✅ → 문제 없음

시나리오 2: DB 성공, Kafka 실패 ❌
  ┌─────────────────────────────────────────────┐
  │  DB: 주문 저장됨 ✅                            │
  │  Kafka: 이벤트 발행 실패 ❌                     │
  │  결과: Consumer가 주문을 모름 → 데이터 불일치    │
  └─────────────────────────────────────────────┘

시나리오 3: DB 실패, Kafka 성공 ❌
  ┌─────────────────────────────────────────────┐
  │  DB: 주문 롤백됨 ❌                            │
  │  Kafka: 이벤트 이미 발행됨 ✅                   │
  │  결과: 없는 주문에 대한 이벤트 전파 → 데이터 불일치│
  └─────────────────────────────────────────────┘
```

핵심 문제: DB와 Kafka는 분산 시스템이므로 **원자적(atomic) 처리가 불가능**하다. 순서를 바꿔도, try-catch로 감싸도 근본적으로 해결되지 않는다.

```
"Kafka 먼저 보내고 DB 저장하면?" → Kafka 성공 후 DB 실패하면 동일한 문제
"try-catch로 Kafka 실패 시 롤백?" → DB 커밋 후 Kafka 실패하면 이미 늦음
"@TransactionalEventListener?" → 리스너에서 Kafka 실패하면 이벤트 유실
```

**Outbox Pattern의 아이디어**

```
"Kafka에 직접 보내지 말고, 같은 DB 트랜잭션에 '보내야 할 메시지'를 저장하자"
```

DB 저장은 트랜잭션으로 원자성을 보장할 수 있으니까, **이벤트도 DB에 같이 저장**하면 Dual Write 문제가 사라진다.

**구조**

```
┌─ commerce-api ──────────────────────────────────────────────────┐
│                                                                  │
│  ┌──────────────────────────────────────────────────────┐        │
│  │              @Transactional (하나의 트랜잭션)           │        │
│  │                                                      │        │
│  │   ① orderRepository.save(order)                      │        │
│  │      └─ INSERT INTO orders (...)                     │        │
│  │                                                      │        │
│  │   ② outboxRepository.save(outboxEvent)               │        │
│  │      └─ INSERT INTO outbox_event (...)               │        │
│  │                                                      │        │
│  │   → 둘 다 같은 트랜잭션 → 원자적 커밋/롤백 보장 ✅      │        │
│  └──────────────────────────────────────────────────────┘        │
│                                                                  │
│  ┌──────────────────────────────────────────────────────┐        │
│  │              Outbox Publisher (@Scheduled)            │        │
│  │                                                      │        │
│  │   ③ SELECT * FROM outbox_event                       │        │
│  │     WHERE published = false                          │        │
│  │     ORDER BY created_at                              │        │
│  │                                                      │        │
│  │   ④ kafkaTemplate.send(topic, payload)               │        │
│  │                                                      │        │
│  │   ⑤ UPDATE outbox_event                              │        │
│  │     SET published = true, published_at = NOW()       │        │
│  └──────────────────┬───────────────────────────────────┘        │
└─────────────────────┼────────────────────────────────────────────┘
                      │
                      ▼
              ┌───────────────┐
              │     Kafka     │
              └───────┬───────┘
                      │
                      ▼
              ┌───────────────┐
              │   Consumer    │
              │ (commerce-    │
              │  streamer)    │
              └───────────────┘
```

**동작 원리 (시간 순서)**

```
Phase 1: 비즈니스 트랜잭션 (원자적)
══════════════════════════════════════════════════════════════

  BEGIN TRANSACTION
  │
  ├─ ① INSERT INTO orders (id, member_id, total_amount, ...)
  │      VALUES (1, 100, 50000, ...)
  │
  ├─ ② INSERT INTO outbox_event
  │      (aggregate_type, aggregate_id, event_type, payload, published)
  │      VALUES ('ORDER', '1', 'ORDER_PAID',
  │              '{"orderId":1,"memberId":100,"amount":50000}', false)
  │
  COMMIT   ← 둘 다 성공하거나 둘 다 실패 ✅
  │
  │  ※ 이 시점에서 Kafka에는 아무것도 보내지 않았다.
  │     대신 outbox_event 테이블에 "보내야 할 메시지"가 기록되어 있다.


Phase 2: Outbox Publisher (별도 스케줄러, 주기적 실행)
══════════════════════════════════════════════════════════════

  @Scheduled(fixedDelay = 1000)  ← 1초마다 폴링
  │
  ├─ ③ SELECT * FROM outbox_event
  │      WHERE published = false
  │      ORDER BY created_at ASC
  │      LIMIT 100
  │
  │    결과: [{id:1, type:'ORDER_PAID', payload:'...'}]
  │
  ├─ ④ kafkaTemplate.send("order-events", payload)
  │    │
  │    ├─ 성공 ✅
  │    │   └─ ⑤ UPDATE outbox_event
  │    │         SET published = true, published_at = NOW()
  │    │         WHERE id = 1
  │    │
  │    └─ 실패 ❌
  │        └─ 아무것도 안 함 → published = false 유지
  │           → 다음 스케줄에서 재시도 ✅
  │
  다음 주기에 다시 ③부터 반복
```

**왜 안전한가? — 실패 시나리오 분석**

```
시나리오 A: Phase 1에서 DB 커밋 실패
───────────────────────────────────
  orders INSERT 실패 → 전체 롤백
  outbox_event도 롤백 → Kafka 발행 대상 없음
  결과: 아무 일도 일어나지 않음 ✅ (정합성 유지)


시나리오 B: Phase 2에서 Kafka 발행 실패
───────────────────────────────────
  outbox_event는 이미 DB에 있음 (published = false)
  Kafka 실패 → published 업데이트 안 함
  다음 스케줄에서 재시도
  결과: At Least Once 보장 ✅


시나리오 C: Phase 2에서 Kafka 성공 후 published 업데이트 실패
───────────────────────────────────
  Kafka에는 이벤트 발행됨
  published = false 유지 → 다음 스케줄에서 중복 발행
  결과: 중복 발행 가능 → Consumer 쪽 멱등 처리 필요 ⚠️


시나리오 D: Publisher 서버 다운
───────────────────────────────────
  outbox_event는 DB에 안전하게 저장되어 있음
  서버 재시작 후 published = false인 이벤트부터 재발행
  결과: 지연은 있지만 유실 없음 ✅
```

**At Least Once 보장과 멱등성**

Outbox Pattern은 **At Least Once** (최소 1회 발행)를 보장하지만, **Exactly Once** (정확히 1회)는 보장하지 않는다.

```
┌──────────────────────────────────────────────────────────┐
│                    발행 보장 수준                           │
│                                                          │
│  At Most Once    ── 발행 실패해도 재시도 안 함 (유실 가능)    │
│  At Least Once   ── 반드시 1회 이상 발행 (중복 가능) ◄ Outbox│
│  Exactly Once    ── 정확히 1회 (이론적으로 분산환경에서 불가능) │
└──────────────────────────────────────────────────────────┘
```

따라서 Consumer 쪽에서 **멱등 처리**가 반드시 필요하다:

```
Consumer 수신
│
├─ event_handled 테이블에 event_id 존재?
│   │
│   ├─ YES → 이미 처리함, skip (ACK)
│   │
│   └─ NO → 처리 수행
│           └─ event_handled에 event_id INSERT
│              └─ ACK
```

**전체 그림: ApplicationEvent → Kafka → Consumer (직접 발행 방식)**

```
┌─ commerce-api ──────────────────────────────────────────────────┐
│                                                                  │
│  OrderTransactionService                                         │
│  │                                                               │
│  ├─ @Transactional                                               │
│  │   ├─ 주문/결제 처리 (orders, payments UPDATE)                   │
│  │   └─ publishEvent(OrderPaidEvent)                             │
│  │                                                               │
│  ├─ @TransactionalEventListener(AFTER_COMMIT)                    │
│  │   ├─ KafkaEventPublishListener → Kafka 직접 발행               │
│  │   └─ OrderEventListener, UserActionEventListener (로깅 등)    │
│  │                                                               │
└────────────────────────────────┬────────────────────────────────┘
                                 │
                          ┌──────▼──────┐
                          │    Kafka    │
                          │ order-events│
                          └──────┬──────┘
                                 │
┌─ commerce-streamer ────────────┼─────────────────────────────────┐
│                                │                                  │
│  OrderEventConsumer            ▼                                  │
│  ├─ event_handled 확인 (멱등)                                      │
│  ├─ product_metrics upsert (집계)                                  │
│  ├─ event_handled INSERT                                          │
│  └─ manual ACK                                                    │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
```

**Polling vs CDC (보충)**

위에서 설명한 방식은 **Polling Publisher** 방식이다. Outbox 테이블을 읽는 방식에는 두 가지가 있다.

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                  │
│  Polling Publisher                    CDC (Change Data Capture)   │
│  ─────────────────                    ────────────────────────    │
│                                                                  │
│  @Scheduled로 주기적 폴링              DB의 변경 로그(binlog)를 감지  │
│  SELECT WHERE published = false       Debezium 등 CDC 커넥터 사용  │
│                                                                  │
│  장점:                                 장점:                       │
│  - 구현이 단순                          - 폴링 없이 실시간 감지       │
│  - 추가 인프라 불필요                    - DB 부하 최소화             │
│                                                                  │
│  단점:                                 단점:                       │
│  - 폴링 주기만큼 지연                    - Debezium 등 추가 인프라    │
│  - DB 부하 (주기적 쿼리)                 - 운영 복잡도 증가           │
│                                                                  │
│  → 우리 프로젝트에서 채택 ✅             → 대규모 트래픽 시 고려       │
└─────────────────────────────────────────────────────────────────┘
```

**Outbox Pattern과 멱등성: Producer vs Consumer의 책임 분리**

Outbox Pattern을 사용하면 Producer가 발행 전에 중복 검증을 하는 것이 아니다. Producer와 Consumer의 역할은 명확히 다르다.

```
┌─ Producer (commerce-api) ─────────────────────────────────────────┐
│                                                                    │
│  Outbox 테이블의 published 플래그                                    │
│  → "이 이벤트를 Kafka에 보냈는가?" 를 추적                            │
│  → 중복 발행 방지가 목적이 아님                                       │
│  → 못 보낸 것을 재시도하기 위한 장치                                   │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘

┌─ Consumer (commerce-streamer) ────────────────────────────────────┐
│                                                                    │
│  event_handled 테이블의 event_id                                    │
│  → "이 이벤트를 이미 처리했는가?" 를 추적                              │
│  → 중복 처리 방지가 목적 (멱등성)                                     │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

왜 이렇게 나뉘는가:

```
시간축 ──────────────────────────────────────────────────────────►

Producer                          Kafka                    Consumer
───────                          ─────                    ────────

① Outbox에서 이벤트 읽음
② Kafka 발행 성공 ✅
③ published = true 업데이트 실패 ❌  ← 여기서 문제 발생
   (DB 장애, 타임아웃 등)

④ 다음 스케줄에서 같은 이벤트 재발행
   (published가 여전히 false니까)
                                  ⑤ 같은 이벤트가
                                     2번 도착
                                                          ⑥ 첫 번째: 처리 ✅
                                                             event_handled INSERT
                                                          ⑦ 두 번째: event_handled에
                                                             이미 있음 → skip ✅
```

Producer는 "못 보낸 걸 다시 보내는 것"이 목적이라 중복 발행이 발생할 수 있고, 이건 의도된 동작이다 (At Least Once). Consumer가 "이미 처리한 건 다시 처리하지 않는 것"으로 멱등성을 보장한다.

| | Outbox 테이블 (Producer) | event_handled 테이블 (Consumer) |
|---|---|---|
| **위치** | commerce-api DB | commerce-streamer DB |
| **목적** | 이벤트 유실 방지 (재시도) | 중복 처리 방지 (멱등성) |
| **확인 대상** | "보냈는가?" (published) | "처리했는가?" (event_id) |
| **없으면?** | 이벤트 유실 가능 | 중복 처리 가능 |

**Outbox Pattern 자체는 "이벤트를 안전하게 발행"하는 것까지가 책임**이고, **멱등성 보장은 Consumer 쪽의 별도 책임**이다. 둘은 한 세트로 같이 써야 분산 환경에서 안전하다.

**Kafka 발행과 published 업데이트는 트랜잭션으로 묶여있지 않다**

Outbox Publisher의 동작을 다시 보면:

```
Outbox Publisher가 하는 일:

  ① SELECT outbox_event WHERE published = false     ← DB 작업
  ② kafkaTemplate.send("order-events", payload)     ← Kafka 작업
  ③ UPDATE outbox_event SET published = true         ← DB 작업

  ①과 ③은 같은 DB라서 트랜잭션으로 묶을 수 있지만,
  ②는 Kafka라는 완전히 다른 시스템이다.

  DB 트랜잭션으로 ①②③을 묶는 것은 불가능하다.
  → 이것이 바로 처음에 설명한 Dual Write Problem과 같은 구조
```

그러면 여기서도 정합성 문제가 생기지 않나? 생긴다. 하지만 **유실은 안 생기고, 중복만 생긴다**. 이것이 핵심이다.

```
시나리오별 분석
═══════════════════════════════════════════════════════════

Case 1: ② Kafka 발행 실패
─────────────────────────
  ① SELECT  ✅
  ② send    ❌  ← 실패
  ③ UPDATE  실행 안 함

  published = false 유지 → 다음 스케줄에서 재시도
  결과: 유실 없음 ✅, 중복 없음 ✅


Case 2: ② 성공, ③ 업데이트 실패
─────────────────────────
  ① SELECT  ✅
  ② send    ✅  ← Kafka에 발행됨
  ③ UPDATE  ❌  ← DB 장애 등으로 실패

  published = false 유지 → 다음 스케줄에서 같은 이벤트 재발행
  결과: 유실 없음 ✅, 중복 발생 ⚠️
        → Consumer의 event_handled로 해결


Case 3: ② 성공, ③ 성공
─────────────────────────
  정상 케이스
  결과: 유실 없음 ✅, 중복 없음 ✅
```

왜 이 설계가 안전한가:

```
트랜잭션으로 묶는 경우 (불가능하지만 가정)
──────────────────────────────────────
  "Kafka 발행과 DB 업데이트가 원자적"
  → Exactly Once (정확히 1회)
  → 이론적으로 분산 시스템에서 달성 불가능


트랜잭션으로 묶지 않는 현재 설계
──────────────────────────────────────
  "실패하면 재시도, 중복은 Consumer가 처리"
  → At Least Once (최소 1회)
  → 분산 시스템에서 현실적으로 달성 가능한 최선

  ┌────────────────────────────────────────────────┐
  │  유실 가능성: 없음                                │
  │  → published = false면 계속 재시도하니까           │
  │                                                │
  │  중복 가능성: 있음                                │
  │  → Kafka 발행 후 published 업데이트 실패 시        │
  │                                                │
  │  중복 해결: Consumer의 event_handled 테이블        │
  │  → 이미 처리한 event_id면 skip                    │
  └────────────────────────────────────────────────┘
```

정리:

```
"트랜잭션으로 묶을 수 없으면, 어느 쪽으로 실패하는 게 나은가?"

  유실 (못 보냄)  vs  중복 (여러 번 보냄)
       ❌                    ✅

  유실은 복구 불가능 → 데이터 정합성 깨짐
  중복은 Consumer에서 걸러낼 수 있음 → 멱등 처리로 해결

  그래서 Outbox Pattern은 의도적으로
  "중복은 허용하되, 유실은 절대 없는" 방향으로 설계된 것이다.
```

이것이 바로 **Outbox (Producer) + event_handled (Consumer)가 한 세트**인 이유다. Outbox만으로는 중복 문제가 남고, event_handled가 있어야 비로소 완전해진다.

**실무에서의 현실: ApplicationEvent + Outbox 풀세트 조합은 드물다**

교과서적 조합(ApplicationEvent → @TransactionalEventListener → Outbox → Kafka → Consumer 멱등 처리)을 풀세트로 쓰는 경우는 실무에서 드물다. 실무에서는 상황에 따라 필요한 것만 골라 쓴다.

실무에서 실제로 흔한 패턴:

```
패턴 A: 그냥 서비스에서 직접 호출
──────────────────────────────
  @Transactional
  public void processPayment(OrderCommand cmd) {
      orderRepository.save(order);
      paymentService.pay(order);
      logService.log(order);          // 그냥 직접 호출
      kafkaTemplate.send(...);        // 그냥 직접 발행
  }

  "같은 서비스 안에서 굳이 이벤트로 나눌 이유가 없다"
  → 메서드 호출이 더 직관적이고 디버깅도 쉬움


패턴 B: Kafka 직접 발행 + 유실 감수
──────────────────────────────
  @TransactionalEventListener(phase = AFTER_COMMIT)
  public void publishToKafka(OrderPaidEvent event) {
      kafkaTemplate.send("order-events", event);
  }

  "Outbox까지 쓸 만큼 유실이 치명적이지 않다"
  → 유실 확률 자체가 매우 낮음 (Kafka 클러스터 장애 시에만)
  → 유실 시 수동 복구하거나 배치로 보정


패턴 C: Outbox만 사용 (ApplicationEvent 없이)
──────────────────────────────
  @Transactional
  public void processPayment(OrderCommand cmd) {
      orderRepository.save(order);
      outboxRepository.save(outboxEvent);    // 직접 저장
  }

  // 스케줄러가 Outbox 폴링 → Kafka 발행
  // ApplicationEvent 자체를 안 씀


패턴 D: CDC (대규모)
──────────────────────────────
  Debezium이 DB binlog를 감지 → Kafka로 자동 발행
  애플리케이션 코드에서 Kafka 발행 로직 자체가 없음
```

풀세트 조합이 실무에서 잘 안 쓰이는 이유:

```
이유 1: 과도한 추상화
  같은 서비스 안에서 이벤트로 분리하면 코드 흐름 추적이 어려움
  직접 호출하면 코드 읽는 순서대로 실행되어 디버깅이 직관적

이유 2: Outbox 운영 비용
  outbox_event 테이블 정리, 스케줄러 모니터링, 폴링 지연 등
  이 운영 비용을 감수할 만큼 유실이 치명적인 경우에만 도입

이유 3: ApplicationEvent의 본래 용도
  서로 다른 모듈/패키지 간의 결합도를 낮추기 위한 도구
  같은 서비스 내에서 "주문 → 로깅" 정도는 이벤트로 나눌 이유가 없는 경우가 많음
```

그러면 우리가 이걸 배우는 이유는:

```
┌──────────────────────────────────────────────────────────────┐
│                                                               │
│  1. 개념과 동작 원리를 이해하기 위해                               │
│     → "왜 이런 패턴이 존재하는가?"를 알아야                        │
│       "언제 안 써도 되는가?"도 판단할 수 있다                      │
│                                                               │
│  2. 트레이드오프 판단력을 기르기 위해                               │
│     → "Outbox가 필요한 상황 vs 과잉 엔지니어링"을                  │
│       구분하는 감각은 직접 구현해봐야 생긴다                        │
│                                                               │
│  3. 규모가 커졌을 때의 선택지를 미리 확보하기 위해                   │
│     → 결제, 정산 같은 돈이 관련된 도메인에서는                      │
│       Outbox Pattern이 실제로 필수인 경우가 있다                   │
│                                                               │
│  "이걸 쓸 줄 아는 것"과 "이걸 언제 쓸지 아는 것"은 다르다.          │
│  둘 다 필요하고, 후자가 더 중요하다.                               │
│                                                               │
└──────────────────────────────────────────────────────────────┘
```

---

#### 2-1. 현재 코드베이스 상태 분석

**commerce-api**:
- `modules:kafka` 의존성 있음 (`build.gradle.kts`에 이미 포함)
- Outbox 관련 코드/테이블 없음 (전부 신규)
- Step 1에서 구현한 ApplicationEvent 4개와 이벤트 리스너 3개 존재

**commerce-streamer**:
- `modules:kafka` 의존성 있음, `DemoKafkaConsumer` 데모만 존재
- 배치 리스너(`BATCH_LISTENER`) 기반, Manual ACK 설정 완료
- application.yml의 application name이 `commerce-api`로 잘못 되어 있음 → 수정 필요

**kafka.yml (modules:kafka)**:
- `acks`, `enable.idempotence` 설정 없음 → 추가 필요
- Producer: `JsonSerializer`, Consumer: `ByteArrayDeserializer`, Manual ACK

#### 2-2. 설계 결정 사항

Step 2 구현에 앞서 아래 6가지 설계 결정을 진행했다.

##### 결정 1: Outbox 엔티티 레이어 배치 → **domain/outbox + infrastructure/outbox (DIP 패턴)**

**고민**: Outbox는 Kafka 발행을 위한 인프라 장치인데, 기존 DIP 패턴(domain에 인터페이스, infrastructure에 구현체)을 따를 것인가, 아니면 infrastructure에 전부 둘 것인가?

**선택지**:
- A. `domain/outbox/` + `infrastructure/outbox/` — 기존 DIP 패턴 따르기
- B. `infrastructure/outbox/`에 엔티티 + Repository 전부 — 인프라 관심사로 취급

**B의 문제점**: Outbox INSERT는 비즈니스 트랜잭션과 같은 TX에서 수행되어야 한다. 즉, application 또는 domain 레이어에서 `outboxRepository.save()`를 호출해야 하는데, Repository와 엔티티가 전부 infrastructure에 있으면 **application → infrastructure** 역방향 의존이 발생한다.

```
기존 패턴 (정상):
  Application → Domain(Repository 인터페이스) ← Infrastructure(구현체)

B를 적용하면:
  Application → Infrastructure(OutboxRepository) ← ❌ 역방향 의존
```

**결정: A** — 기존 프로젝트의 DIP 패턴과 일관성을 유지한다.
- `domain/outbox/` — `OutboxEvent` 엔티티, `OutboxEventRepository` 인터페이스
- `infrastructure/outbox/` — JPA Repository 구현체

##### 결정 2: Outbox Publisher 위치 → **application/outbox + 인터페이스 추상화**

**고민**: OutboxPublisher는 "Outbox 조회(domain)" + "Kafka 발행(인프라)"을 조율한다. application 레이어에서 `KafkaTemplate`을 직접 사용하면 인프라 구체 클래스에 직접 의존하게 된다.

**선택지**:
- 접근 1: application에서 KafkaTemplate 직접 사용 — 단순하지만 DIP 위반
- 접근 2: domain에 발행 인터페이스 정의, infrastructure에서 Kafka 구현 — DIP 유지

**접근 1의 문제점**: `KafkaTemplate`은 Kafka 인프라에 종속된 구체 클래스. application에서 직접 쓰면 Facade에서 JpaRepository 구현체를 직접 쓰는 것과 같은 문제.

**결정: 접근 2** — 인터페이스 추상화로 DIP 유지

```
domain/outbox/
├── OutboxEvent.java              (엔티티)
├── OutboxEventRepository.java    (DB 접근 인터페이스)
└── OutboxEventPublisher.java     (메시지 발행 인터페이스) ← 추가

infrastructure/outbox/
├── OutboxEventRepositoryImpl.java       (JPA 구현체)
└── KafkaOutboxEventPublisher.java       (Kafka 구현체) ← 추가

application/outbox/
└── OutboxScheduler.java          (스케줄러 - 조율만 담당)
```

OutboxScheduler는 domain 인터페이스만 의존하며, Kafka 구체 구현을 모른다:
```java
OutboxScheduler {
    private final OutboxEventRepository outboxRepository;   // domain 인터페이스
    private final OutboxEventPublisher eventPublisher;       // domain 인터페이스

    @Scheduled
    void publish() {
        List<OutboxEvent> events = outboxRepository.findUnpublished();
        for (OutboxEvent event : events) {
            eventPublisher.publish(event);
            event.markPublished();
        }
    }
}
```

##### 결정 3: Kafka 전파 대상 이벤트 → **3개 (OrderFailedEvent 제외)**

**고민**: Step 1의 4개 이벤트 중 어떤 것을 Kafka로 전파할 것인가?

| 이벤트 | Kafka 전파 | 이유 |
|---|---|---|
| `OrderPaidEvent` | **O** | 판매량(order_count) 집계 |
| `ProductLikedEvent` | **O** | 좋아요 수(like_count) 집계 |
| `ProductViewedEvent` | **O** | 조회수(view_count) 집계 |
| `OrderFailedEvent` | **X** | commerce-api에서 보상 트랜잭션으로 이미 처리 완료. Consumer에서 추가 집계할 대상 없음 |

**결정**: `OrderPaidEvent`, `ProductLikedEvent`, `ProductViewedEvent` 3개만 전파.

##### 결정 4: 토픽 설계 → **도메인 기준 2개 토픽**

**고민**: Consumer가 하는 일이 전부 `product_metrics` upsert인데, 토픽을 나눌 필요가 있는가?

**선택지**:
- A. 도메인 기준 2개: `catalog-events`(key=productId) + `order-events`(key=orderId)
- B. 1개 통합: `product-metrics-events`(key=productId)

**B의 문제점**: 주문 이벤트를 상품 메트릭 토픽에 넣는 것이 의미적으로 어색하고, 나중에 order-events에 다른 Consumer Group(알림 등)을 붙이기 어려움.

**결정: A** — 도메인 기준 2개 토픽

| 토픽 | Partition Key | 발행 이벤트 | Consumer 처리 |
|------|-------------|-----------|-------------|
| `catalog-events` | productId | `ProductLikedEvent`, `ProductViewedEvent` | like_count, view_count 집계 |
| `order-events` | orderId | `OrderPaidEvent` | order_count 집계 |

##### 결정 5: product_metrics와 기존 Product.likeCount 관계 → **이중 관리 (기존 유지 + product_metrics 추가)**

**고민**: Product 엔티티에 이미 `likeCount`가 있고, `LikeEventListener`에서 `AFTER_COMMIT`으로 동기 집계하고 있다. Consumer가 product_metrics에 비동기 집계하면 같은 데이터가 두 곳에 존재한다.

**선택지**:
- A. 이중 관리 — `Product.likeCount`(실시간, API 응답용) + `product_metrics`(비동기, 분석/통계용)
- B. product_metrics로 완전 이관 — `Product.likeCount` 삭제, 모든 집계를 비동기로

**B의 문제점**: 기존 코드 대규모 변경 필요 (정렬 인덱스, 조회 쿼리 등). 비동기이므로 즉시 반영 안 됨. 과제에서 `Product.likeCount` 제거를 요구하지 않음.

**결정: A** — 기존 `Product.likeCount` 유지 + `product_metrics` 별도 집계 테이블 추가.
- commerce-api: `Product.likeCount` → 실시간 반영 (API 응답, 정렬용)
- commerce-streamer: `product_metrics` → 비동기 집계 (통계/분석용)

##### 결정 6: event_handled / product_metrics 테이블 위치 → **같은 DB, 코드 소유권은 commerce-streamer**

**고민**: Consumer(commerce-streamer)의 멱등 처리를 위한 `event_handled`와 집계용 `product_metrics`를 별도 DB에 둘 것인가?

**현재 상태**: commerce-api와 commerce-streamer가 같은 `jpa.yml`을 import하며, 동일한 DB(`localhost:3306/loopers`)를 공유한다.

**결정**: 같은 DB에 둔다.
- 원칙적으로 `event_handled`는 Consumer 소유이므로 별도 DB가 이상적이지만, 현재 구조에서 별도 DB 구성은 과제 범위를 넘어섬
- `event_handled`, `product_metrics` 엔티티와 Repository는 **commerce-streamer 코드에 배치**하여 소유권을 명확히 함

#### 2-3. Transactional Outbox Pattern 구현 (Producer — commerce-api)

**Outbox 테이블 설계**:

```sql
CREATE TABLE outbox_event (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id       VARCHAR(36) NOT NULL UNIQUE, -- UUID, Consumer 멱등 처리 키
    aggregate_type VARCHAR(100) NOT NULL,       -- 'ORDER', 'PRODUCT'
    aggregate_id   VARCHAR(100) NOT NULL,       -- orderId, productId 등
    event_type     VARCHAR(100) NOT NULL,       -- 'ORDER_PAID', 'PRODUCT_LIKED'
    topic          VARCHAR(100) NOT NULL,       -- 'order-events', 'catalog-events'
    payload        TEXT NOT NULL,               -- JSON
    created_at     DATETIME(6) NOT NULL,
    published      BOOLEAN DEFAULT FALSE,
    published_at   DATETIME(6)
);
```

> `event_id`를 UUID로 생성하는 이유: DB auto-increment ID는 시스템이 다른 DB를 쓰면 충돌 가능.
> UUID는 분산 환경에서도 고유성이 보장된다. Consumer의 `event_handled` 테이블에서 이 값을 PK로 사용.

> `topic` 필드를 추가하는 이유: Outbox Publisher가 `aggregateType`으로 토픽을 분기하면
> 매핑 로직이 Publisher에 묶인다. 저장 시점에 토픽을 확정하면 Publisher는 라우팅 없이 발행만 하면 된다.

**이벤트별 Kafka 발행 방식 (최종)**:

통계성 이벤트(catalog/order)는 유실을 허용하여 `KafkaEventPublishListener`가 `@TransactionalEventListener(AFTER_COMMIT)`에서 직접 발행한다.
유실이 허용되지 않는 쿠폰 발급 요청만 Outbox Pattern을 적용한다.

**직접 발행 (catalog-events, order-events)** — `KafkaEventPublishListener`:
```
@Transactional 비즈니스 메서드
  ├─ 비즈니스 로직
  ├─ publishEvent(ProductLikedEvent / OrderPaidEvent 등)
  │
  ├─ DB COMMIT
  └─ [AFTER_COMMIT] KafkaEventPublishListener.onEvent()  ← 직접 Kafka 발행
         │
         └─ 실패하면? → 이벤트 유실 허용 (통계성이므로 허용)
```

**Outbox Pattern (coupon-issue-requests)** — `CouponIssueFacade` + `OutboxEventListener`:
```
@Transactional CouponIssueFacade
  ├─ memberCouponRepository.save(REQUESTED)  ← 비즈니스 INSERT
  ├─ outboxEventRepository.save(outbox)      ← 같은 TX ✅
  │
  ├─ DB COMMIT
  └─ [AFTER_COMMIT] OutboxEventListener.onPublished()  ← 즉시 Kafka 발행 시도
         │
         └─ 실패하면? → @Scheduled가 미발행 이벤트 재시도 (10초 간격)
```

**OrderTransactionService** — Outbox 없는 순수 TX 래핑:
```
OrderPaymentFacade (TX 없음, 조율만)
  └─ OrderTransactionService.completeOrder()  ← @Transactional 메서드
       ├─ orderService.completeOrder()         ← 이 TX에 참여 (REQUIRED)
       └─ eventPublisher.publishEvent(OrderPaidEvent)  ← AFTER_COMMIT에서 KafkaEventPublishListener 트리거
```

**이벤트별 발행 방식 (최종)**:

| 이벤트 | 발행 방식 | 발행 위치 | 이유 |
|---|---|---|---|
| `ProductLikedEvent` | **직접 발행** | `KafkaEventPublishListener` (AFTER_COMMIT) | 통계성, 유실 허용 |
| `ProductViewedEvent` | **직접 발행** | `KafkaEventPublishListener` (AFTER_COMMIT) | 통계성, 유실 허용 |
| `OrderPaidEvent` (전액 할인) | **직접 발행** | `KafkaEventPublishListener` (AFTER_COMMIT) | 통계성, 유실 허용. `OrderTransactionService`에서 TX 래핑 후 이벤트 발행 |
| `OrderPaidEvent` (결제 성공) | **직접 발행** | `KafkaEventPublishListener` (AFTER_COMMIT) | 통계성, 유실 허용. `OrderTransactionService`에서 TX 래핑 후 이벤트 발행 |
| 쿠폰 발급 요청 | **Outbox Pattern** | `OutboxEventListener` (AFTER_COMMIT 즉시 + 스케줄러 재시도) | 유실 불허. MemberCoupon INSERT + OutboxEvent INSERT 같은 TX |

> **catalog/order-events에 Outbox를 쓰지 않는 이유**:
> `product_metrics`(좋아요/조회/판매량 집계)는 통계성 데이터로, 간헐적 유실이 비즈니스 정합성에 치명적이지 않다.
> `@TransactionalEventListener(AFTER_COMMIT)` 만으로도 TX 커밋 후 발행이 보장되며, Kafka 자체 retries(3회)로 일시적 장애를 방어한다.
> Outbox를 도입하면 outbox_event 테이블 크기 관리, 스케줄러 모니터링 등 운영 비용이 발생한다.

**OrderTransactionService 구조** (Outbox 없는 순수 TX 래핑):
```java
@RequiredArgsConstructor
@Component
public class OrderTransactionService {
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final ApplicationEventPublisher eventPublisher;

    // 전액 할인: 주문 완료 + OrderPaidEvent 발행
    @Transactional
    public void completeOrder(Long orderId, Long memberId, int totalAmount,
                               List<OrderPaidEvent.OrderedProduct> orderedProducts) {
        orderService.completeOrder(orderId);
        eventPublisher.publishEvent(new OrderPaidEvent(orderId, memberId, totalAmount, orderedProducts));
        // AFTER_COMMIT에서 KafkaEventPublishListener가 Kafka 발행
    }

    // 결제 성공: 결제 상태 업데이트 + OrderPaidEvent 발행
    @Transactional
    public PaymentStatus updatePaymentStatus(Long paymentId, PaymentGatewayResponse response,
                                              Long orderId, Long memberId, int totalAmount,
                                              List<OrderPaidEvent.OrderedProduct> orderedProducts) {
        paymentService.updatePaymentStatus(paymentId, response);
        Payment updated = paymentService.findPayment(paymentId);
        if (updated.getStatus() == PaymentStatus.SUCCESS) {
            eventPublisher.publishEvent(new OrderPaidEvent(orderId, memberId, totalAmount, orderedProducts));
        }
        return updated.getStatus();
    }
}
```

**KafkaEventPublishListener 구조** (catalog/order 직접 발행):
```java
@Component
public class KafkaEventPublishListener {
    private final KafkaTemplate<Object, Object> kafkaTemplate;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishProductLiked(ProductLikedEvent event) {
        kafkaTemplate.send("catalog-events", String.valueOf(event.productId()), serialize(event));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publishProductViewed(ProductViewedEvent event) {
        kafkaTemplate.send("catalog-events", String.valueOf(event.productId()), serialize(event));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publishOrderPaid(OrderPaidEvent event) {
        kafkaTemplate.send("order-events", String.valueOf(event.orderId()), serialize(event));
    }
}
```

**OutboxEventListener 구조** (쿠폰 발급 요청 전용):
```java
@Component
public class OutboxEventListener {
    private final OutboxEventPublisher eventPublisher;
    private final OutboxEventRepository outboxEventRepository;

    // TX 커밋 후 비동기로 즉시 Kafka 발행을 시도한다.
    // @Async로 별도 스레드에서 실행되므로 REQUIRES_NEW로 새 TX를 열어 markPublished()를 반영한다.
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishAfterCommit(OutboxEvent.Published event) {
        OutboxEvent outboxEvent = event.outboxEvent();
        try {
            eventPublisher.publish(outboxEvent);
            outboxEvent.markPublished();
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            log.warn("Outbox 즉시 발행 실패 — 스케줄러가 재시도 예정: eventId={}",
                    outboxEvent.getEventId(), e);
        }
    }
}
```

**Outbox Publisher (스케줄러) — 미발행 쿠폰 이벤트 재시도**:

```java
@Transactional  // 변경 감지(dirty checking)를 위해 필요
@Scheduled(fixedDelay = 10000)  // 10초마다 폴링
public void publishPendingEvents() {
    List<OutboxEvent> events = outboxRepository.findUnpublished();
    for (OutboxEvent event : events) {
        try {
            eventPublisher.publish(event);
            event.markPublished();  // @Transactional 안이므로 변경 감지 동작 ✅
        } catch (Exception e) {
            log.error("Outbox 발행 실패: eventId={}", event.getEventId(), e);
            // 실패한 건은 skip, 다음 스케줄에서 재시도
        }
    }
}
```

> **스케줄러 설계 포인트**:
> - `@Scheduled` 메서드에 `@Transactional`을 추가하여 JPA 변경 감지가 동작하도록 함.
>   `event.markPublished()` 후 별도 `save()` 호출 없이도 TX 커밋 시 UPDATE 반영.
> - for 루프 안에 건별 try-catch를 추가하여, 하나의 발행 실패가 나머지 이벤트의 발행을 막지 않도록 함.
> - `markPublished()`로 `published = true` 업데이트 방식을 채택. 이력 추적 목적.
>   테이블이 커지는 문제는 별도 배치로 오래된 레코드를 정리하여 해결.

**흐름 요약**:
1. **catalog/order 직접 발행**: `ProductFacade`에서 `ProductLikedEvent`/`ProductViewedEvent` 발행,
   `OrderTransactionService`에서 `OrderPaidEvent` 발행 → AFTER_COMMIT에서 `KafkaEventPublishListener`가 Kafka 직접 발행
2. **쿠폰 Outbox Pattern**: `CouponIssueFacade`에서 MemberCoupon INSERT + OutboxEvent INSERT를 같은 TX로 수행
   → AFTER_COMMIT에서 `OutboxEventListener`가 즉시 발행 시도 → 실패 시 @Scheduled가 재시도

> **실패 경로(OrderFailedEvent)는 기존 ApplicationEvent 방식 유지**:
>
> `OrderFailedEvent`는 Kafka 전파 대상이 아니다(결정 3). 기존 그대로
> `ApplicationEventPublisher.publishEvent(new OrderFailedEvent(...))`로 발행하며,
> `OrderEventListener`와 `UserActionEventListener`가 `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)`로 수신한다.

**신규 파일** (commerce-api):
- `domain/outbox/OutboxEvent.java` — Entity (event_id UUID 포함, Published record 추가)
- `domain/outbox/OutboxEventRepository.java` — Repository interface
- `domain/outbox/OutboxEventPublisher.java` — 메시지 발행 interface (DIP)
- `application/event/KafkaEventPublishListener.java` — ProductLikedEvent, ProductViewedEvent, OrderPaidEvent를 @Async + AFTER_COMMIT에서 비동기 Kafka 발행
- `application/event/OutboxEventListener.java` — Published 이벤트 처리 (@Async + REQUIRES_NEW로 즉시 발행, 성공 시 markPublished()로 중복 방지)
- `application/order/OrderTransactionService.java` — Outbox 없는 순수 TX 래핑 (주문 완료/결제 성공 + 이벤트 발행)
- `application/outbox/OutboxScheduler.java` — @Scheduled 스케줄러 (미발행 재시도, LIMIT 100 적용)
- `infrastructure/outbox/OutboxEventRepositoryImpl.java` — JPA 구현
- `infrastructure/outbox/OutboxEventJpaRepository.java` — Spring Data JPA
- `infrastructure/outbox/KafkaOutboxEventPublisher.java` — Kafka 발행 구현체

**수정 파일** (commerce-api):
- `OrderPaymentFacade.java` — `OrderTransactionService` 사용, `OrderPaidEvent`로 Kafka 발행 위임
- `ProductFacade.java` — Outbox 관련 코드 제거, ObjectMapper 의존 제거. `ProductLikedEvent`/`ProductViewedEvent` 발행만 수행

#### 2-4. Kafka Producer 설정

**kafka.yml 수정**:
```yaml
producer:
  acks: all                    # 모든 복제본 확인 → 유실 방지
  properties:
    enable.idempotence: true   # 네트워크 재시도 중복 방지 (Producer → Kafka 구간)
```

**토픽 설계** (결정 3, 4 반영):

| 토픽 | Partition Key | 발행 이벤트 | Consumer 처리 |
|------|-------------|-----------|-------------|
| `catalog-events` | productId | `ProductLikedEvent`, `ProductViewedEvent` | like_count, view_count 집계 |
| `order-events` | orderId | `OrderPaidEvent` | order_count 집계 |
| `coupon-issue-requests` | couponId | 쿠폰 발급 요청 (Step 3) | 선착순 쿠폰 발급 |

> `OrderFailedEvent`는 Kafka로 전파하지 않는다. commerce-api에서 보상 트랜잭션으로 이미 처리 완료되며, Consumer에서 추가 집계할 대상이 없다.

#### 2-5. Kafka 메시지 구조 (공유 DTO)

Producer(commerce-api)와 Consumer(commerce-streamer)가 같은 Kafka 메시지 구조를 공유해야 함.

**결정: 각 앱에서 독립적으로 같은 구조의 DTO 정의**

**고민**: `modules/kafka`에 공유 DTO를 두면 실용적이지만, modules는 설정(config) 전용 모듈이라 DTO를 넣으면 모듈의 역할이 섞인다. 레퍼런스 PR은 단일 모듈 프로젝트라 `support` 패키지에 공유했지만, 우리는 멀티 모듈 구조.

**선택지**:
- A. `modules/kafka`에 공유 DTO → 모듈 역할 혼재 (config + DTO)
- B. 각 앱에서 독립 DTO → 느슨한 결합, 같은 레포라 동기화 부담 적음
- C. `supports/` 하위에 새 모듈 → 깔끔하지만 새 모듈 + build.gradle.kts 수정 필요

**결정: B** — 각 앱에서 독립 정의. 같은 JSON 스키마를 공유하되 DTO 클래스는 각자 소유.
모듈의 역할을 지키면서도 같은 레포지토리 안이라 동기화 부담이 크지 않다.

**메시지 payload 예시**:

OrderPaidEvent (리뷰 이슈 6 반영 — 상품 목록 포함):
```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "ORDER_PAID",
  "aggregateId": "1",
  "occurredAt": "2026-03-25T10:30:00",
  "data": {
    "orderId": 1,
    "memberId": 100,
    "totalAmount": 50000,
    "orderedProducts": [
      {"productId": 10, "quantity": 2},
      {"productId": 20, "quantity": 1}
    ]
  }
}
```

> **리뷰 이슈 6 반영**: 기존 `OrderPaidEvent(orderId, memberId, totalAmount)`에는 어떤 상품이 몇 개 팔렸는지
> 정보가 없어 Consumer가 `product_metrics.order_count`를 업데이트할 수 없었다.
>
> **검토한 선택지**:
> - A. 이벤트 payload에 상품 목록 포함 — 이벤트가 자기 완결적. Consumer가 DB 조회 불필요
> - B. Consumer가 orderId로 DB 조회 — 기존 구조 변경 없지만, Consumer가 orders 테이블에 결합 (소유권 위반)
> - C. order_count를 전체 주문 건수로 재정의 — 단순하지만 상품별 판매량 파악 불가
>
> **결정: A** — `OrderPaidEvent`에 `List<OrderedProduct>(productId, quantity)` 추가.
> Consumer는 `orderedProducts`를 순회하면서 각 productId의 `order_count`를 `+quantity`로 집계.
> 이 정보는 `OrderPlacementService.placeOrder()` 시점에 이미 존재하므로 추가 조회 불필요.
>
> **도메인 이벤트 record 변경**:
> ```java
> // 변경 전
> public record OrderPaidEvent(Long orderId, Long memberId, int totalAmount) {}
>
> // 변경 후
> public record OrderPaidEvent(Long orderId, Long memberId, int totalAmount,
>                               List<OrderedProduct> orderedProducts) {
>     public record OrderedProduct(Long productId, int quantity) {}
> }
> ```

ProductLikedEvent:
```json
{
  "eventId": "...",
  "eventType": "PRODUCT_LIKED",
  "aggregateId": "10",
  "occurredAt": "2026-03-25T10:31:00",
  "data": {
    "productId": 10,
    "memberId": 100,
    "liked": true
  }
}
```

ProductViewedEvent:
```json
{
  "eventId": "...",
  "eventType": "PRODUCT_VIEWED",
  "aggregateId": "10",
  "occurredAt": "2026-03-25T10:32:00",
  "data": {
    "productId": 10,
    "memberId": null
  }
}
```

> **ProductViewedEvent의 memberId가 null** (리뷰 이슈 7):
> 비로그인 조회 시 memberId가 null로 발행된다. 집계(view_count +1)에는 memberId가 불필요하므로
> Consumer에서 null 처리만 유의하면 된다. 의도된 동작.

#### 2-6. Consumer (commerce-streamer 확장)

**event_handled 테이블** (멱등 처리):

```sql
CREATE TABLE event_handled (
    event_id   VARCHAR(36) PRIMARY KEY,  -- Outbox의 event_id (UUID)
    handled_at DATETIME(6) NOT NULL
);
```

**product_metrics 테이블** (집계) — 결정 5 반영: 기존 `Product.likeCount`와 별도로 운영:

```sql
CREATE TABLE product_metrics (
    product_id  BIGINT PRIMARY KEY,
    like_count  BIGINT DEFAULT 0,
    view_count  BIGINT DEFAULT 0,
    order_count BIGINT DEFAULT 0,
    updated_at  DATETIME(6)
);
```

> **기존 Product.likeCount와의 관계**: commerce-api의 `Product.likeCount`는 실시간 반영(API 응답, 정렬용)으로 유지.
> `product_metrics`는 비동기 집계(통계/분석용)로 분리 운영. 두 값이 일시적으로 불일치할 수 있으나
> 역할이 다르므로 허용한다.

**event_handled, product_metrics 코드 소유권** — 결정 6 반영:
- 두 테이블 모두 같은 DB(`localhost:3306/loopers`)에 위치
- 엔티티와 Repository 코드는 **commerce-streamer에 배치**하여 소유권 명확히 함

**Consumer 리스너 전략 → 단건 리스너**:

**고민**: 기존 `BATCH_LISTENER`(3000건 배치)를 사용할지, 단건 리스너를 추가할지.

**배치 리스너의 복잡성**: 3000건 중 500번째에서 예외 발생 시 ACK 안 됨 → 전체 재수신 → 앞의 499건 중복 처리(event_handled로 skip은 되지만 비효율). 부분 실패 처리 로직이 복잡해짐.

**결정: 단건 리스너** — `ConcurrentKafkaListenerContainerFactory` 별도 Bean 추가.
과제에서 배치 처리는 Nice-to-Have이고, 핵심은 멱등 처리 + manual ACK 구현.
단건 리스너는 실패 시 해당 메시지만 재수신되어 멱등 처리가 단순하고 직관적.

**단건 리스너 Factory Bean 위치** (리뷰 이슈 5 반영):
`modules/kafka`의 `KafkaConfig`에 추가하면 commerce-api에도 불필요하게 노출된다.
**commerce-streamer에 자체 Config 클래스(`MetricsKafkaConfig`)를 추가**하여 단건 팩토리를 정의한다.
소유권이 명확하고, Consumer 전용 설정을 streamer가 관리할 수 있다.

**Nice-to-Have 확장**: `catalog-events`(조회 이벤트)는 트래픽이 가장 높으므로,
배치 전환 시 같은 productId 이벤트를 모아서 `UPDATE SET view_count = view_count + N`으로
DB 호출을 줄이는 효과가 크다. 단건으로 먼저 구현 후 필요 시 확장.

**Consumer 처리 흐름** (단건 기준):
```
메시지 수신 (event_id = "abc-123")
│
├─ event_handled에 "abc-123" 있는가?
│   ├─ YES → skip, ACK
│   └─ NO
│        │
│     BEGIN TX
│       ① INSERT INTO event_handled (event_id, handled_at)
│       ② product_metrics upsert (like_count +1 등)
│     COMMIT
│        │
│     manual ACK (acknowledgment.acknowledge())
```

> event_handled INSERT와 비즈니스 로직이 **같은 트랜잭션**이어야 함.
> 비즈니스 로직 실패 시 handled 기록도 롤백 → "처리 안 했는데 처리했다고 기록되는" 상황 방지.

**product_metrics upsert 방식**:
- 좋아요/조회 이벤트: 매 이벤트를 **증감(+1/-1)** 처리
- 순서 보장: 같은 productId는 같은 파티션 → 같은 Consumer가 순서대로 처리
- `version`/`updated_at` 비교는 상태 덮어쓰기 방식에서 필요, 증감 방식에서는 멱등 처리(event_handled)로 충분

**Consumer Group**:
- `metrics-group` — 집계 처리 전용
- Nice-to-Have: 관심사별 그룹 분리 (예: `notify-group`)

**신규 파일** (commerce-streamer):
- `config/MetricsKafkaConfig.java` — 단건 리스너 ContainerFactory Bean 정의 (streamer 전용)
- `domain/event/EventHandled.java` — Entity
- `domain/event/EventHandledRepository.java` — Repository interface
- `domain/metrics/ProductMetrics.java` — Entity
- `domain/metrics/ProductMetricsRepository.java` — Repository interface
- `infrastructure/event/EventHandledRepositoryImpl.java` — JPA 구현
- `infrastructure/metrics/ProductMetricsRepositoryImpl.java` — JPA 구현
- `application/metrics/MetricsEventService.java` — Consumer 비즈니스 로직 + TX 관리 (구현 리뷰 반영)
- `interfaces/consumer/OrderEventConsumer.java` — order-events 처리
- `interfaces/consumer/CatalogEventConsumer.java` — catalog-events 처리

#### 2-7. 구현 후 코드 리뷰 반영 사항

구현 완료 후 코드 리뷰에서 3가지 이슈가 발견되어 수정했다.

##### 이슈 1 (높음): Outbox payload에 eventId/eventType이 포함되지 않음

**문제**: Producer에서 Outbox payload를 생성할 때 `data` 필드만 직렬화하여 저장했다.
Consumer는 `{"eventId":"...", "eventType":"...", "data":{...}}` 엔벨로프 구조를 기대하고
`payload.path("eventId")`로 파싱하므로, 항상 null이 되어 **모든 메시지가 skip**되었다.

```
Producer가 저장한 payload:
  {"productId": 10, "memberId": 100, "liked": true, "occurredAt": "..."}

Consumer가 기대한 payload:
  {"eventId": "uuid-...", "eventType": "PRODUCT_LIKED", "data": {"productId": 10, ...}}
```

**해결**: payload 생성 시 `buildEnvelope()` 헬퍼를 추가하여 eventId, eventType, data를 감싸는
엔벨로프 구조로 직렬화한다. eventId는 `OutboxEvent.create()` 후에 확정되므로,
`OutboxEvent.updatePayload()`를 추가하여 엔벨로프 조립 후 payload를 갱신한다.

```java
// 수정 후 — buildEnvelope()로 엔벨로프 구조 생성
OutboxEvent outboxEvent = OutboxEvent.create(..., ""); // payload 빈 문자열
Map<String, Object> data = Map.of("productId", productId, ...);
outboxEvent.updatePayload(
    serializePayload(buildEnvelope(outboxEvent.getEventId(), "PRODUCT_LIKED", data))
);

private Map<String, Object> buildEnvelope(String eventId, String eventType, Object data) {
    return Map.of("eventId", eventId, "eventType", eventType, "data", data);
}
```

**수정 파일**:
- `OutboxEvent.java` — `updatePayload()` 메서드 추가
- `CouponIssueFacade.java` — `buildEnvelope()` 추가, 쿠폰 Outbox payload 엔벨로프 구조로 수정

##### 이슈 2 (중간): @Transactional + @KafkaListener에서 ACK가 TX 커밋 전에 호출됨

**문제**: Consumer에 `@Transactional`과 `@KafkaListener`가 동시에 걸려 있으면,
`acknowledgment.acknowledge()`가 메서드 안에서(TX 커밋 전에) 호출된다.
TX 커밋이 실패하면 Kafka는 이미 ACK를 받아 재전달하지 않으므로 메시지가 유실된다.

```
문제 흐름:
  @Transactional + @KafkaListener
    ├─ 비즈니스 로직 수행
    ├─ event_handled INSERT
    ├─ acknowledgment.acknowledge()  ← TX 커밋 전에 ACK 전송
    └─ TX 커밋 시도 → 실패 ❌
       → Kafka는 ACK를 받았으므로 재전달 안 함 → 메시지 유실
```

**해결**: Consumer에서 `@Transactional`을 제거하고, `MetricsEventService` 서비스 레이어에 TX를 위임한다.
서비스 메서드 리턴 = TX 커밋 완료이므로, 그 후에 ACK를 전송하면 안전하다.

```
수정 후 흐름:
  @KafkaListener (TX 없음)
    ├─ metricsEventService.processXxx()  ← @Transactional 시작/커밋
    └─ acknowledgment.acknowledge()      ← TX 커밋 완료 후 ACK 전송
```

**수정 파일**:
- `MetricsEventService.java` — **신규** (application/metrics/). `@Transactional` 메서드로 event_handled INSERT + product_metrics upsert 수행
- `CatalogEventConsumer.java` — `@Transactional` 제거, `MetricsEventService`에 위임
- `OrderEventConsumer.java` — `@Transactional` 제거, `MetricsEventService`에 위임

##### 이슈 3 (낮음): OutboxEventListener 즉시 발행 실패 시 로그 명확화

**문제**: `OutboxEventListener.onPublished()`에서 Kafka 발행 실패 시 로그 메시지가 부정확하면
"스케줄러가 재시도 예정인지 아닌지"를 판단하기 어렵다.

**해결**: 즉시 발행 실패는 스케줄러 재시도가 가능하므로 (DB에 outbox_event 레코드가 있음),
`warn` 레벨로 "스케줄러가 재시도 예정"임을 명확히 로그에 남긴다.

```java
// 수정 후
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onPublished(OutboxEvent.Published event) {
    try {
        eventPublisher.publish(event.outboxEvent());
    } catch (Exception e) {
        log.warn("Outbox 즉시 발행 실패 — 스케줄러가 재시도 예정: eventId={}",
                event.outboxEvent().getEventId(), e);
        // outbox_event.published = false 유지 → 스케줄러가 재시도
    }
}
```

**수정 파일**: `OutboxEventListener.java` — `onPublished()` 메서드 로그 메시지 명확화

#### 2-8. 설계 결정 포인트 요약

| # | 고민 | 결정 | 이유 |
|---|------|------|------|
| 1 | 이벤트별 Kafka 발행 방식 | catalog/order → **직접 발행** (KafkaEventPublishListener), 쿠폰 → **Outbox Pattern** | 통계성 이벤트는 유실 허용. 쿠폰은 유실 불허 → 이벤트 중요도로 분리 |
| 2 | event_id 생성 | UUID | 분산 환경에서 고유성 보장 |
| 3 | 메시지 DTO 위치 | 각 앱에서 독립 정의 | modules는 config 전용. DTO를 넣으면 역할 혼재 |
| 4 | Consumer 리스너 전략 | 단건 리스너 추가 | 멱등 처리 단순. 배치는 Nice-to-Have로 확장 |
| 5 | product_metrics 집계 방식 | 증감(+1/-1) + event_handled 멱등 | 상태 덮어쓰기 불필요. 멱등 처리로 중복 방지 |
| 6 | Outbox payload 구조 | 엔벨로프 (eventId + eventType + data) | Consumer가 eventId로 멱등 처리, eventType으로 분기 |
| 7 | Consumer TX와 ACK 순서 | 서비스 레이어에 TX 위임, ACK는 TX 커밋 후 | TX 커밋 실패 시 ACK 미전송 → Kafka 재전달 보장 |

---

### Step 3 — Kafka 기반 선착순 쿠폰 발급

#### 설계 결정 사항

| 항목 | 결정 | 근거 |
|------|------|------|
| 엔드포인트 | 기존 동기 발급과 별도 API로 분리 | 동기/비동기 응답 방식이 다름 (200 vs 202) |
| Redis | 사용 안 함, DB only | 선착순 100장 수준이면 DB로 충분. 장애 포인트 최소화 |
| Outbox | **사용함** (Outbox Pattern) | `member_coupon` INSERT(REQUESTED) + `outbox_event` INSERT를 같은 TX로 묶어 원자성 보장. AFTER_COMMIT에서 즉시 발행 시도 + 스케줄러 재시도 안전망. 쿠폰은 유실 시 유저가 REQUESTED 상태에서 영구 대기 → 허용 불가 |
| 상태 추적 | `member_coupon` 테이블 활용 | 별도 테이블 없이 `MemberCouponStatus`에 `REQUESTED`/`FAILED` 상태 추가 |
| 동시성 제어 | Kafka partition key=couponId → 순차 처리 | 같은 쿠폰 요청은 같은 파티션에서 순차 처리되므로 race condition 없음. Consumer concurrency는 토픽 파티션 수 이하로 설정 |
| Consumer 엔티티 | streamer에 `Coupon`, `MemberCoupon`, `MemberCouponStatus` 복제 | 같은 DB를 공유하므로 직접 조회/UPDATE 가능. 기존 streamer 패턴과 일관성 유지 |
| 실패 사유 | `member_coupon`에 `fail_reason` 컬럼 추가 (nullable) | enum 세분화보다 단순하고, 사유 추가에 유연 |

#### 3-1. 발급 요청 API (선착순 전용, 기존 동기 발급과 별도)

```
POST /api/v1/coupons/{couponId}/issue-request
Header: X-Loopers-LoginId
Response: 202 Accepted (비동기 처리)
```

**처리 흐름**:
```
@Transactional (CouponIssueFacade)
1. member_coupon 중복 체크 (member_id + coupon_id)
   - 이미 REQUESTED/AVAILABLE 상태 존재 → 거부 (409 CONFLICT)
2. member_coupon INSERT (status=REQUESTED) — 정적 팩토리 메서드 MemberCoupon.createRequested(memberId, couponId)
3. outbox_event INSERT (topic=coupon-issue-requests, key=couponId) — 같은 TX ✅
   - INSERT 실패 시 → TX 롤백 → member_coupon INSERT도 롤백 → 원자성 보장
4. DB COMMIT
5. [AFTER_COMMIT] OutboxEventListener → KafkaOutboxEventPublisher.publish() → 즉시 Kafka 발행 시도
   - 발행 실패 시 → outbox_event.published = false 유지 → 스케줄러(10초)가 재시도
6. return 202 Accepted
```

**중복 요청 방어**:
- API 레벨: INSERT 전에 기존 레코드 조회하여 REQUESTED/AVAILABLE 상태면 거부
- DB 레벨: `member_coupon` 테이블의 `UK(member_id, coupon_id)` 제약이 최종 안전망
- Consumer 레벨: UK 위반 시 `DataIntegrityViolationException` → FAILED 처리 없이 skip + ACK

**이벤트 발행 방식 선택 — 직접 발행 vs Outbox Pattern 비교**:

이 프로젝트에서는 이벤트 중요도에 따라 발행 방식을 나눈다. 선착순 쿠폰에는 **Outbox Pattern**을 선택했다.

| 방식 | 사용처 | Kafka 발행 시점 | 원자성 | 유실 가능성 |
|------|--------|----------------|--------|------------|
| **직접 발행** (KafkaEventPublishListener) | `ProductFacade`, `OrderTransactionService` | AFTER_COMMIT에서 즉시 | 미보장 (AFTER_COMMIT 실패 시 유실) | 있음 (통계성이므로 허용) |
| **Outbox Pattern** | `CouponIssueFacade` | AFTER_COMMIT 즉시 시도 + 스케줄러 재시도 | 보장 (MemberCoupon + OutboxEvent 같은 TX) | 없음 (스케줄러 재시도) |

왜 직접 발행을 사용하지 않는가:
- 직접 발행에서 AFTER_COMMIT 리스너가 Kafka 발행에 실패하면 이벤트가 유실된다
- 유실 = 유저가 영원히 REQUESTED 상태에서 대기 → 허용 불가

Outbox Pattern의 트레이드오프:
- 장점: MemberCoupon INSERT + OutboxEvent INSERT가 같은 TX → 원자성 보장
- 장점: Kafka 발행 실패 시 스케줄러가 재시도 → At Least Once 보장
- 장점: AFTER_COMMIT에서 즉시 발행도 시도하므로, 스케줄러 10초 지연 없이 빠른 처리 가능
- 단점: outbox_event 테이블 관리 (정기 정리 배치 필요)

#### 3-2. Consumer 선착순 로직 (commerce-streamer)

**동시성 제어 전략**: Kafka partition key=couponId → 같은 쿠폰 요청은 순차 처리

```
1. Consumer가 메시지 수신
2. event_handled 확인 → 중복이면 skip + ACK
3. member_coupon 레코드 조회 (REQUESTED 상태)
   - 레코드 없음 → DB 커밋 실패 케이스, skip + ACK
4. Coupon 조회 → maxIssueCount 확인
5. SELECT COUNT(*) FROM member_coupon WHERE coupon_id=? AND status IN (AVAILABLE, USED)
   - 수량 초과 → member_coupon status=FAILED, fail_reason="수량 초과" + ACK
   - 수량 이내 → member_coupon status=AVAILABLE + ACK
6. event_handled INSERT (멱등 처리)
```

**Coupon 엔티티 확장**:
- `maxIssueCount` 필드 추가 (선착순 수량, 0이면 수량 제한 없는 일반 쿠폰)

**MemberCoupon 엔티티 확장**:
- `MemberCouponStatus`에 `REQUESTED`, `FAILED` 상태 추가
- `fail_reason` 컬럼 추가 (nullable)
- 기존 생성자는 `status=AVAILABLE`로 고정 (기존 동기 발급 유지)
- 선착순 요청용 정적 팩토리 메서드 추가: `MemberCoupon.createRequested(memberId, couponId)` → `status=REQUESTED`

#### 3-3. 발급 결과 확인 API (Polling)

```
GET /api/v1/coupons/{couponId}/issue-status
Header: X-Loopers-LoginId
Response: { status: "REQUESTED" | "AVAILABLE" | "FAILED", failReason: "..." }
```

- `member_coupon` 테이블 조회로 상태 반환
- 레코드 없음 → 요청 안 함

#### 3-4. 동시성 테스트

- `CountDownLatch` + `ExecutorService`로 동시 요청 시뮬레이션
- 100장 한정 쿠폰에 대해 초과 발급이 발생하지 않는지 검증

**신규 파일**:
- `domain/coupon/CouponIssueService.java` — 발급 로직 (commerce-api)
- `interfaces/api/coupon/CouponIssueV1Controller.java` — 발급 요청/Polling API (commerce-api)
- `domain/coupon/Coupon.java` — 엔티티 복제 (commerce-streamer)
- `domain/coupon/MemberCoupon.java` — 엔티티 복제 (commerce-streamer)
- `interfaces/consumer/CouponIssueConsumer.java` — Consumer (commerce-streamer)
- 동시성 테스트 코드

#### 구현 순서

| 순서 | 작업 | 의존성 |
|------|------|--------|
| 9 | Coupon에 `maxIssueCount` 추가 + MemberCouponStatus에 `REQUESTED`/`FAILED` 추가 + `fail_reason` 컬럼 | 없음 |
| 10 | 선착순 발급 요청 API + Outbox Pattern (MemberCoupon INSERT + OutboxEvent INSERT 같은 TX) | 9 |
| 11 | streamer에 쿠폰 엔티티 복제 + CouponIssueConsumer 구현 | 10 |
| 12 | Polling API + 동시성 테스트 | 11 |

---

### 구현 순서 (전체)

| 순서 | 작업 | 의존성 |
|------|------|--------|
| 1 | 이벤트 클래스 정의 (`domain/event/`) | 없음 |
| 2 | Step 1: ApplicationEvent + Listener 구현 | 1 |
| 3 | Step 1: 좋아요 집계 이벤트 분리 | 1, 2 |
| 4 | Step 1: 유저 행동 로깅 이벤트 | 1, 2 |
| 5 | Step 2: Outbox 테이블 + Entity + Repository | 없음 |
| 6 | Step 2: Outbox Publisher (스케줄러 + Kafka 발행) | 5 |
| 7 | Step 2: product_metrics + Consumer 구현 | 6 |
| 8 | Step 2: 멱등 처리 (event_handled) | 7 |
| 9 | Step 3: Coupon 엔티티 확장 + MemberCoupon 상태/컬럼 추가 | 없음 |
| 10 | Step 3: 선착순 발급 요청 API + Outbox Pattern (MemberCoupon + OutboxEvent 같은 TX) | 9 |
| 11 | Step 3: streamer 엔티티 복제 + Consumer 선착순 로직 | 10 |
| 12 | Step 3: Polling API + 동시성 테스트 | 11 |

---

### 핵심 설계 판단 포인트

1. **ApplicationEvent vs Kafka 경계**: 같은 JVM 내 부가 로직은 ApplicationEvent, 시스템 간 전파가 필요한 것(집계, 메트릭)은 Kafka
2. **Outbox Pattern**: 비즈니스 트랜잭션과 이벤트 발행의 원자성 보장 — Kafka 직접 발행만 하면 트랜잭션 커밋은 됐는데 Kafka 발행 실패 가능
3. **선착순 쿠폰에서 DB + Kafka 조합**: Kafka partition key=couponId로 순차 처리 + DB COUNT 쿼리로 수량 체크 (Redis 미사용)
4. **`@TransactionalEventListener` phase**: `AFTER_COMMIT`이 기본 — 트랜잭션 성공 후에만 부가 로직 실행

---

## 고민 포인트

Step 2 구현 과정에서 두 가지 이상의 접근을 비교하고 테스트로 차이를 검증한 뒤 하나를 선택한 사례를 정리한다.

### 고민 1: Consumer 멱등 처리 위치 — TOCTOU 문제

**배경**: At Least Once 환경에서 같은 이벤트가 중복 수신될 수 있다. `event_handled` 테이블로 멱등 처리를 하는데, 이 체크를 **어디서** 할 것인가?

**접근 A (초기 구현): Consumer에서 체크 후 서비스 호출**

```java
// Consumer
public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
    if (eventHandledRepository.existsByEventId(eventId)) {  // ① TX 밖에서 체크
        ack.acknowledge();
        return;
    }
    metricsEventService.processProductLiked(eventId, data);  // ② TX 안에서 처리
    ack.acknowledge();
}
```

**문제 발견**: ①과 ② 사이에 TOCTOU(Time of Check to Time of Use) 갭이 존재한다.

```
Thread-1: existsByEventId("uuid-1") → false  ← 아직 INSERT 전
Thread-2: existsByEventId("uuid-1") → false  ← 아직 INSERT 전
Thread-1: processProductLiked("uuid-1") → likeCount +1
Thread-2: processProductLiked("uuid-1") → likeCount +1  ← 중복 집계!
```

Consumer의 체크는 TX 밖에서 실행되므로, 두 스레드가 동시에 체크를 통과하면 둘 다 처리에 진입하여 중복 집계가 발생한다.

**접근 B (최종 구현): Service 내부 TX에서 체크 + UNIQUE 제약 방어**

```java
// MetricsEventService
@Transactional
public boolean processProductLiked(String eventId, JsonNode data) {
    if (eventHandledRepository.existsByEventId(eventId)) {  // TX 안에서 체크
        return false;
    }
    // ... 집계 로직 ...
    try {
        eventHandledRepository.save(new EventHandled(eventId));  // PK UNIQUE 제약
    } catch (DataIntegrityViolationException e) {
        throw e;  // TX 롤백 → 집계도 함께 롤백
    }
    return true;
}
```

```
Thread-1: @Transactional { existsByEventId → false → process → event_handled INSERT ✅ }
Thread-2: @Transactional { existsByEventId → false → process → event_handled INSERT → UNIQUE 위반 → TX 롤백 }
→ Thread-2의 집계도 롤백되므로 중복 집계 방지 ✅
```

**테스트로 검증한 내용** (`MetricsEventServiceTest` — `@Nested: 고민 1: 멱등 처리 위치 비교`):

| 테스트 케이스 | 검증 내용 |
|---|---|
| `approachA_toctouAllowsDuplicateProcessing` | **[접근 A 문제 재현]** Consumer에서 체크 후 서비스를 같은 eventId로 2번 호출 → 멱등 체크가 TX 밖이므로 likeCount가 2가 됨 (중복 집계 발생) |
| `approachB_skipAlreadyHandled_insideTransaction` | **[접근 B 해결 1차]** TX 내부에서 이미 처리된 이벤트 감지 → skip하고 false 반환 |
| `approachB_uniqueConstraintPreventsduplicateProcessing` | **[접근 B 해결 2차]** 동시 요청으로 두 TX가 모두 existsByEventId → false를 받아도, UNIQUE 제약 위반 시 TX 전체 롤백 → 집계도 롤백되어 중복 방지 |

**결정: 접근 B** — `event_handled` PK UNIQUE 제약을 최종 방어선으로 사용하여 race condition 없이 정확한 멱등 처리를 보장한다.

| | 접근 A (Consumer에서 체크) | 접근 B (Service TX 내부 체크) |
|---|---|---|
| 멱등 체크 위치 | Consumer — TX 밖 | Service — `@Transactional` 안 |
| TOCTOU 방어 | 불가 | UNIQUE 제약으로 최종 방어 |
| 중복 집계 가능성 | 있음 (race condition) | 없음 (UNIQUE 위반 시 TX 롤백) |
| 코드 복잡도 | 단순 | 약간 복잡 (예외 처리 추가) |

---

### 고민 2: Kafka 발행 방식 — 동기 vs 비동기

**배경**: `OutboxScheduler`가 미발행 이벤트를 Kafka로 발행할 때, 동기(`get()`)와 비동기(`whenComplete`) 중 어떤 방식을 사용할 것인가?

**접근 A — 비동기 발행 (whenComplete 콜백)**

```java
kafkaTemplate.send(topic, key, payload)
    .whenComplete((result, ex) -> {
        if (ex == null) {
            // 성공: markPublished() 호출 → 하지만 콜백 스레드에서 별도 TX 필요
        } else {
            log.error("발행 실패", ex);
        }
    });
// 즉시 리턴 — 호출 스레드 블로킹 없음
```

- 장점: 100건을 거의 즉시 발행 요청 가능. 스케줄러 스레드가 블로킹되지 않음
- 단점: 콜백에서 `markPublished()`를 호출하려면 별도 TX를 열어야 함. 콜백 스레드에서 JPA 영속성 컨텍스트를 관리하는 복잡도 추가. 발행 실패를 호출자가 즉시 알 수 없음

**접근 B — 동기 발행 (.get() 블로킹)**

```java
kafkaTemplate.send(topic, key, payload).get(5, TimeUnit.SECONDS);
// 발행 완료까지 블로킹 — 성공/실패 즉시 확인
```

- 장점: 발행 결과를 즉시 알 수 있어 `markPublished()` 호출이 명확. 실패 시 예외 전파 → 스케줄러 try-catch에서 처리 가능
- 단점: 건별 최대 5초 블로킹. 100건 순차 발행 시 최대 500초 소요

**테스트로 검증한 내용** (`KafkaOutboxEventPublisherTest`):

| 테스트 케이스 | 검증 내용 |
|---|---|
| `asyncFailure_callerCannotDetect` | **[접근 A 문제 재현]** 비동기 발행 실패 시 `send()` 직후에는 `failureDetected`가 false — 호출자가 실패를 즉시 감지할 수 없음. Future가 완료된 후에야 콜백에서 감지. 이 시점에 `markPublished()`를 호출하려면 별도 TX가 필요 |
| `syncSuccess_immediateConfirmation` | **[접근 B 장점]** 동기 발행 성공 시 메서드 리턴 = 발행 완료 확정 → 바로 `markPublished()` 호출 가능 |
| `syncFailure_immediateException` | **[접근 B 장점]** 동기 발행 실패 시 예외가 호출자에게 즉시 전파 → 스케줄러 try-catch에서 처리 가능 |
| `failedEvent_remainsUnpublished_forRetry` | **[접근 B 장점]** 실패한 이벤트는 published=false 유지 → 다음 폴링에서 재시도 대상 |

**결정: 접근 B (동기 발행)** — `markPublished()`를 같은 TX 안에서 확정적으로 처리하는 것이 구조적으로 자연스럽고, LIMIT 100으로 처리량을 제한하고 있어 최악의 경우에도 영향이 제한적이다. 대규모 트래픽에서 성능 이슈가 발생하면 비동기 + 배치 ACK 방식으로 전환 가능.

| | 접근 A (비동기 whenComplete) | 접근 B (동기 .get()) |
|---|---|---|
| 블로킹 | 없음 (즉시 리턴) | 건별 최대 5초 |
| markPublished 타이밍 | 콜백에서 별도 TX 필요 | 리턴 후 같은 TX에서 호출 |
| 에러 전파 | 호출자에게 전파 안 됨 | 예외로 즉시 전파 |
| 구현 복잡도 | 높음 (콜백 TX 관리) | 낮음 |

---

### 고민 3: 발행 완료 후 Outbox 처리 — 삭제 vs published 업데이트

**배경**: 스케줄러가 Kafka 발행에 성공한 후, Outbox 레코드를 어떻게 처리할 것인가?

**접근 A — 삭제**

```java
eventPublisher.publish(event);
outboxRepository.delete(event);  // 레코드 삭제
```

- 장점: Outbox 테이블이 항상 깨끗. 미발행 이벤트만 남아 있어 쿼리 성능 좋음
- 단점: 발행 이력이 사라져 디버깅/감사 추적 불가. "이 이벤트가 언제 발행됐는지" 확인 불가. 장애 상황에서 "이 이벤트가 정말 발행됐는지" 검증 불가

**접근 B — published 업데이트**

```java
eventPublisher.publish(event);
event.markPublished();  // published = true, publishedAt = now()
```

- 장점: 발행 이력 추적 가능. 장애 분석 시 published/unpublished 비교로 문제 파악 가능. "이 이벤트가 언제 발행됐는지" DB에서 즉시 확인 가능
- 단점: 테이블이 계속 커짐. 주기적 정리(배치 삭제)가 필요

**테스트로 검증한 내용** (`OutboxSchedulerTest` — `@Nested: 고민 3: 발행 완료 후 처리`):

| 테스트 케이스 | 검증 내용 |
|---|---|
| `approachA_deleteDestroysAuditTrail` | **[접근 A 문제 재현]** 삭제 방식이면 레코드가 null이 되어 발행 이력(eventId, payload, 발행 시각)을 추적할 수 없음. 반면 published 방식이었다면 이 정보가 DB에 남아있음 |
| `approachB_publishedRetainsAuditTrail` | **[접근 B 장점]** published=true로 변경 후에도 eventId, eventType, payload, publishedAt을 모두 조회 가능 |
| `approachB_unpublishedQueryForTroubleshooting` | **[접근 B 장점]** 2건 중 1건 실패 시, published=true/false 상태로 성공/실패 현황을 한눈에 파악 가능. 삭제 방식이었다면 성공한 이벤트가 사라져 비교 분석 불가 |

**결정: 접근 B (published 업데이트)** — Outbox Pattern 도입 초기에는 이벤트 흐름을 추적할 수 있어야 한다. 삭제 방식은 되돌릴 수 없지만, published 방식은 필요 시 삭제로 전환 가능하다 (반대는 불가). 테이블 비대화는 별도 배치로 오래된 published 레코드를 삭제하여 관리.

| | 접근 A (삭제) | 접근 B (published 업데이트) |
|---|---|---|
| 테이블 크기 | 항상 깨끗 | 계속 증가 (배치 정리 필요) |
| 이력 추적 | 불가 | 발행 시각/이벤트 내용 확인 가능 |
| 장애 분석 | 어려움 | published/unpublished 비교 가능 |
| 전환 가능성 | B→A 전환 가능 | A→B 전환 불가 (이미 삭제됨) |

---

## 테스트 구현 현황

### 테스트 피라미드 구성

```
                     /\
                    /E2E\           CouponIssueV1ApiE2ETest (6건)
                   /______\
                  /        \
                 / Integration\     CouponIssue, Outbox, Metrics, 동시성 (26건)
                /______________\
               /                \
              /   Unit Tests     \  Domain + Application + Interfaces (110건+)
             /____________________\
```

### 신규 작성 테스트 목록

#### 1. 선착순 쿠폰 동시성 테스트 (Step 3 체크리스트 필수)

| 파일 | 유형 | 테스트 메서드 | 검증 대상 |
|------|------|-------------|----------|
| `CouponIssueConcurrencyTest` (streamer) | Integration + 동시성 | `showsRaceConditionWithoutKafkaOrdering` | 100장 한정 쿠폰에 150명 동시 요청 → Kafka 순차 처리 없이는 race condition으로 초과 가능 |
| | | `exactLimitWithSequentialProcessing` | 10장 한정에 30명 순차 요청 → AVAILABLE + FAILED 합이 정확 |
| | | `duplicateEventIdIsProcessedOnce` | 같은 eventId 중복 요청 → 1회만 처리 |

**핵심**: `CountDownLatch` + `ExecutorService`로 동시 요청을 시뮬레이션하여 Kafka partition key=couponId 기반 순차 처리 전략이 DB 레벨에서 수량 초과를 방지하는지 검증.

#### 2. ApplicationEvent 리스너 단위 테스트 (Step 1 누락분)

| 파일 | 유형 | 테스트 메서드 | 검증 대상 |
|------|------|-------------|----------|
| `LikeEventListenerTest` | Unit (Mock) | `incrementsLikeCount_whenLiked` | 좋아요 → likeCount 증가 위임 |
| | | `decrementsLikeCount_whenUnliked` | 취소 → likeCount 감소 위임 |
| | | `suppressesException_whenAggregationFails` | 집계 실패 시 예외 삼킴 (Eventual Consistency) |
| `OrderEventListenerTest` | Unit | `handlesOrderPaidEvent` | OrderPaidEvent 수신 시 예외 없이 처리 |
| | | `handlesOrderFailedEvent` | OrderFailedEvent 수신 시 예외 없이 처리 |
| `UserActionEventListenerTest` | Unit | `logsOrderPaid` | ORDER_PAID 행동 로깅 |
| | | `logsOrderFailed` | ORDER_FAILED 행동 로깅 |
| | | `logsProductLiked` / `logsProductUnliked` | 좋아요/취소 행동 로깅 |
| | | `logsProductViewed` / `logsProductViewed_withNullMemberId` | 조회 행동 로깅 (비로그인 포함) |

**핵심**: 로깅 리스너가 예외를 발생시키지 않아 메인 비즈니스 흐름에 영향을 주지 않는지 검증. `LikeEventListener`는 `ProductService`에 위임하는 행위를 Mock으로 검증.

#### 3. Integration 테스트 (Testcontainers)

| 파일 | 모듈 | 테스트 메서드 수 | 검증 대상 |
|------|------|---------------|----------|
| `CouponIssueServiceIntegrationTest` | commerce-api | 6건 | 발급 요청 DB 저장, 중복 CONFLICT, 만료/비선착순 BAD_REQUEST, 상태 조회 |
| `OutboxEventRepositoryIntegrationTest` | commerce-api | 5건 | findUnpublishedBefore 쿼리 정확성, published 제외, 10초 임계값, limit 적용 |
| `CouponIssueEventServiceIntegrationTest` | commerce-streamer | 4건 | REQUESTED→AVAILABLE 전환, 수량 초과 FAILED, 멱등 skip, member_coupon 없음 skip |
| `MetricsEventServiceIntegrationTest` | commerce-streamer | 6건 | like_count/view_count/order_count 실제 DB upsert, 멱등 skip, 신규 metrics 생성 |

**핵심**: Mock이 아닌 실제 DB(Testcontainers)에서 비즈니스 로직이 올바르게 동작하는지 검증. 특히 `event_handled` UNIQUE 제약, `member_coupon` UK 제약 등 DB 레벨 방어가 실제로 작동하는지 확인.

#### 4. E2E 테스트

| 파일 | 모듈 | 테스트 메서드 수 | 검증 대상 |
|------|------|---------------|----------|
| `CouponIssueV1ApiE2ETest` | commerce-api | 6건 | POST /issue-request → 202, 401, 409 응답 / GET /issue-status → REQUESTED, 404, 401 응답 |

**핵심**: TestRestTemplate으로 HTTP 요청 → Controller → Facade → Service → Repository → Database + Kafka 전체 흐름을 검증. 인증 헤더 검증, 중복 요청 방어, Polling 상태 조회까지 포함.

### 테스트 요약 통계 (신규 작성분)

| 유형 | 파일 수 | 메서드 수 |
|------|---------|----------|
| Unit (이벤트 리스너) | 3 | 11 |
| Integration (API 도메인) | 2 | 11 |
| Integration (Streamer) | 2 | 10 |
| 동시성 (Streamer) | 1 | 3 |
| E2E (API) | 1 | 6 |
| **합계** | **9** | **41** |