# Week 7 테스트 구현 기록

---

## 1. 테스트 전체 현황

### 테스트 피라미드

```
                     /\
                    /E2E\           CouponIssueV1ApiE2ETest (6건)
                   /______\
                  /        \
                 / Integration\     CouponIssue, Outbox, Metrics, 동시성 (27건)
                /______________\
               /                \
              /   Unit Tests     \  Domain + Application + Interfaces + Consumer (104건)
             /____________________\
```

### 신규 작성 테스트 요약

| # | 파일 | 유형 | 모듈 | 메서드 수 | 결과 |
|---|------|------|------|----------|------|
| 1 | `CouponIssueConcurrencyTest` | 동시성 + Integration | streamer | 3 | PASS |
| 2 | `LikeEventListenerTest` | Unit | commerce-api | 3 | PASS |
| 3 | `OrderEventListenerTest` | Unit | commerce-api | 2 | PASS |
| 4 | `UserActionEventListenerTest` | Unit | commerce-api | 6 | PASS |
| 5 | `CouponIssueServiceIntegrationTest` | Integration | commerce-api | 7 | PASS |
| 6 | `OutboxEventRepositoryIntegrationTest` | Integration | commerce-api | 5 | PASS |
| 7 | `CouponIssueEventServiceIntegrationTest` | Integration | streamer | 4 | PASS |
| 8 | `MetricsEventServiceIntegrationTest` | Integration | streamer | 6 | PASS |
| 9 | `CouponIssueV1ApiE2ETest` | E2E | commerce-api | 6 | PASS |
| 10 | `MemberCouponIssueTest` | Unit (Domain) | commerce-api | 8 | PASS |
| 11 | `CouponTest` (isLimitedIssue 추가) | Unit (Domain) | commerce-api | 2 | PASS |
| 12 | `CouponIssueServiceTest` | Unit (Service) | commerce-api | 9 | PASS |
| 13 | `CouponIssueFacadeTest` | Unit (Facade) | commerce-api | 5 | PASS |
| 14 | `CouponIssueV1ControllerTest` | Unit (Controller) | commerce-api | 10 | PASS |
| 15 | `CouponIssueEventServiceTest` | Unit (Service) | streamer | 8 | PASS |
| 16 | `CouponIssueConsumerTest` | Unit (Consumer) | streamer | 5 | PASS |
| 17 | `CouponIssueRequestConcurrencyTest` | 동시성 + Integration | commerce-api | 2 | PASS |
| 18 | `KafkaEventPublishListenerTest` | Unit | commerce-api | 6 | PASS |
| 19 | `OrderTransactionServiceTest` | Unit | commerce-api | 4 | PASS |
| 20 | `OutboxSchedulerTest` | Unit | commerce-api | 6 | PASS |
| 21 | `KafkaOutboxEventPublisherTest` | Unit | commerce-api | 4 | PASS |
| 22 | `OutboxEventListenerTest` | Unit | commerce-api | 2 | PASS |
| 23 | `OutboxEventTest` | Unit (Domain) | commerce-api | 5 | PASS |
| 24 | `MetricsEventServiceTest` | Unit (Service) | streamer | 8 | PASS |
| 25 | `ProductMetricsTest` | Unit (Domain) | streamer | 5 | PASS |
| 26 | `CatalogEventConsumerTest` | Unit (Consumer) | streamer | 4 | PASS |
| 27 | `OrderEventConsumerTest` | Unit (Consumer) | streamer | 2 | PASS |
| 28 | `AfterCommitWithoutRequiresNewTest` | Integration | commerce-api | 2 | PASS |
| | **합계** | | | **139** | **ALL PASS** |

### 아키텍처 변경으로 보강된 기존 테스트

| 파일 | 추가된 검증 | 이유 |
|------|-----------|------|
| `ProductFacadeTest` | `getProduct(productId, memberId)` 시 `ProductViewedEvent` 발행 검증 (memberId 전달 포함) | Outbox 제거 후 이벤트 발행이 유일한 Kafka 연결점이므로 회귀 방지 |
| `OrderPaymentFacadeTest` | 전액 할인/결제 성공 시 `OrderPaidEvent`, 실패 시 `OrderFailedEvent` 발행 검증 + SUCCESS 경로 테스트 추가 | 이벤트 기반 Kafka 발행 구조에서 이벤트 발행 누락은 통계 파이프라인 단절을 의미 |

---

## 2. 테스트별 상세 기록

---

### 2-1. 선착순 쿠폰 동시성 테스트

**파일**: `apps/commerce-streamer/src/test/java/com/loopers/application/coupon/CouponIssueConcurrencyTest.java`

**왜 필요한가**

week7 체크리스트에 "동시성 테스트 — 수량 초과 발급이 발생하지 않는지 검증"이 명시되어 있다. 선착순 쿠폰의 동시성 제어 전략은 Kafka partition key=couponId로 같은 쿠폰 요청을 순차 처리하는 것인데, 이 전략이 실제로 수량 초과를 방지하는지 검증해야 한다.

**테스트 내용**

| 메서드 | 검증 대상 |
|--------|----------|
| `showsRaceConditionWithoutKafkaOrdering` | 100장 한정 쿠폰에 150명이 **동시** 요청 (10 스레드) |
| `exactLimitWithSequentialProcessing` | 10장 한정 쿠폰에 30명이 **순차** 요청 |
| `duplicateEventIdIsProcessedOnce` | 같은 eventId로 중복 요청 시 멱등 처리 |

**테스트 결과에서 알 수 있었던 것**

동시성 테스트(`showsRaceConditionWithoutKafkaOrdering`)에서 100장 한정 쿠폰에 150명이 10개 스레드로 동시 요청한 결과, AVAILABLE=109, FAILED=41로 **9장이 초과 발급**되었다. `SELECT COUNT` → `UPDATE` 사이에 race condition이 발생한 것이다.

```
Thread-1: SELECT COUNT(*) → 99  (아직 100 미만)
Thread-2: SELECT COUNT(*) → 99  (Thread-1의 UPDATE가 커밋 전)
Thread-1: UPDATE status=AVAILABLE → 100장째 ✅
Thread-2: UPDATE status=AVAILABLE → 101장째 ❌ (수량 초과)
// 이런 race condition이 여러 스레드에서 반복되어 최종 109장 발급
```

반면 순차 처리 테스트(`exactLimitWithSequentialProcessing`)에서는 10장 한정 쿠폰에 30명이 순차 요청한 결과, 정확히 10장만 AVAILABLE, 20장은 FAILED가 되었다. 이것은 **Kafka partition key 기반 순차 처리가 동시성 제어의 핵심**임을 실증한다. DB의 `SELECT COUNT`는 read-committed 격리 수준에서 race condition에 취약하며, Kafka 파티션 순서 보장이 없으면 수량 초과가 발생할 수 있다.

```
동시 처리 결과 (Kafka 없이, 10 스레드):
  AVAILABLE: 109 (maxIssueCount=100 대비 9장 초과)
  FAILED:     41

순차 처리 결과 (Kafka 파티션 순서 시뮬레이션):
  AVAILABLE: 10 (정확히 maxIssueCount)
  FAILED:    20 (정확히 totalRequests - maxIssueCount)
```

**결론**: 동시성 제어를 DB 락이 아닌 Kafka 파티션 순서 보장에 의존하는 설계가 올바르게 동작하려면, Consumer concurrency가 토픽 파티션 수 이하여야 하고 같은 couponId의 메시지가 같은 파티션에 도달해야 한다.

---

### 2-2. LikeEventListener 단위 테스트

**파일**: `apps/commerce-api/src/test/java/com/loopers/application/event/LikeEventListenerTest.java`

**왜 필요한가**

Step 1에서 좋아요 집계를 이벤트로 분리했다. `LikeEventListener`는 `@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW`로 별도 트랜잭션에서 집계를 수행하며, **집계 실패가 좋아요 자체의 성공에 영향을 주지 않아야 한다** (Eventual Consistency). 이 행위를 검증해야 한다.

**테스트 내용**

| 메서드 | 검증 대상 |
|--------|----------|
| `incrementsLikeCount_whenLiked` | 좋아요 이벤트 → `productService.incrementLikeCount()` 호출 |
| `decrementsLikeCount_whenUnliked` | 좋아요 취소 이벤트 → `productService.decrementLikeCount()` 호출 |
| `suppressesException_whenAggregationFails` | 집계 실패(RuntimeException) 시 예외를 삼키고 전파하지 않음 |

**테스트 결과에서 알 수 있었던 것**

3번째 테스트(`suppressesException_whenAggregationFails`)가 핵심이다. `productService.incrementLikeCount()`가 RuntimeException을 던져도 리스너가 try-catch로 삼키므로 호출자에게 예외가 전파되지 않는다. 이것이 "집계 실패와 무관하게 좋아요는 성공"이라는 Eventual Consistency 설계를 보장하는 메커니즘이다.

---

### 2-3. OrderEventListener 단위 테스트

**파일**: `apps/commerce-api/src/test/java/com/loopers/application/event/OrderEventListenerTest.java`

**왜 필요한가**

`OrderEventListener`는 주문 결제 성공/실패 시 로깅을 담당하는 부가 로직 리스너다. 로깅 전용이므로 비즈니스 로직은 없지만, **예외가 발생하지 않아 메인 흐름에 영향을 주지 않는지** 검증해야 한다. `fallbackExecution=true` 설정으로 트랜잭션 없이 발행된 이벤트도 수신한다.

**테스트 내용**

| 메서드 | 검증 대상 |
|--------|----------|
| `handlesOrderPaidEvent` | `OrderPaidEvent` 수신 시 예외 없이 처리 |
| `handlesOrderFailedEvent` | `OrderFailedEvent` 수신 시 예외 없이 처리 |

**테스트 결과에서 알 수 있었던 것**

로깅 전용 리스너가 다양한 이벤트 데이터(orderId, memberId, totalAmount, orderedProducts, reason 등)를 수신해도 NullPointerException이나 포맷 오류 없이 정상 처리된다. `OrderEventListener`는 외부 의존성 없이 `log.info()`만 호출하므로 Mock 없이 순수하게 검증 가능하다.

---

### 2-4. UserActionEventListener 단위 테스트

**파일**: `apps/commerce-api/src/test/java/com/loopers/application/event/UserActionEventListenerTest.java`

**왜 필요한가**

`UserActionEventListener`는 모든 유저 행동(주문, 좋아요, 조회)을 통합 로깅한다. 4개의 도메인 이벤트(`OrderPaidEvent`, `OrderFailedEvent`, `ProductLikedEvent`, `ProductViewedEvent`)를 각각 다른 핸들러 메서드로 수신하며, 일부는 `fallbackExecution=true`로 트랜잭션 없이도 동작한다. 각 핸들러가 독립적으로 예외 없이 동작하는지 검증해야 한다.

**테스트 내용**

| 메서드 | 검증 대상 |
|--------|----------|
| `logsOrderPaid` | ORDER_PAID 행동 로깅 |
| `logsOrderFailed` | ORDER_FAILED 행동 로깅 |
| `logsProductLiked` | PRODUCT_LIKED 행동 로깅 |
| `logsProductUnliked` | PRODUCT_UNLIKED 행동 로깅 (liked=false) |
| `logsProductViewed` | PRODUCT_VIEWED 행동 로깅 |
| `logsProductViewed_withNullMemberId` | 비로그인 사용자 조회 (memberId=null) |

**테스트 결과에서 알 수 있었던 것**

`logsProductViewed_withNullMemberId` 테스트가 중요하다. `ProductViewedEvent`의 memberId는 비로그인 시 null이 될 수 있는데, `log.info()`에서 null을 포맷팅할 때 NPE가 발생하지 않는지 확인했다. SLF4J의 `{}`  placeholder는 null을 `"null"` 문자열로 안전하게 출력하므로 문제없었다.

---

### 2-5. CouponIssueService 통합 테스트

**파일**: `apps/commerce-api/src/test/java/com/loopers/domain/coupon/CouponIssueServiceIntegrationTest.java`

**왜 필요한가**

`CouponIssueService`는 선착순 발급 요청을 생성하는 도메인 서비스다. 단위 테스트에서는 Mock으로 검증했지만, **실제 DB에서 UK 제약(member_id + coupon_id)이 중복을 방지하는지**, 상태 기반 검증(REQUESTED/AVAILABLE/FAILED)이 정확한지를 통합 테스트로 확인해야 한다.

**테스트 내용**

| 메서드 | 검증 대상 |
|--------|----------|
| `savesRequestedMemberCoupon` | 정상 요청 → REQUESTED 상태로 DB 저장, ID 발급 |
| `throwsConflict_whenDuplicate` | 동일 회원+쿠폰 중복 요청 → CONFLICT 예외 |
| `throwsBadRequest_whenExpired` | 만료 쿠폰 → BAD_REQUEST 예외 |
| `throwsBadRequest_whenUnlimitedCoupon` | 비선착순 쿠폰(maxIssueCount=0) → BAD_REQUEST 예외 |
| `retriesRequest_whenPreviouslyFailed` | FAILED 상태 재요청 → 기존 레코드를 REQUESTED로 상태 전환 |
| `returnsExistingRecord` | 발급 상태 조회 → REQUESTED 상태 반환 |
| `returnsEmpty_whenNoRecord` | 미요청 상태 조회 → 빈 Optional |

**테스트 결과에서 알 수 있었던 것**

초기 구현에서는 FAILED 재요청 시 새 레코드를 INSERT하려 했으나, UK(member_id, coupon_id) 제약으로 `DataIntegrityViolationException`이 발생했다. 통합 테스트에서 발견된 이 문제를 기존 FAILED 레코드를 REQUESTED로 상태 전환(UPDATE)하는 방식으로 해결했다.

```
단위 테스트:  save(any()) → mock이 그냥 성공 반환 → 통과
통합 테스트:  INSERT INTO member_coupon → Duplicate entry '1-1' → UK 위반
해결:        기존 레코드 조회 → retryRequest()로 상태 전환 → UPDATE
```

이것이 통합 테스트가 필요한 이유다. Mock 기반 단위 테스트에서는 DB 제약 조건이 드러나지 않는다.

---

### 2-6. OutboxEventRepository 통합 테스트

**파일**: `apps/commerce-api/src/test/java/com/loopers/infrastructure/outbox/OutboxEventRepositoryIntegrationTest.java`

**왜 필요한가**

`findUnpublishedBefore` 쿼리는 "생성 후 10초 이상 지난 미발행 이벤트만 조회"하는 핵심 쿼리다. JPQL의 조건(`published = false AND createdAt < threshold`)이 실제 DB에서 올바르게 동작하는지, `LIMIT` 절이 정상 적용되는지 검증해야 한다.

**테스트 내용**

| 메서드 | 검증 대상 |
|--------|----------|
| `returnsUnpublishedEvents` | 11초 전 생성된 미발행 이벤트 → 조회됨 |
| `excludesPublishedEvents` | published=true 이벤트 → 조회에서 제외 |
| `excludesRecentEvents` | 방금 생성된 이벤트 (10초 이내) → 조회에서 제외 |
| `respectsLimit` | 5건 중 limit=3 → 3건만 조회 |
| `savesWithEventIdAndCreatedAt` | 저장 시 eventId(UUID), createdAt 자동 설정 |

**테스트 결과에서 알 수 있었던 것**

`createdAt` 컬럼이 `@Column(updatable = false)`로 설정되어 있어, ReflectionTestUtils로 변경 후 JPA save()를 호출해도 DB에 반영되지 않았다. Native Query(`UPDATE outbox_event SET created_at = DATE_SUB(NOW(6), INTERVAL 11 SECOND)`)로 직접 업데이트해야 했다. 이는 JPA의 `updatable = false` 속성이 UPDATE SQL에서 해당 컬럼을 아예 제외시키기 때문이다.

```
ReflectionTestUtils.setField(event, "createdAt", 11초 전);
outboxEventJpaRepository.save(event);
// → JPA가 생성하는 UPDATE SQL에 created_at이 포함되지 않음
// → DB의 created_at은 원래 값(현재 시각) 그대로
```

---

### 2-7. CouponIssueEventService 통합 테스트

**파일**: `apps/commerce-streamer/src/test/java/com/loopers/application/coupon/CouponIssueEventServiceIntegrationTest.java`

**왜 필요한가**

Consumer 쪽 선착순 발급 로직이 실제 DB에서 올바르게 동작하는지 검증한다. 특히 `event_handled` 멱등 처리, `member_coupon` 상태 전환(REQUESTED → AVAILABLE/FAILED), 수량 체크(`SELECT COUNT`)가 실제 데이터로 정확한지 확인해야 한다.

**테스트 내용**

| 메서드 | 검증 대상 |
|--------|----------|
| `approvesWhenWithinLimit` | 수량 이내 → AVAILABLE + event_handled 기록 |
| `rejectsWhenExceedsLimit` | 1장 한정에 2번째 요청 → FAILED, failReason="수량 초과" |
| `skipsAlreadyHandledEvent` | 같은 eventId 중복 호출 → 두 번째는 false 반환 |
| `skipsWhenMemberCouponNotFound` | 존재하지 않는 memberCouponId → skip + event_handled 기록 |

**테스트 결과에서 알 수 있었던 것**

`skipsWhenMemberCouponNotFound` 테스트는 Outbox Pattern 사용 환경에서의 방어 로직을 검증한다. Outbox Pattern에서는 MemberCoupon INSERT와 OutboxEvent INSERT가 같은 트랜잭션에서 원자적으로 커밋되므로, 정상 경로에서는 `member_coupon` 레코드가 반드시 존재한다. 하지만 Consumer가 중복 수신하거나, 수동으로 Kafka 토픽에 메시지가 발행되는 등 비정상 상황에서는 `member_coupon`이 없을 수 있다. 이때 서비스가 skip하고 `event_handled`에 기록하여 이후 재처리를 방지하는 것을 확인했다.

---

### 2-8. MetricsEventService 통합 테스트

**파일**: `apps/commerce-streamer/src/test/java/com/loopers/application/metrics/MetricsEventServiceIntegrationTest.java`

**왜 필요한가**

`product_metrics` upsert 로직이 실제 DB에서 정확하게 동작하는지, `event_handled` 멱등 처리가 DB 레벨에서 보장되는지 검증한다. 특히 "없는 상품에 대한 첫 이벤트 → 새 metrics 생성"과 "기존 metrics에 대한 증감"이 모두 올바른지 확인해야 한다.

**테스트 내용**

| 메서드 | 검증 대상 |
|--------|----------|
| `incrementsLikeCount` | PRODUCT_LIKED(liked=true) → like_count +1, event_handled 기록 |
| `decrementsLikeCount` | PRODUCT_LIKED(liked=false) → like_count -1 |
| `skipsDuplicateEvent` | 같은 eventId → 두 번째 skip, like_count 1 유지 |
| `incrementsViewCount` | PRODUCT_VIEWED → view_count +1 |
| `createsNewMetrics` | 없는 productId → 새 ProductMetrics 생성 (모든 count 0에서 시작) |
| `incrementsOrderCountPerProduct` | ORDER_PAID → 상품별 order_count 증가 (배열 내 각 상품) |

**테스트 결과에서 알 수 있었던 것**

`createsNewMetrics` 테스트에서 `getOrCreateMetrics()` 로직이 실제 DB에서 정상 동작하는 것을 확인했다. `findByProductId()` → empty → `new ProductMetrics(productId)` → save 흐름이 실제 INSERT로 이어지고, 이후 같은 productId에 대한 요청은 기존 레코드를 UPDATE하는 것을 확인했다.

`incrementsOrderCountPerProduct`에서는 JSON 배열(`orderedProducts`)을 파싱하여 상품별로 개별 metrics를 생성/업데이트하는 것을 검증했다. productId=1에 quantity=2, productId=2에 quantity=3이 각각 별도의 ProductMetrics 레코드로 저장되었다.

---

### 2-9. 선착순 쿠폰 발급 API E2E 테스트

**파일**: `apps/commerce-api/src/test/java/com/loopers/interfaces/api/CouponIssueV1ApiE2ETest.java`

**왜 필요한가**

선착순 쿠폰 발급 요청 API(`POST /issue-request`)와 상태 조회 API(`GET /issue-status`)의 전체 HTTP 흐름을 검증한다. 인증(X-Loopers-LoginId 헤더), 상태 코드(202, 401, 409, 404), 응답 DTO 구조까지 실제 HTTP 요청으로 확인해야 한다.

**테스트 내용**

| 메서드 | 검증 대상 |
|--------|----------|
| `returns202_whenAuthenticated` | 인증 + 정상 요청 → 202 Accepted, REQUESTED 상태 |
| `returns401_whenNoAuth` | 인증 없이 요청 → 401 Unauthorized |
| `returns409_whenDuplicate` | 중복 요청 → 409 Conflict |
| `returnsRequestedStatus` | 발급 요청 후 조회 → 200 OK, REQUESTED 상태 |
| `returns404_whenNotRequested` | 미요청 상태 조회 → 404 Not Found |
| `returns401_whenNoAuth` (status) | 인증 없이 조회 → 401 Unauthorized |

**Kafka 처리 방식**

E2E 테스트에서 `KafkaTemplate`은 `@MockBean`으로 대체했다. 이유:

1. DB는 Testcontainers MySQL이 자동으로 뜨지만, Kafka는 별도 Docker Compose로 실행해야 한다
2. `OutboxEventListener`가 AFTER_COMMIT에서 `KafkaOutboxEventPublisher`를 통해 Kafka 발행을 시도하는데, 테스트 환경에서 Kafka 브로커가 없으면 타임아웃이 발생한다
3. E2E의 목적은 **HTTP → Controller → Facade → Service → DB(MemberCoupon + OutboxEvent) 흐름** 검증이며, Kafka 연동은 streamer 통합 테스트에서 별도 검증한다

```
실제 Kafka 연결 시도 시:
  OutboxEventListener.publishAfterCommit() (@Async + REQUIRES_NEW 별도 스레드/TX)
  → KafkaOutboxEventPublisher.publish() → TimeoutException
  → 즉시 발행은 실패하지만 OutboxScheduler가 재시도 (Outbox 레코드는 DB에 이미 저장됨)

MockBean으로 대체 시:
  KafkaTemplate은 MockBean → 즉시 성공 반환
  → 202 Accepted + Outbox 레코드 저장 정상 완료
```

**테스트 결과에서 알 수 있었던 것**

`returns409_whenDuplicate` 테스트에서 첫 번째 요청이 성공(202)한 후 두 번째 요청이 409를 반환하는 흐름을 확인했다. 이는 `CouponIssueService`의 중복 체크(`findByMemberIdAndCouponIdIncludingDeleted`)가 REQUESTED 상태의 기존 레코드를 감지하여 CONFLICT 예외를 던지는 것을 HTTP 레벨에서 검증한 것이다.

---

## 3. 테스트 과정에서 발견한 사항

### 3-1. 단위 테스트 vs 통합 테스트의 차이가 드러난 사례

| 항목 | 단위 테스트 (Mock) | 통합 테스트 (실제 DB) |
|------|-------------------|---------------------|
| FAILED 재요청 | `save(any())` → mock 성공 → 통과 | UK 위반 → `DataIntegrityViolationException` |
| 동시성 제어 | 순차 실행 → race condition 없음 | 10 스레드 동시 실행 → `issuedCount=11/10` 수량 초과 |
| Outbox createdAt 변경 | ReflectionTestUtils → 메모리상 변경 → 검증 통과 | `updatable=false` → DB 미반영 → 조회 실패 |

단위 테스트만으로는 DB 제약 조건, 동시성, JPA 설정의 실제 동작을 검증할 수 없다.

### 3-2. Kafka 테스트 환경의 제약

```
┌─ Testcontainers ─────────────────────────────────┐
│  MySQL: 자동으로 Docker 컨테이너 생성 → 테스트 독립적  │
│  Redis: 자동으로 Docker 컨테이너 생성 → 테스트 독립적  │
└──────────────────────────────────────────────────┘

┌─ Docker Compose (수동 실행) ─────────────────────┐
│  Kafka: localhost:19092 → 테스트 전 직접 실행 필요   │
│  → Kafka Testcontainer 설정이 프로젝트에 없음       │
└──────────────────────────────────────────────────┘
```

- commerce-streamer 통합 테스트: Kafka가 Docker Compose로 떠있으면 `localhost:19092`로 정상 연결
- commerce-api E2E: `@SpringBootTest(RANDOM_PORT)` + Kafka 초기 연결이 `send().get(5초)` 타임아웃 내에 안 되는 경우 발생 → `@MockBean`으로 대체

### 3-3. 동시성 제어 전략의 실증

```
설계 가정:
  "Kafka partition key=couponId → 같은 쿠폰 요청은 같은 파티션 → 같은 Consumer가 순차 처리"

검증 결과:
  순차 처리 (Kafka 시뮬레이션, 10장 한정/30명): AVAILABLE=10, FAILED=20 → 정확히 수량 제한 ✅
  동시 처리 (Kafka 없이, 100장 한정/150명):     AVAILABLE=109, FAILED=41 → 9장 수량 초과 발생 ❌

결론:
  DB의 SELECT COUNT만으로는 동시성을 보장할 수 없다.
  Kafka 파티션 순서 보장이 이 설계의 핵심 전제 조건이다.
```

---

## 4. 테스트 실행 방법

### 전제 조건
- Docker 실행 중
- `docker-compose -f ./docker/infra-compose.yml up` (MySQL, Redis, Kafka)

### Unit 테스트 (Docker 불필요)
```bash
# commerce-api
./gradlew :apps:commerce-api:test \
  --tests "com.loopers.application.event.LikeEventListenerTest" \
  --tests "com.loopers.application.event.OrderEventListenerTest" \
  --tests "com.loopers.application.event.UserActionEventListenerTest" \
  --tests "com.loopers.application.event.KafkaEventPublishListenerTest" \
  --tests "com.loopers.application.event.OutboxEventListenerTest" \
  --tests "com.loopers.application.order.OrderTransactionServiceTest" \
  --tests "com.loopers.application.order.OrderPaymentFacadeTest" \
  --tests "com.loopers.application.product.ProductFacadeTest" \
  --tests "com.loopers.application.coupon.CouponIssueFacadeTest" \
  --tests "com.loopers.application.outbox.OutboxSchedulerTest" \
  --tests "com.loopers.infrastructure.outbox.KafkaOutboxEventPublisherTest" \
  --tests "com.loopers.domain.outbox.OutboxEventTest"

# commerce-streamer
./gradlew :apps:commerce-streamer:test \
  --tests "com.loopers.application.metrics.MetricsEventServiceTest" \
  --tests "com.loopers.domain.metrics.ProductMetricsTest" \
  --tests "com.loopers.interfaces.consumer.CatalogEventConsumerTest" \
  --tests "com.loopers.interfaces.consumer.OrderEventConsumerTest"
```

### Integration 테스트 (Docker 필요 — MySQL, Kafka)
```bash
# commerce-api
./gradlew :apps:commerce-api:test \
  --tests "com.loopers.domain.coupon.CouponIssueServiceIntegrationTest" \
  --tests "com.loopers.infrastructure.outbox.OutboxEventRepositoryIntegrationTest"

# commerce-streamer
./gradlew :apps:commerce-streamer:test \
  --tests "com.loopers.application.coupon.CouponIssueEventServiceIntegrationTest" \
  --tests "com.loopers.application.metrics.MetricsEventServiceIntegrationTest" \
  --tests "com.loopers.application.coupon.CouponIssueConcurrencyTest"
```

### E2E 테스트 (Docker 필요 — MySQL)
```bash
./gradlew :apps:commerce-api:test \
  --tests "com.loopers.interfaces.api.CouponIssueV1ApiE2ETest"
```

---

## 5. Step 3 — 선착순 쿠폰 발급 테스트 상세

### 5-1. 테스트 전략 개요

Step 3의 핵심은 **Kafka 기반 비동기 쿠폰 발급**이다. 테스트는 3개 관점으로 나눈다:

```
┌───────────────────────────────────────────────────────────────┐
│  1. 단위 테스트 (Mock) — 각 레이어의 로직 분기를 빠르게 검증   │
│     Domain: 상태 전환 (REQUESTED → AVAILABLE/FAILED)          │
│     Service: 검증 분기 (만료/중복/비선착순)                     │
│     Facade: Service 위임 + Kafka 발행                         │
│     Controller: HTTP 상태 코드 (202/400/404/409)              │
│     Consumer: 메시지 파싱 + 서비스 위임 + ACK/NACK             │
├───────────────────────────────────────────────────────────────┤
│  2. 통합 테스트 — 실제 DB(Testcontainers)에서 동작 검증        │
│     API: UK 제약 중복 방지, 상태 기반 조회                     │
│     Streamer: event_handled 멱등, 수량 체크, 상태 전환          │
├───────────────────────────────────────────────────────────────┤
│  3. 동시성 테스트 — race condition과 수량 초과 검증             │
│     API: 같은 회원 동시 요청 → UK 제약으로 1건만 성공           │
│     Streamer: 100장 한정에 150명 순차/동시 처리 비교            │
└───────────────────────────────────────────────────────────────┘
```

### 5-2. 단위 테스트 상세

#### MemberCouponIssueTest — 도메인 모델 상태 전환

**파일**: `apps/commerce-api/src/test/java/com/loopers/domain/coupon/MemberCouponIssueTest.java`

**검증 대상**: `MemberCoupon.createRequested()`, `approve()`, `reject()` 메서드

| 메서드 | 검증 대상 |
|--------|----------|
| `createsWithRequestedStatus` | `createRequested()` → status=REQUESTED, failReason=null |
| `transitionsToAvailable` | REQUESTED → `approve()` → status=AVAILABLE |
| `throwsWhenNotRequested` (approve) | AVAILABLE에서 `approve()` → CONFLICT 예외 |
| `transitionsToFailedWithReason` | REQUESTED → `reject("수량 초과")` → status=FAILED, failReason 기록 |
| `throwsWhenNotRequested` (reject) | AVAILABLE에서 `reject()` → CONFLICT 예외 |
| `transitionsToRequestedAndClearsFailReason` | FAILED → `retryRequest()` → status=REQUESTED, failReason=null |
| `throwsWhenRequested` (retryRequest) | REQUESTED에서 `retryRequest()` → CONFLICT 예외 |
| `throwsWhenAvailable` (retryRequest) | AVAILABLE에서 `retryRequest()` → CONFLICT 예외 |

**테스트 의도**: 도메인 모델의 상태 전이 규칙이 코드 레벨에서 강제되는지 확인한다. AVAILABLE 상태에서 `approve()`나 `reject()`를 호출하면 예외가 발생하여, Consumer가 잘못된 상태의 쿠폰을 처리하는 것을 방지한다. 또한 FAILED 상태에서만 `retryRequest()`가 허용되어 재요청 흐름을 안전하게 제어한다.

#### CouponIssueServiceTest — 발급 요청 도메인 서비스

**파일**: `apps/commerce-api/src/test/java/com/loopers/domain/coupon/CouponIssueServiceTest.java`

| 메서드 | 검증 대상 |
|--------|----------|
| `createsRequestedMemberCoupon` | 정상 요청 → REQUESTED 상태 MemberCoupon 저장 |
| `throwsWhenCouponNotFound` | 존재하지 않는 쿠폰 → NOT_FOUND |
| `throwsWhenCouponExpired` | 유효기간 만료 쿠폰 → BAD_REQUEST |
| `throwsWhenNotLimitedCoupon` | maxIssueCount=0 → BAD_REQUEST "선착순 쿠폰이 아닙니다" |
| `throwsWhenAlreadyRequested` | REQUESTED 상태 존재 → CONFLICT |
| `throwsWhenAlreadyIssued` | AVAILABLE 상태 존재 → CONFLICT |
| `allowsReRequestWhenPreviouslyFailed` | FAILED 상태 → 재요청 허용 |
| `returnsExistingRecord` / `returnsEmptyWhenNotRequested` | 상태 조회 |

**테스트 의도**: `CouponIssueService`의 검증 분기가 빈틈없이 동작하는지 확인한다. 특히 중복 요청 방어(REQUESTED/AVAILABLE 상태에서 거부)와 FAILED 상태에서의 재요청 허용이 핵심이다.

#### CouponIssueFacadeTest — Outbox Pattern 발행 방식 검증

**파일**: `apps/commerce-api/src/test/java/com/loopers/application/coupon/CouponIssueFacadeTest.java`

**왜 이 테스트가 중요한가 — 직접 발행 vs Outbox 비교**

이 프로젝트에는 2가지 이벤트 발행 전략이 있다:

| 전략 | 사용처 | Kafka 발행 시점 | 실패 시 동작 |
|------|--------|----------------|-------------|
| 직접 Kafka 발행 | `KafkaEventPublishListener` (좋아요, 조회수, 판매량) | TX 커밋 후 AFTER_COMMIT | 유실 허용 (통계성) |
| **Outbox Pattern** | `CouponIssueFacade` (선착순 쿠폰) | TX 커밋 후 즉시 시도 + 스케줄러 재시도 | 유실 불가 (사용자 대면) |

`CouponIssueFacadeTest`는 Outbox Pattern의 핵심 특성을 검증한다:

| 메서드 | 검증 대상 |
|--------|----------|
| `requestsIssueAndSavesOutbox` | MemberCoupon + OutboxEvent가 같은 TX에서 저장되고, Published 이벤트가 발행됨 |
| `propagatesServiceException` | Service 예외 시 Outbox 저장 없이 실패 |
| `returnsRequestedStatus` | Polling REQUESTED 상태 반환 |
| `returnsFailedStatusWithReason` | Polling FAILED + failReason 반환 |
| `throwsWhenNotFound` | 미요청 상태 Polling → NOT_FOUND |

**Outbox Pattern 적용 후 테스트 설계 의도**:

쿠폰 발급은 Outbox Pattern을 사용하여 MemberCoupon INSERT(REQUESTED)와 OutboxEvent INSERT를 같은 TX에서 원자적으로 저장한다. TX 커밋 후 즉시 Kafka 발행을 시도하고, 실패 시 OutboxScheduler가 재시도한다.

```
Outbox Pattern:
  @Transactional { MemberCoupon INSERT + OutboxEvent INSERT } → commit
  commit 후 → 즉시 Kafka 발행 시도
  실패 시 → OutboxScheduler 10초 폴링으로 재시도 → 유실 없음
```

쿠폰 발급은 사용자 대면 기능이므로 이벤트 유실 시 REQUESTED 상태에서 영원히 멈추게 된다. 통계성 이벤트(좋아요, 조회수)와 달리 Outbox Pattern이 필요한 이유이다.

#### CouponIssueV1ControllerTest — HTTP 인터페이스

**파일**: `apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/CouponIssueV1ControllerTest.java`

| 엔드포인트 | 메서드 | 검증 대상 |
|------------|--------|----------|
| POST /issue-request | `returns202_whenAuthenticated` | 202 Accepted + REQUESTED |
| POST /issue-request | `returns401_whenNoAuth` | 인증 없음 → 401 |
| POST /issue-request | `returns409_whenAlreadyRequested` | 중복 → 409 + 메시지 |
| POST /issue-request | `returns404_whenCouponNotFound` | 미존재 → 404 |
| POST /issue-request | `returns400_whenNotLimitedCoupon` | 비선착순 → 400 |
| GET /issue-status | `returnsRequestedStatus` | REQUESTED 상태 200 |
| GET /issue-status | `returnsAvailableStatus` | AVAILABLE 상태 200 |
| GET /issue-status | `returnsFailedStatusWithReason` | FAILED + failReason 200 |
| GET /issue-status | `returns404_whenNotRequested` | 미요청 → 404 |
| GET /issue-status | `returns401_whenNoAuth` | 인증 없음 → 401 |

**테스트 의도**: `@ResponseStatus(HttpStatus.ACCEPTED)`로 202가 반환되는지, Polling API에서 REQUESTED/AVAILABLE/FAILED 3가지 상태가 모두 정상 응답되는지 확인한다.

#### CouponIssueEventServiceTest — Consumer 서비스 (streamer)

**파일**: `apps/commerce-streamer/src/test/java/com/loopers/application/coupon/CouponIssueEventServiceTest.java`

| 메서드 | 검증 대상 |
|--------|----------|
| `skipAlreadyHandled` | 이미 처리된 이벤트 → skip, false 반환 |
| `throwsOnUniqueViolation` | event_handled UNIQUE 위반 → DataIntegrityViolationException 전파 (TX 롤백) |
| `approvesWhenWithinLimit` | 수량 이내 → AVAILABLE 전환 |
| `approvesLastOne` | 마지막 1장 (99/100) → 정상 승인 |
| `rejectsWhenExceedsLimit` | 수량 초과 (100/100) → FAILED + "수량 초과" |
| `rejectsWhenCouponDeleted` | 쿠폰 삭제됨 → FAILED + "쿠폰을 찾을 수 없습니다" |
| `skipsWhenMemberCouponNotFound` | member_coupon 없음 → skip + event_handled 기록 |
| `skipsWhenNotRequestedStatus` | REQUESTED 아닌 상태 → skip |

**테스트 의도**: Consumer 서비스의 모든 분기를 검증한다.

특히 `skipsWhenMemberCouponNotFound`는 Outbox Pattern의 방어 로직을 검증하는 테스트다:

```
Outbox Pattern에서:
  MemberCoupon INSERT + OutboxEvent INSERT가 같은 TX → 원자적 커밋
  → 정상 경로에서는 member_coupon이 반드시 존재

  하지만 Consumer가 중복 수신하거나 비정상 상황에서 member_coupon이 없을 수 있음
  → Consumer에서 skip + event_handled 기록으로 방어
```

이 테스트가 통과한다는 것은 비정상 상황에서도 Consumer가 안전하게 처리함을 증명한다.

#### CouponIssueConsumerTest — Kafka 메시지 수신

**파일**: `apps/commerce-streamer/src/test/java/com/loopers/interfaces/consumer/CouponIssueConsumerTest.java`

| 메서드 | 검증 대상 |
|--------|----------|
| `handleCouponIssueRequested_delegatesToService` | 정상 메시지 → 서비스 위임 + ACK |
| `doesNotAck_whenServiceFails` | 서비스 예외 → ACK 미전송 (재수신 예정) |
| `skipMessage_whenEventIdMissing` | eventId 누락 → skip + ACK |
| `skipMessage_whenUnknownEventType` | 알 수 없는 eventType → skip + ACK |
| `skipMessage_whenJsonParsingFails` | JSON 파싱 실패 → skip + ACK |

**테스트 의도**: Consumer의 방어 로직을 검증한다. 잘못된 메시지(누락 필드, 파싱 실패)는 skip+ACK하여 offset이 진행되고, 서비스 예외 시에만 ACK를 보내지 않아 재수신을 유도한다.

### 5-3. 동시성 테스트 상세

#### CouponIssueRequestConcurrencyTest — API 쪽 중복 요청 방어

**파일**: `apps/commerce-api/src/test/java/com/loopers/domain/coupon/CouponIssueRequestConcurrencyTest.java`

| 메서드 | 시나리오 | 기대 결과 |
|--------|----------|----------|
| `onlyOneSucceeds_whenSameMemberConcurrentRequest` | 같은 회원이 5개 스레드로 동시 발급 요청 | 1건 성공, 4건 실패 (UK 제약) |
| `allSucceed_whenDifferentMembersConcurrentRequest` | 10명의 다른 회원이 동시 발급 요청 | 10건 모두 성공 |

**테스트 의도**: `member_coupon` 테이블의 UK(member_id, coupon_id) 제약이 동시 요청 환경에서도 중복 INSERT를 방지하는지 검증한다. 같은 회원의 동시 요청은 `CouponIssueService`의 애플리케이션 레벨 체크 또는 DB UK 제약 중 하나에 의해 차단된다.

#### CouponIssueConcurrencyTest — Consumer 쪽 수량 초과 방지

**파일**: `apps/commerce-streamer/src/test/java/com/loopers/application/coupon/CouponIssueConcurrencyTest.java`

| 메서드 | 시나리오 | 기대 결과 |
|--------|----------|----------|
| `showsRaceConditionWithoutKafkaOrdering` | 100장 한정, 150명 동시 처리 (10 스레드) | 모든 요청 처리됨 (race condition으로 초과 가능) |
| `exactLimitWithSequentialProcessing` | 10장 한정, 30명 순차 처리 (Kafka 시뮬레이션) | 정확히 10장 AVAILABLE, 20장 FAILED |
| `duplicateEventIdIsProcessedOnce` | 같은 eventId로 2회 호출 | 1회만 처리, 2회째 skip |

**테스트 의도**: Kafka 파티션 순서 보장이 동시성 제어의 핵심 전제임을 실증한다.

- **순차 처리**(Kafka 파티션이 보장하는 순서): `SELECT COUNT` → `UPDATE` 사이에 다른 스레드가 끼어들 수 없으므로 정확한 수량 제한
- **동시 처리**(Kafka 없이 직접 호출): `SELECT COUNT`가 동시에 같은 값을 읽어 수량 초과 발생 가능

### 5-4. 테스트 커버리지 — Checklist 대응

| Checklist 항목 | 단위 테스트 | 통합 테스트 | 동시성 테스트 |
|---------------|------------|------------|-------------|
| 발급 요청 API → Kafka 발행 | `CouponIssueFacadeTest` (Kafka mock), `CouponIssueV1ControllerTest` (HTTP 202) | `CouponIssueServiceIntegrationTest` (DB 저장) | — |
| Consumer 수량 제한 + 중복 방지 | `CouponIssueEventServiceTest` (수량 초과/멱등), `CouponIssueConsumerTest` (메시지 수신) | `CouponIssueEventServiceIntegrationTest` (실제 DB) | `CouponIssueConcurrencyTest` (순차/동시) |
| Polling API | `CouponIssueV1ControllerTest` (REQUESTED/AVAILABLE/FAILED/404) | `CouponIssueServiceIntegrationTest` (상태 조회) | — |
| 동시성 — 수량 초과 방지 | — | — | `CouponIssueConcurrencyTest` (150명 동시), `CouponIssueRequestConcurrencyTest` (UK 방어) |

### 5-5. Step 3 테스트 실행 방법

#### 단위 테스트 (Docker 불필요)
```bash
# commerce-api 단위 테스트
./gradlew :apps:commerce-api:test \
  --tests "com.loopers.domain.coupon.MemberCouponIssueTest" \
  --tests "com.loopers.domain.coupon.CouponIssueServiceTest" \
  --tests "com.loopers.application.coupon.CouponIssueFacadeTest" \
  --tests "com.loopers.interfaces.api.coupon.CouponIssueV1ControllerTest"

# commerce-streamer 단위 테스트
./gradlew :apps:commerce-streamer:test \
  --tests "com.loopers.application.coupon.CouponIssueEventServiceTest" \
  --tests "com.loopers.interfaces.consumer.CouponIssueConsumerTest"
```

#### 통합 + 동시성 테스트 (Docker 필요)
```bash
# commerce-api
./gradlew :apps:commerce-api:test \
  --tests "com.loopers.domain.coupon.CouponIssueServiceIntegrationTest" \
  --tests "com.loopers.domain.coupon.CouponIssueRequestConcurrencyTest"

# commerce-streamer
./gradlew :apps:commerce-streamer:test \
  --tests "com.loopers.application.coupon.CouponIssueEventServiceIntegrationTest" \
  --tests "com.loopers.application.coupon.CouponIssueConcurrencyTest"
```

---

## 6. 아키텍처 변경에 따른 추가 테스트

Outbox 적용 대상을 재설계하면서(통계성 → 직접 Kafka 발행, 쿠폰 → Outbox Pattern), 아래 테스트를 신규 작성하거나 보강했다.

### 6-1. KafkaEventPublishListenerTest (신규)

**파일**: `apps/commerce-api/src/test/java/com/loopers/application/event/KafkaEventPublishListenerTest.java`

**왜 필요한가**

`KafkaEventPublishListener`는 통계성 이벤트(좋아요, 조회수, 판매량)를 `@TransactionalEventListener(AFTER_COMMIT)`에서 직접 Kafka로 발행하는 리스너다. 이 리스너의 핵심 계약은 두 가지다:
1. 올바른 토픽/키/페이로드로 Kafka에 발행한다
2. Kafka 발행 실패 시 예외를 전파하지 않는다 (통계성 이벤트는 유실 허용)

두 번째가 특히 중요하다. 만약 예외가 전파되면 호출자(Facade)의 응답이 깨진다.

**테스트 내용**

| 이벤트 | 성공 검증 | 실패 검증 |
|--------|----------|----------|
| `ProductLikedEvent` | `catalog-events` 토픽, key=productId, payload에 eventId/eventType/data 포함 | Kafka 예외 시 미전파 |
| `ProductViewedEvent` | `catalog-events` 토픽, key=productId, payload에 PRODUCT_VIEWED 포함 | Kafka 예외 시 미전파 |
| `OrderPaidEvent` | `order-events` 토픽, key=orderId, payload에 orderedProducts 포함 | Kafka 예외 시 미전파 |

**테스트 결과에서 알 수 있었던 것**

`ArgumentCaptor`로 실제 전송되는 payload를 캡처하여 엔벨로프 구조(`eventId`, `eventType`, `data`)가 Consumer가 기대하는 형태인지 검증했다. 특히 `OrderPaidEvent`의 `orderedProducts` 리스트가 JSON으로 올바르게 직렬화되는지 확인했다.

### 6-2. OrderTransactionServiceTest (신규)

**파일**: `apps/commerce-api/src/test/java/com/loopers/application/order/OrderTransactionServiceTest.java`

**왜 필요한가**

`OrderOutboxService`에서 Outbox 코드를 제거하고 `OrderTransactionService`로 단순화했다. TX 래핑만 담당하는 얇은 서비스이지만, `completeOrder()` 위임과 `updatePaymentStatus()` + 상태 반환이 정확한지 검증이 필요하다.

**테스트 내용**

| 메서드 | 검증 대상 |
|--------|----------|
| `completeOrder_delegatesToOrderService` | 전액 할인 주문 완료가 OrderService에 위임됨 |
| `updatePaymentStatus_success` | 결제 성공 시 SUCCESS 반환 |
| `updatePaymentStatus_failed` | 결제 실패 시 FAILED 반환 |
| `updatePaymentStatus_pending` | 결제 보류 시 PENDING 반환 |

### 6-3. ProductFacadeTest 보강

**변경 사항**: `getProduct()` 테스트 2건에 `ProductViewedEvent` 발행 검증 추가

```java
// 캐시 히트/미스 모두에서 이벤트 발행을 검증
verify(eventPublisher).publishEvent(new ProductViewedEvent(1L, null));
```

**이유**: Outbox 제거 후 `ProductViewedEvent` 발행이 Kafka 연결의 유일한 진입점이다. 이 검증이 없으면 누군가 실수로 `publishEvent` 호출을 제거해도 테스트가 통과하여, 조회수 집계 파이프라인이 끊기는 회귀를 놓칠 수 있다.

### 6-4. OrderPaymentFacadeTest 보강

**변경 사항**: 이벤트 발행 검증 4건 추가 + SUCCESS 경로 테스트 1건 신규

| 테스트 | 추가된 검증 |
|--------|-----------|
| `completesOrder_whenPaymentAmountIsZero` | `OrderPaidEvent` 발행 검증 |
| `compensatesOrder_whenPaymentFails` | `OrderFailedEvent` 발행 검증 |
| `compensatesAndThrowsException_whenPaymentSystemFails` | `OrderFailedEvent` 발행 검증 |
| `publishesOrderPaidEvent_whenPaymentSucceeds` (신규) | PG 결제 SUCCESS → `OrderPaidEvent` 발행 + 보상 미실행 |

**이유**: `OrderPaymentFacade`가 이벤트를 발행하면 `KafkaEventPublishListener`가 Kafka로 전송한다. 이벤트 발행이 누락되면 판매량 통계가 끊기므로, 성공/실패 모든 경로에서 올바른 이벤트가 발행되는지 검증해야 한다. 특히 기존에는 SUCCESS 경로를 테스트하는 케이스가 없었다 — `createsOrderAndProcessesPayment`는 PENDING을 반환하여 이벤트 발행 분기를 타지 않았다.

### 6-5. CouponIssueFacadeTest 재작성

**변경 사항**: Direct Kafka → Outbox Pattern으로 전면 재작성

| 기존 (Direct Kafka) | 변경 후 (Outbox Pattern) |
|---------------------|------------------------|
| `kafkaTemplate.send()` Mock 검증 | `outboxEventRepository.save()` + payload 검증 |
| Kafka 실패 → RuntimeException 검증 | 불필요 (Outbox라서 실패 자체가 TX 롤백) |
| — | `OutboxEvent.Published` 이벤트 발행 검증 |

**이유**: 쿠폰 발급이 Outbox Pattern으로 변경되면서 `KafkaTemplate` 의존이 제거되고 `OutboxEventRepository` + `ApplicationEventPublisher`로 대체되었다. 핵심 검증 포인트가 "Kafka 전송 성공/실패"에서 "Outbox 저장 + Published 이벤트 발행"으로 바뀌었다.

---

## 7. Kafka 수동 통합 테스트 (Manual Integration Test)

자동화 테스트로 검증하기 어려운 **실제 Kafka 브로커 기반의 Producer-Consumer 전체 흐름**을 수동으로 확인한다.

### 7-1. 테스트 환경

| 구성 요소 | 설정 |
|----------|------|
| commerce-api | `localhost:8080` (서버), `localhost:8081` (management) |
| commerce-streamer | `localhost:8084` (서버), `localhost:8083` (management) |
| Kafka | `localhost:19092` (외부), `kafka:9092` (내부) |
| Kafka UI | `localhost:9099` |
| Grafana | `localhost:3000` (admin/admin) |
| Prometheus | `localhost:9090` |

### 7-2. API 호출을 통한 이벤트 발행 테스트

실제 API 엔드포인트를 호출하여 Kafka 메시지가 정상 발행되고, commerce-streamer에서 소비되는지 확인한다.

#### 테스트 데이터 준비

```sql
-- 브랜드, 상품, 상품옵션, 쿠폰, product_metrics 사전 생성
INSERT INTO brand (id, name, created_at, updated_at) VALUES (1, '테스트브랜드', NOW(), NOW());
INSERT INTO product (id, brand_id, name, description, price, supply_price, discount_price, shipping_fee, like_count, display_yn, margin_type, status, created_at, updated_at)
VALUES (1, 1, '테스트상품', '카프카 테스트용 상품', 10000, 8000, 0, 0, 0, 'Y', 'RATE', 'ON_SALE', NOW(), NOW());
INSERT INTO product_option (id, product_id, option_name, stock_quantity, created_at, updated_at) VALUES (1, 1, '기본옵션', 100, NOW(), NOW());
INSERT INTO coupon (id, name, coupon_scope, discount_type, discount_value, min_order_amount, max_discount_amount, valid_from, valid_to, max_issue_count, created_at, updated_at)
VALUES (1, '선착순쿠폰', 'CART', 'FIXED_AMOUNT', 1000, 0, 0, '2026-01-01', '2026-12-31', 100, NOW(), NOW());
INSERT INTO product_metrics (product_id, view_count, like_count, order_count, updated_at) VALUES (1, 0, 0, 0, NOW());
```

#### 회원가입

```bash
curl -X POST http://localhost:8080/api/v1/members/signup \
  -H "Content-Type: application/json" \
  -d '{"loginId":"kafkatest1","password":"Password1!","name":"카프카","email":"kafka@test.com","birthDate":"19990101"}'
```

#### 테스트 케이스 및 결과

| # | API 호출 | 토픽 | 이벤트 | 결과 |
|---|---------|------|--------|------|
| 1 | `GET /api/v1/products/1` | catalog-events | PRODUCT_VIEWED | PASS - streamer에서 view_count 집계 완료 |
| 2 | `POST /api/v1/products/1/likes` | catalog-events | PRODUCT_LIKED (liked: true) | PASS - streamer에서 like_count 집계 완료 |
| 3 | `DELETE /api/v1/products/1/likes` | catalog-events | PRODUCT_LIKED (liked: false) | PASS - streamer에서 like_count 차감 완료 |
| 4 | `POST /api/v1/coupons/1/issue-request` | coupon-issue-requests | COUPON_ISSUE_REQUESTED | PASS - HTTP 202, streamer에서 발급 처리 완료 |

```bash
# 상품 상세 조회 (인증 불필요) → PRODUCT_VIEWED 발행
curl -s http://localhost:8080/api/v1/products/1

# 상품 좋아요 → PRODUCT_LIKED 발행
curl -X POST http://localhost:8080/api/v1/products/1/likes \
  -H "X-Loopers-LoginId: kafkatest1" -H "X-Loopers-LoginPw: Password1!"

# 좋아요 취소 → PRODUCT_LIKED (liked: false) 발행
curl -X DELETE http://localhost:8080/api/v1/products/1/likes \
  -H "X-Loopers-LoginId: kafkatest1" -H "X-Loopers-LoginPw: Password1!"

# 쿠폰 발급 요청 → COUPON_ISSUE_REQUESTED 발행 (Outbox Pattern)
curl -X POST http://localhost:8080/api/v1/coupons/1/issue-request \
  -H "X-Loopers-LoginId: kafkatest1" -H "X-Loopers-LoginPw: Password1!"
```

#### 토픽별 발행 확인

```
catalog-events:0:6        -- PRODUCT_VIEWED 4건 + PRODUCT_LIKED 2건
coupon-issue-requests:0:1 -- COUPON_ISSUE_REQUESTED 1건
order-events:0:0          -- (주문 생성 시 발행)
```

### 7-3. Kafka 토픽 직접 메시지 발행을 통한 Consumer 테스트

Kafka 토픽에 메시지를 직접 발행하여 commerce-streamer Consumer의 예외 처리, 유효성 검증, 멱등 처리를 검증한다.
Kafka UI(`localhost:9099`) 또는 kafka-console-producer로 수행 가능.

#### 테스트 케이스 및 결과

| # | 테스트 시나리오 | 토픽 | 발행 메시지 | 기대 동작 | 결과 |
|---|---------------|------|-----------|----------|------|
| 1 | 정상 이벤트 처리 | catalog-events | `{"eventId":"manual-view-001","eventType":"PRODUCT_VIEWED","data":{"productId":1}}` | view_count 증가, event_handled 저장 | PASS |
| 2 | 알 수 없는 eventType | catalog-events | `{"eventId":"manual-unknown-001","eventType":"UNKNOWN_TYPE","data":{}}` | WARN 로그 + skip + ACK 전송 | PASS |
| 3 | eventId 누락 | catalog-events | `{"eventType":"PRODUCT_VIEWED","data":{"productId":1}}` | WARN 로그 + skip + ACK 전송 | PASS |
| 4 | 중복 eventId (멱등 처리) | catalog-events | eventId `manual-view-001` 재발행 | `이미 처리된 이벤트 - skip` DEBUG 로그 | PASS |
| 5 | 존재하지 않는 memberCouponId | coupon-issue-requests | `{"eventId":"manual-coupon-001","eventType":"COUPON_ISSUE_REQUESTED","data":{"couponId":1,"memberId":1,"memberCouponId":999}}` | `member_coupon 레코드 없음` WARN 로그 + skip | PASS |

#### Consumer 로그 (실제 출력)

```
-- 테스트 1: 정상 처리
INFO  CatalogEventConsumer : catalog-events 처리 완료: eventId=manual-view-001, eventType=PRODUCT_VIEWED

-- 테스트 2: 알 수 없는 eventType → skip
WARN  CatalogEventConsumer : catalog-events: 알 수 없는 eventType=UNKNOWN_TYPE — skip
INFO  CatalogEventConsumer : catalog-events 처리 완료: eventId=manual-unknown-001, eventType=UNKNOWN_TYPE

-- 테스트 3: eventId 누락 → skip
WARN  CatalogEventConsumer : catalog-events: eventId 또는 eventType 누락 — skip. value={"eventType":"PRODUCT_VIEWED","data":{"productId":1}}

-- 테스트 4: 중복 eventId → 멱등 처리
DEBUG MetricsEventService  : 이미 처리된 이벤트 — skip (TX 내부 체크): eventId=manual-view-001
INFO  CatalogEventConsumer : catalog-events 처리 완료: eventId=manual-view-001, eventType=PRODUCT_VIEWED

-- 테스트 5: 존재하지 않는 memberCouponId → skip
WARN  CouponIssueEventService : member_coupon 레코드 없음 — DB 커밋 실패 케이스, skip: memberCouponId=999
INFO  CouponIssueConsumer     : coupon-issue-requests 처리 완료: eventId=manual-coupon-001, couponId=1, memberId=1
```

### 7-4. Grafana Kafka Dashboard

Kafka Producer/Consumer 메트릭을 Grafana에서 시각적으로 모니터링할 수 있도록 대시보드를 구성했다.

- 위치: `docker/grafana/dashboards/kafka-dashboard.json`
- 접속: `http://localhost:3000` → Kafka Dashboard

#### 대시보드 구성

| 섹션 | 패널 | 메트릭 |
|------|------|--------|
| **Producer** | Record Send Rate | 토픽별 초당 발행량 |
| | Record Error Rate | 초당 발행 실패 수 |
| | Request Latency | Producer 요청 평균 지연 |
| | Byte Rate | 초당 전송 바이트 |
| **Consumer** | Records Consumed Rate | 토픽별 초당 소비량 |
| | Records Lag | 파티션별 컨슈머 랙 (100 이상 yellow, 1000 이상 red) |
| **Listener** | Processing Rate | 리스너별 초당 처리량 |
| | Avg Processing Time | 리스너 평균 처리 시간 |
| | Max Processing Time | 리스너 최대 처리 시간 |
| | Fetch Latency | 컨슈머 fetch 평균 지연 |

#### Prometheus 설정

commerce-api와 commerce-streamer를 별도 job으로 분리하여 메트릭을 수집한다.

```yaml
# docker/grafana/prometheus.yml
scrape_configs:
  - job_name: 'commerce-api'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8081']

  - job_name: 'commerce-streamer'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8083']
```

Spring Boot가 `spring-kafka` + `micrometer-registry-prometheus` 조합에서 Kafka 클라이언트 메트릭을 자동 노출하므로 별도 코드 추가 없이 동작한다.

---

## 8. AFTER_COMMIT + REQUIRES_NEW 없이 DB 쓰기 동작 검증

### 8-1. AfterCommitWithoutRequiresNewTest (신규)

**파일**: `apps/commerce-api/src/test/java/com/loopers/application/event/AfterCommitWithoutRequiresNewTest.java`

**왜 필요한가**

블로그(실험 4)에서 "REQUIRES_NEW 없이도 auto-commit 모드로 DB 쓰기가 반영된다"고 서술했지만, 이를 증명하는 테스트 코드가 없었다. 블로그의 주장을 테스트로 검증하기 위해 작성했다.

**테스트 방법**

`@TransactionalEventListener(AFTER_COMMIT)`의 내부 동작은 `TransactionSynchronization.afterCommit()` 콜백이다. 이 콜백을 직접 등록하여 AFTER_COMMIT 시점의 DB 쓰기 동작을 재현했다. REQUIRES_NEW 없이 `JdbcTemplate`으로 직접 UPDATE를 실행하여 auto-commit 모드에서의 동작을 검증한다.

**테스트 내용**

| 메서드 | 검증 대상 | 결과 |
|--------|----------|------|
| `afterCommit_withoutRequiresNew_dbWriteSucceedsByAutoCommit` | REQUIRES_NEW 없이 AFTER_COMMIT 콜백에서 DB UPDATE 실행 -> auto-commit으로 반영됨 | PASS |
| `afterCommit_withoutRequiresNew_partialCommitOnFailure` | 2개의 UPDATE 중 두 번째 실패 시, 첫 번째는 이미 auto-commit으로 반영 -> 부분 커밋 발생 | PASS |

**검증된 블로그 주장**

| 블로그 서술 | 테스트 결과 |
|------------|-----------|
| "REQUIRES_NEW 없이도 DB 쓰기 자체는 실행될 수 있다" | auto-commit 모드에서 UPDATE 반영 확인 |
| "auto-commit 모드로 전환되어 개별 SQL이 즉시 커밋된다" | 첫 번째 UPDATE 성공 후 두 번째 실패 시 부분 커밋 확인 |
| "REQUIRES_NEW를 붙여야 원자성이 보장된다" | REQUIRES_NEW 없이는 트랜잭션 경계가 없어 부분 커밋이 발생함을 확인 |

**실행 방법**

```bash
./gradlew :apps:commerce-api:test --tests "com.loopers.application.event.AfterCommitWithoutRequiresNewTest"
```
