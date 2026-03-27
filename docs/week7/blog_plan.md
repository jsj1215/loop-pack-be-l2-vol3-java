
# 블로그 작성 계획

## 제목

**이벤트로 분리하면 끝인 줄 알았다 — @TransactionalEventListener의 5가지 함정을 테스트로 증명하기**

---

## TL;DR

`@TransactionalEventListener(AFTER_COMMIT)`은 비동기가 아니다.
리스너가 죽으면 클라이언트는 500을 받고, 이벤트는 영원히 유실된다.
6개의 테스트로 Spring Event의 실제 동작을 증명하고, 각 함정에 대해 어떤 판단을 내렸는지 정리한 기록이다.

---

## 글의 흐름 한눈에 보기

```
들어가며 — 왜 이벤트로 분리하려 했는가
  │
  v
Part 1. @EventListener vs @TransactionalEventListener — 어떤 걸 써야 하는가?
  │
  v
Part 2. 테스트로 증명하는 5가지 함정
  ├─ 실험 1. AFTER_COMMIT은 비동기가 아니다
  ├─ 실험 2. 리스너가 죽으면 DB는 커밋, 클라이언트는 500
  ├─ 실험 3. 트랜잭션 없이 이벤트를 발행하면 — 조용히 사라진다
  ├─ 실험 4. AFTER_COMMIT 리스너에서 DB 쓰기 — REQUIRES_NEW가 필요한 이유
  └─ 실험 5. 이벤트는 유실된다 — 그리고 복구할 수 없다
  │
  v
Part 3. 왜 이렇게 동작하는가 — ThreadLocal + TransactionSynchronization
  │
  v
Part 4. 실험 6 — Outbox Pattern으로 유실을 막을 수 있는가?
  ├─ 실험 6-D. At Least Once의 이면 — Consumer 멱등성
  └─ 쿠폰 발급에 Outbox를 적용한 이유
  │
  v
Part 5. 유실 영향도에 따른 발행 전략 구분
  ├─ 직접 Kafka 발행 — 통계성 이벤트 (좋아요, 조회수, 판매량)
  └─ Outbox Pattern — 사용자 대면 기능 (선착순 쿠폰)
  │
  v
마치며
```

---

## 들어가며

Spring Boot + Java 기반의 커머스 API 프로젝트를 진행하고 있다.
주문-결제 플로우를 구현하고 나니 Facade 하나에 부가 로직이 잔뜩 박혀 있는 상태가 됐다.

```java
// OrderPaymentFacade — 주문, 결제, 로깅, 알림을 전부 직접 호출
public void createOrder(...) {
    orderService.createOrder(...);
    paymentService.pay(...);
    loggingService.log(...);        // ← 이거 실패하면 주문도 롤백?
    notificationService.send(...);  // ← 이것도?
}
```

로깅이 실패했다고 주문이 롤백되면 안 된다. 알림 서버가 죽었다고 결제가 취소되면 안 된다.
"주요 로직과 부가 로직을 분리해야겠다"는 결론이 나왔고, Spring의 `ApplicationEvent`를 도입하기로 했다.

그런데 이벤트로 분리하기만 하면 끝일까?
직접 테스트해보니까, `@TransactionalEventListener`에는 문서만 봐서는 알 수 없는 함정들이 숨어 있었다.

---

## Part 1. @EventListener vs @TransactionalEventListener — 어떤 걸 써야 하는가?

Spring에서 이벤트를 수신하는 방법은 두 가지다.

| 항목 | `@EventListener` | `@TransactionalEventListener` |
|------|-------------------|-------------------------------|
| 실행 시점 | `publishEvent()` 호출 즉시 | 트랜잭션 phase에 따라 (기본: 커밋 후) |
| 트랜잭션 없을 때 | 항상 실행 | 실행 안 됨 (`fallbackExecution=true`면 실행) |
| 같은 트랜잭션 참여 | O (호출자의 트랜잭션 안에서 실행) | X (커밋 후 실행이므로 트랜잭션 이미 종료) |
| 리스너 예외 시 | 호출자 트랜잭션 롤백 | 호출자 트랜잭션에 영향 없음 (이미 커밋됨) |
| 리스너에서 DB 쓰기 | 현재 트랜잭션에 포함됨 | 새 트랜잭션 필요 (`REQUIRES_NEW`) |

선택 기준은 하나다. **"리스너 실패가 호출자를 롤백시켜야 하는가?"**

```
리스너 실패 시 호출자도 롤백해야 하는가?
│
├─ YES → @EventListener
│         (같은 트랜잭션, 예외 전파)
│
└─ NO → @TransactionalEventListener(AFTER_COMMIT)
          (커밋 후 실행, 호출자에 영향 없음)
```

실제 프로젝트에서 내린 판단은 이렇다.

| 리스너 | 어노테이션 | 선택 이유 |
|--------|-----------|----------|
| `LikeEventListener` | `AFTER_COMMIT` | 집계 실패가 좋아요를 롤백시키면 안 됨 |
| `OrderEventListener` | `AFTER_COMMIT` + `fallbackExecution=true` | 결제 처리가 트랜잭션 밖에서 발생 |
| `UserActionEventListener` | `AFTER_COMMIT` + `fallbackExecution=true` | 로깅은 트랜잭션 유무와 무관하게 항상 실행 |

> 여기까지만 보면 `@TransactionalEventListener`가 완벽한 답처럼 보인다.
> 하지만 직접 테스트해보니 5가지 함정이 숨어 있었다.

---

## Part 2. 테스트로 증명하는 5가지 함정

### 실험 1. AFTER_COMMIT은 비동기가 아니다

"커밋 후에 실행된다"고 하니까 당연히 비동기일 거라고 생각했다. 커밋이 끝나고 별도 스레드에서 돌아가는 거 아닌가?

틀렸다. **같은 스레드에서 동기적으로 실행된다.**

먼저 스레드가 같은지부터 확인해봤다.

```java
@DisplayName("AFTER_COMMIT 리스너는 발행자와 같은 스레드에서 실행된다")
@Test
void afterCommitListenerRunsOnSameThread() {
    // given
    // 리스너 내부에서 Thread.currentThread().getName()을 캡처

    // when
    productFacade.like(memberId, productId);

    // then
    // 발행 스레드 이름 == 리스너 스레드 이름
    assertThat(publisherThreadName).isEqualTo(listenerThreadName);
}
```

[스크린샷: 테스트 초록불 + 로그에서 같은 스레드 이름 확인]

스레드가 같다는 건 확인했는데, "그래서 실제로 응답이 느려지는가?"를 숫자로 확인하고 싶었다.
리스너에 `Thread.sleep(2000)`을 넣고 API 응답 시간을 측정해봤다.

```java
@DisplayName("AFTER_COMMIT 리스너의 지연이 API 응답 시간에 직접 영향을 준다")
@Test
void afterCommitListenerDelay_affectsApiResponseTime() {
    // given - 리스너에 2초 sleep 설정

    // when
    long startTime = System.currentTimeMillis();
    productFacade.like(memberId, productId);
    long elapsed = System.currentTimeMillis() - startTime;

    // then - 리스너의 2초가 응답 시간에 그대로 반영됨
    assertThat(elapsed).isGreaterThan(2000);
}
```

| 조건 | API 응답 시간 | 비고 |
|------|-------------|------|
| 리스너 없음 | ~50ms | 기준 |
| AFTER_COMMIT 리스너 (2초 sleep) | ~2,050ms | **약 40배 느려짐** |
| AFTER_COMMIT + `@Async` 리스너 (2초 sleep) | ~50ms | 기준과 동일 |

"커밋 후"라는 말에 속아서 무거운 로직을 넣으면, 사용자는 의미 없이 2초를 더 기다려야 한다. 이건 개발 환경에서는 몰라도 운영에서는 바로 문제가 된다.

그래서 프로젝트에서는 Kafka 발행 리스너에 `@Async`를 붙여서 별도 스레드로 분리했다.

```java
// OutboxEventListener — @Async로 별도 스레드에서 실행, REQUIRES_NEW로 markPublished() 반영
@Async
@Transactional(propagation = Propagation.REQUIRES_NEW)
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void publishAfterCommit(OutboxEvent.Published event) { ... }
```

> **포기한 것**: `@Async`를 붙이면 리스너에서 예외가 터져도 호출자에게 전파되지 않는다. 실패를 즉시 감지할 수 없으니, 별도 모니터링(로그, 메트릭)이 필요해진다. 하지만 2,050ms → 50ms의 차이를 보고 나니, 응답 시간을 희생하는 것보다는 모니터링 비용을 감수하는 게 나은 선택이라고 판단했다.

---

### 실험 2. 리스너가 죽으면 — DB는 커밋, 클라이언트는 500

실험 1에서 "같은 스레드에서 동기 실행"이라는 걸 알았다. 그러면 리스너에서 예외가 터지면 어떻게 될까?

```java
@DisplayName("AFTER_COMMIT 리스너 예외 시: DB는 커밋되지만 예외는 호출자에게 전파된다")
@Test
void afterCommitListenerException_dbCommitted_butExceptionPropagated() {
    // given
    // 리스너가 RuntimeException을 던지도록 설정

    // when & then
    // 1) 서비스 호출 시 예외가 전파됨
    assertThatThrownBy(() -> productFacade.like(memberId, productId))
            .isInstanceOf(RuntimeException.class);

    // 2) 그런데 DB에는 좋아요가 저장되어 있음
    assertThat(likeRepository.findByMemberIdAndProductId(memberId, productId))
            .isPresent();
}
```

[스크린샷: 테스트 초록불 — 두 assert가 동시에 통과]

이 테스트가 통과한다는 게 바로 문제다. **DB에는 좋아요가 저장됐는데, 클라이언트는 500 에러를 받는다.** 만약 `@EventListener`였다면 리스너 예외가 호출자 트랜잭션을 롤백시키므로 DB에도 좋아요가 없고 클라이언트도 500을 받는다 — 불일치는 아니다. `@TransactionalEventListener`이기 때문에 "DB는 커밋됐는데 응답은 에러"라는 불일치가 생기는 것이다.

클라이언트는 "실패했구나"라고 판단하고 재시도할 수 있다. 그런데 DB에는 이미 저장돼 있다. 중복 처리 위험이 생기는 것이다.

| 시점 | DB 상태 | 클라이언트가 받는 응답 | 문제 |
|------|--------|---------------------|------|
| 커밋 전 | 좋아요 INSERT 대기 | - | - |
| 커밋 후 | 좋아요 저장 완료 | - | - |
| 리스너 예외 | 좋아요 그대로 | **500 에러** | 불일치 |

이 문제를 해결할 방법은 세 가지가 떠올랐다.

| 전략 | 동작 | 장점 | 단점 |
|------|------|------|------|
| A. try-catch로 삼킴 | 리스너에서 예외를 catch, 로그만 남김 | 단순함, 호출자에 영향 없음 | 실패를 즉시 감지 못함 |
| B. `@Async`로 분리 | 별도 스레드에서 실행, 예외가 아예 전파 안 됨 | 응답도 빠르고 예외도 격리 | 트랜잭션 컨텍스트 없음, 실행 순서 보장 안 됨 |
| C. `@EventListener`로 변경 | 같은 TX에서 실행, 실패 시 함께 롤백 | 정합성 보장 | 부가 로직 실패가 주요 로직을 롤백시킴 — 원래 분리하려 했던 이유 자체가 사라짐 |

B도 생각해봤는데, `@Async`를 붙이면 예외가 호출자에게 아예 전파되지 않으므로 실패를 즉시 감지할 수 없다. 좋아요 집계처럼 단순한 로직에 비동기 스레드풀 관리와 별도 모니터링 체계까지 추가하는 건 과잉 대응이라고 판단했다. C는 처음에 이벤트로 분리한 이유 자체("부가 로직 실패가 주요 로직을 롤백시키면 안 된다")를 부정하게 된다.

결국 A를 선택했다.

```java
// LikeEventListener — try-catch로 예외를 삼킴
public void handleProductLiked(ProductLikedEvent event) {
    try {
        productService.incrementLikeCount(event.productId());
    } catch (Exception e) {
        log.error("좋아요 집계 실패: productId={}", event.productId(), e);
        // 예외를 삼킴 → 호출자에게 전파되지 않음
    }
}
```

> **포기한 것**: 예외를 삼키면 집계가 실패해도 사용자에게 200이 내려간다. "좋아요는 성공했는데 likeCount가 안 올라갔네?"라는 불일치가 생길 수 있다. 하지만 좋아요 자체가 실패 처리되는 것보다는 집계 불일치가 덜 치명적이라고 판단했다. 집계는 배치로 보정할 수 있지만, 사용자의 좋아요가 유실되면 되돌릴 수 없다.

---

### 실험 3. 트랜잭션 없이 이벤트를 발행하면 — 조용히 사라진다

이건 실제로 겪고 나서 알게 된 함정이다. **에러 로그도 없이 리스너가 안 도는 상황**이 있다.

```java
@DisplayName("트랜잭션 없이 발행된 이벤트는 @TransactionalEventListener에 도달하지 않는다")
@Test
void noTransaction_eventSilentlyIgnored() {
    // given - 트랜잭션 없는 메서드에서 이벤트 발행

    // when
    eventPublisher.publishEvent(new ProductLikedEvent(productId, memberId, true));

    // then - 리스너가 호출되지 않음
    verify(likeEventListener, never()).handleProductLiked(any());
}
```

```java
@DisplayName("fallbackExecution=true면 트랜잭션 없이도 리스너가 실행된다")
@Test
void noTransaction_withFallbackExecution_listenerExecuted() {
    // given - 트랜잭션 없는 메서드에서 이벤트 발행

    // when
    eventPublisher.publishEvent(new OrderPaidEvent(...));

    // then - fallbackExecution=true인 리스너는 실행됨
    verify(userActionEventListener, times(1)).logOrderPaid(any());
}
```

[스크린샷: 두 테스트를 나란히 — 같은 이벤트 발행인데 리스너 설정에 따라 결과가 다름]

같은 `publishEvent()` 호출인데, `fallbackExecution` 설정 하나 차이로 리스너가 실행되거나 무시된다. 문제는 무시될 때 **아무 로그도 남지 않는다**는 것이다. 이벤트가 발행됐는데 리스너가 안 돌아가는 상황에서 원인을 찾기가 매우 어렵다.

프로젝트에서 `OrderPaymentFacade`는 결제를 트랜잭션 밖에서 처리한다. PG 호출이 트랜잭션 안에 있으면 PG 응답 대기 시간만큼 DB 커넥션을 잡고 있게 되기 때문이다. 그래서 결제 후 이벤트를 발행하는 시점에는 트랜잭션이 없다.

```java
// OrderEventListener — fallbackExecution=true 설정한 이유가 바로 이것
@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)
public void handleOrderPaid(OrderPaidEvent event) { ... }

// UserActionEventListener — ProductLikedEvent만 fallbackExecution 미설정
// 좋아요는 반드시 @Transactional 안에서 발행되므로 필요 없음
@TransactionalEventListener(phase = AFTER_COMMIT)  // fallback 없음
public void logProductLiked(ProductLikedEvent event) { ... }
```

`fallbackExecution`을 모든 리스너에 일괄로 `true` 설정하지 않은 이유는, 좋아요처럼 **반드시 트랜잭션 안에서만 발행되어야 하는 이벤트**가 트랜잭션 없이 발행되는 건 버그 신호이기 때문이다. 이때는 무시되는 게 맞다.

만약 좋아요 이벤트에도 `fallbackExecution=true`를 넣으면 어떻게 될까? 누군가 실수로 `@Transactional`을 빼먹어도 리스너가 정상 실행된다. 트랜잭션 없이 좋아요가 발행되는 건 버그인데, 그 버그가 조용히 동작하게 되는 셈이다. 결국 `fallbackExecution=false`가 **"이 이벤트는 반드시 트랜잭션 안에서 발행되어야 한다"**는 제약을 강제하는 안전장치 역할을 하는 것이다.

> **판단 기준 정리**: 트랜잭션 밖에서 발행될 수 있는 이벤트(결제 후 이벤트, readOnly 조회 이벤트) → `fallbackExecution=true`. 반드시 트랜잭션 안에서만 발행되어야 하는 이벤트(좋아요) → `fallbackExecution=false` (버그 감지용).

---

### 실험 4. AFTER_COMMIT 리스너에서 DB 쓰기 — REQUIRES_NEW가 필요한 이유

`LikeEventListener`는 `AFTER_COMMIT`에서 `likeCount`를 UPDATE해야 한다. 근데 "커밋 후"면 트랜잭션이 끝난 상태 아닌가? 트랜잭션 없이 UPDATE가 되나?

```java
@DisplayName("AFTER_COMMIT 리스너에서 REQUIRES_NEW로 DB 쓰기 시 정상 반영된다")
@Test
void afterCommitListener_withRequiresNew_dbWriteSuccess() {
    // given - REQUIRES_NEW가 있는 LikeEventListener

    // when
    productFacade.like(memberId, productId);

    // then
    Product product = productRepository.findById(productId).orElseThrow();
    assertThat(product.getLikeCount()).isEqualTo(1);
}
```

[스크린샷: REQUIRES_NEW 있을 때 / 없을 때 결과 비교]

`REQUIRES_NEW` 없이 DB 쓰기를 시도하면 어떻게 되는지도 확인해봤다.

사실 `REQUIRES_NEW` 없이도 DB 쓰기 자체는 실행될 수 있다. AFTER_COMMIT 시점에 기존 트랜잭션은 커밋되었지만 JDBC 커넥션은 아직 반환되지 않은 상태이고, auto-commit 모드로 전환되어 개별 SQL이 즉시 커밋되기 때문이다.

문제는 **트랜잭션 경계가 없다**는 것이다. 리스너 안에서 UPDATE 2개를 실행하는데 첫 번째만 성공하고 두 번째가 실패하면, 첫 번째 UPDATE는 이미 auto-commit으로 반영되어 있다. 부분 커밋이 발생하는 것이다. `REQUIRES_NEW`를 붙여야 리스너 내부의 DB 작업들이 하나의 트랜잭션으로 묶여서 원자성이 보장된다.

```java
// LikeEventListener — REQUIRES_NEW로 원자성 보장
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void handleProductLiked(ProductLikedEvent event) {
    if (event.liked()) {
        productService.incrementLikeCount(event.productId());
    } else {
        productService.decrementLikeCount(event.productId());
    }
}
```

> 처음에는 "REQUIRES_NEW 없으면 DB 쓰기가 안 되겠지"라고 생각했는데, 실제로는 auto-commit 모드로 실행이 되긴 했다. 하지만 트랜잭션 경계 없이 실행되는 건 단일 UPDATE에서는 괜찮아 보여도, 리스너 로직이 복잡해지는 순간 부분 커밋이라는 더 찾기 어려운 문제를 만든다. "동작한다"와 "안전하다"는 다른 문제라는 걸 이때 알게 됐다.

> **주의: REQUIRES_NEW의 커넥션 비용.** `REQUIRES_NEW`는 새 트랜잭션을 위해 커넥션 풀에서 커넥션을 하나 더 가져온다. 그런데 `@Async` 없이 동기 실행되는 리스너라면, 호출자가 잡고 있는 커넥션이 아직 반환되지 않은 상태에서 리스너가 추가 커넥션을 요청하게 된다. 즉 한 스레드가 커넥션 2개를 동시에 점유한다. 트래픽이 몰리면 Connection Pool Starvation(커넥션 풀 고갈)이 발생할 수 있다. 프로젝트에서 `KafkaEventPublishListener`와 `OutboxEventListener`에 `@Async`를 붙인 건 응답 시간뿐 아니라 이 커넥션 점유 문제를 피하기 위한 이유도 있다.

---

### 실험 5. 이벤트는 유실된다 — 그리고 복구할 수 없다

이게 가장 치명적인 함정이다.

`@TransactionalEventListener`에는 **재시도 메커니즘이 없다.** 리스너가 실패하면 그 이벤트는 영원히 유실된다. Spring이 다시 호출해주지 않는다.

```java
@DisplayName("AFTER_COMMIT 리스너 실패 시 이벤트는 재시도되지 않고 유실된다")
@Test
void afterCommitListenerFails_eventLostForever() {
    // given - LikeEventListener가 예외를 던지도록 설정

    // when
    productFacade.like(memberId, productId);

    // 일정 시간 대기 (재시도가 일어나는지 확인)
    Thread.sleep(3000);

    // then
    // 1) 좋아요 상태는 커밋됨 (주요 로직 성공)
    assertThat(likeRepository.findByMemberIdAndProductId(memberId, productId))
            .isPresent();

    // 2) likeCount는 0 (집계 실패, 재시도 없음)
    Product product = productRepository.findById(productId).orElseThrow();
    assertThat(product.getLikeCount()).isEqualTo(0);

    // 3) 리스너는 정확히 1번만 호출됨 (재시도 없음)
    verify(likeEventListener, times(1)).handleProductLiked(any());
}
```

[스크린샷: "좋아요는 있는데 likeCount는 0" — 불일치 상태가 증거]

좋아요는 있는데 likeCount는 0이다. **데이터 불일치가 발생했고, Spring Event만으로는 복구할 방법이 없다.**
이 문제는 리스너에서 try-catch로 삼키는 것만으로는 해결되지 않는다. 삼키면 500은 안 나지만, 집계 자체가 누락되는 건 마찬가지다.

그렇다면 이걸 어떻게 해결할 수 있을까? 몇 가지 방법을 생각해봤다.

| 전략 | 동작 방식 | 장점 | 단점 |
|------|----------|------|------|
| A. 리스너 내부에서 직접 재시도 | catch 블록에서 3회 retry | 구현 간단 | 동기 실행이라 API 응답 지연 (실험 1의 문제 재발) |
| B. Spring Retry + `@Async` | 비동기 스레드에서 재시도 | 응답 영향 없음 | 서버 재시작 시 메모리의 재시도 큐 유실 — 결국 같은 문제 |
| C. Redis에 이벤트 저장 | Redis에 기록 후 Worker가 폴링 | 빠른 읽기/쓰기 | 비즈니스 TX와 원자성 보장 불가 (Redis는 별도 시스템이라 "좋아요는 저장됐는데 Redis에는 기록 안 됨"이 가능) |
| D. Transactional Outbox | 같은 DB, 같은 TX에 기록 | 비즈니스와 이벤트의 원자성 보장 | DB 쓰기 부하 증가 |

A는 실험 1에서 확인한 "동기 실행 → 응답 지연" 문제를 다시 악화시킨다. B는 서버가 죽으면 메모리에 있던 재시도 큐가 날아간다 — 결국 유실 문제가 그대로다. C는 매력적이었지만, Redis와 MySQL이 별도 시스템이라 원자성을 보장할 수 없다. "좋아요 INSERT는 커밋됐는데 Redis 기록은 실패"하면 이벤트가 유실되고, 반대로 "Redis에 기록됐는데 좋아요 INSERT가 롤백"되면 존재하지 않는 좋아요에 대한 이벤트가 발행된다.

D만이 **같은 DB 트랜잭션으로 비즈니스 로직과 이벤트 기록의 원자성을 보장**할 수 있다. DB 쓰기 부하가 늘어나는 건 사실이지만, 유실되면 안 되는 이벤트에만 선택적으로 적용하면 감수할 만하다고 판단했다.

---

## Part 3. 왜 이렇게 동작하는가 — ThreadLocal + TransactionSynchronization

실험 1~5의 결과가 왜 그렇게 나오는지, 한 번에 설명할 수 있는 다이어그램이 있다.

Spring이 `@TransactionalEventListener`를 처리하는 내부 흐름이다.

```
@Transactional 메서드 시작
  │
  ├─ ① TransactionSynchronizationManager (ThreadLocal)
  │     → 현재 스레드에 트랜잭션 활성 상태 저장
  │
  ├─ ② 비즈니스 로직 (DB INSERT)
  │
  ├─ ③ publishEvent(OrderPaidEvent)
  │     └─ 리스너를 즉시 실행하지 않음
  │        ThreadLocal의 synchronizations 리스트에 콜백 등록
  │
  ├─ ④ DB COMMIT
  │
  ├─ ⑤ triggerAfterCommit()
  │     └─ 등록된 콜백(리스너)을 같은 스레드에서 실행
  │          └─ 예외 발생 시 호출 스택을 타고 올라감
  │
  └─ ⑥ 응답 반환
```

핵심은 **"같은 스레드 = 같은 ThreadLocal 컨텍스트"**라는 동작 원리다.
이 다이어그램 하나로 실험 1~5의 결과가 전부 설명된다.

| 실험 | 왜 그렇게 동작하는가 |
|------|---------------------|
| 실험 1 (동기 실행) | ⑤에서 같은 스레드의 콜백으로 실행되니까 |
| 실험 2 (예외 전파) | ⑤에서 터진 예외가 호출 스택을 타고 ⑥으로 전파되니까 |
| 실험 3 (이벤트 무시) | ①에서 ThreadLocal에 트랜잭션이 없으면 ③에서 콜백을 등록할 곳이 없으니까 |
| 실험 4 (REQUIRES_NEW) | ⑤ 시점에 ④에서 기존 트랜잭션이 이미 닫혀 있으니까 |
| 실험 5 (재시도 없음) | 콜백은 1회성이고, 실패해도 다시 호출하는 메커니즘이 없으니까 |

> 개별 실험을 하나씩 돌릴 때는 "왜 이러지?"라는 의문이었는데, 내부 구조를 알고 나니 전부 같은 원리에서 나온 결과라는 걸 알게 됐다. ThreadLocal 기반이라서 같은 스레드에서 동기 실행되고, 콜백이라서 1회성이고, 트랜잭션 종료 후에 실행되니까 새 트랜잭션이 필요하다.

---

## Part 4. 실험 6 — Outbox Pattern으로 유실을 막을 수 있는가?

실험 5에서 "이벤트가 유실되고 복구할 수 없다"는 문제를 확인했다.
이걸 해결하기 위해 **Transactional Outbox Pattern**을 도입했다.

핵심 아이디어는 단순하다. 이벤트를 "메모리의 콜백"이 아니라 **"DB 테이블에 기록"**하는 것이다.

```
실험 5 (Spring Event만)              실험 6 (Outbox Pattern)
─────────────────────              ──────────────────────

TX 커밋 O                           TX 커밋 O
  │                                   ├─ outbox_event INSERT (같은 TX)
  v                                   v
리스너 실행 → 실패 X                  즉시 발행 시도 → 실패 X
  │                                   │
  v                                   v
이벤트 유실 (복구 불가)               outbox에 published=false로 남아 있음
                                      │
                                      v
                                    스케줄러 실행 → 재시도 → 발행 성공 O
```

비즈니스 로직과 같은 트랜잭션에서 `outbox_event`를 INSERT하니까, 비즈니스가 커밋되면 이벤트 기록도 반드시 커밋된다. 발행이 실패해도 `published=false` 레코드가 DB에 남아 있으니까, 스케줄러가 주기적으로 조회해서 재시도할 수 있다.

이걸 실험 5와 같은 조건에서 테스트해봤다.

### 실험 6-A. 비즈니스 로직과 Outbox INSERT의 원자성

```java
@DisplayName("Outbox: 비즈니스 로직과 Outbox INSERT가 같은 트랜잭션으로 원자적 커밋된다")
@Test
void outbox_atomicCommitWithBusinessLogic() {
    // given - 좋아요 실행 전 outbox_event 비어있음

    // when
    productFacade.like(memberId, productId);

    // then
    // 1) 좋아요가 저장됨
    assertThat(likeRepository.findByMemberIdAndProductId(memberId, productId))
            .isPresent();

    // 2) outbox_event에 published=false 레코드가 함께 커밋됨
    List<OutboxEvent> events = outboxRepository.findByPublishedFalse();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).getEventType()).isEqualTo("PRODUCT_LIKED");
}
```

### 실험 6-B. 비즈니스 롤백 시 Outbox도 함께 롤백

```java
@DisplayName("Outbox: 비즈니스 로직이 롤백되면 Outbox INSERT도 함께 롤백된다")
@Test
void outbox_rollbackWithBusinessLogic() {
    // given - 존재하지 않는 상품 ID

    // when & then
    assertThatThrownBy(() -> productFacade.like(memberId, invalidProductId))
            .isInstanceOf(EntityNotFoundException.class);

    // outbox_event에도 레코드가 없음 (같은 TX에서 롤백)
    assertThat(outboxRepository.findByPublishedFalse()).isEmpty();
}
```

### 실험 6-C. 즉시 발행 실패 → 스케줄러가 재시도

```java
@DisplayName("Outbox: 즉시 발행이 실패해도 스케줄러가 재시도하여 이벤트가 유실되지 않는다")
@Test
void outbox_immediatePublishFails_schedulerRetries() {
    // given - Kafka 발행이 실패하도록 설정

    // when
    productFacade.like(memberId, productId);

    // then
    // 1) 즉시 발행 실패 → outbox에 published=false로 남아 있음
    List<OutboxEvent> unpublished = outboxRepository.findByPublishedFalse();
    assertThat(unpublished).hasSize(1);

    // 2) Kafka 복구 후 스케줄러 수동 실행
    outboxScheduler.publishPendingEvents();

    // 3) 이제 published=true → 이벤트 유실 없음
    assertThat(outboxRepository.findByPublishedFalse()).isEmpty();
}
```

[스크린샷: 실험 5(유실) vs 실험 6-C(재시도 성공) 나란히 비교]

실험 5에서는 "좋아요 O, likeCount 0, 재시도 없음"이었다.
실험 6에서는 "좋아요 O, outbox에 기록, 스케줄러 재시도 → 발행 성공"이다.
**같은 실패 시나리오인데 결과가 완전히 다르다.**

차이를 만든 건 딱 하나다. 이벤트가 **메모리에만 존재하느냐, DB에 기록되느냐.**

실제 코드에서는 이렇게 구현했다.

```java
// CouponIssueFacade — 같은 TX에서 비즈니스 로직 + OutboxEvent INSERT (원자성 보장)
@Transactional
public CouponIssueStatusInfo requestIssue(Member member, Long couponId) {
    MemberCoupon memberCoupon = couponIssueService.createIssueRequest(member.getId(), couponId);
    OutboxEvent outboxEvent = createCouponIssueOutbox(couponId, member.getId(), memberCoupon.getId());
    outboxEventRepository.save(outboxEvent);
    // TX 커밋 후 즉시 Kafka 발행 시도
    eventPublisher.publishEvent(new OutboxEvent.Published(outboxEvent));
    return CouponIssueStatusInfo.from(memberCoupon);
}

// OutboxEventListener — AFTER_COMMIT + @Async로 즉시 발행 시도 (실패해도 스케줄러가 재시도)
@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void publishAfterCommit(OutboxEvent.Published event) {
    try {
        eventPublisher.publish(event.outboxEvent());
    } catch (Exception e) {
        log.warn("즉시 발행 실패 — 스케줄러가 재시도 예정: eventId={}",
                event.outboxEvent().getEventId(), e);
    }
}
```

> **포기한 것**: Outbox 테이블에 INSERT하는 만큼 DB 쓰기 부하가 늘어난다. 모든 이벤트를 Outbox로 처리하면 DB가 이벤트 저장소 역할까지 해야 한다. 그래서 프로젝트에서는 Kafka로 전파해야 하는 이벤트만 Outbox를 태우고, JVM 내부 부가 로직(로깅, likeCount 집계)은 기존 Spring Event를 유지했다. 모든 이벤트에 Outbox를 적용하는 게 아니라, **유실되면 안 되는 이벤트에만 선택적으로 적용**하는 것이 현실적이다.
>
> **운영 고려: Outbox 테이블 정리.** 발행 완료된(`published=true`) 레코드는 삭제하지 않고 남겨두어 감사 추적과 장애 분석에 활용한다. 대신 테이블이 계속 커지므로, 운영에서는 일정 기간(예: 7일) 이상 지난 `published=true` 레코드를 배치로 삭제하는 정리 정책이 필요하다. 프로젝트에서는 아직 초기 단계라 정리 배치를 도입하지 않았지만, 이벤트 볼륨이 늘어나면 반드시 추가해야 할 부분이다.

### 실험 6-D. Outbox는 At Least Once다 — 중복 발행은 어떻게 막는가?

Outbox Pattern으로 유실은 막았다. 그런데 한 가지 간과한 게 있다. 즉시 발행이 성공했는데 `markPublished()`가 실패하면? 또는 네트워크 지연으로 Kafka ACK가 늦어져서 스케줄러가 같은 이벤트를 다시 발행하면? **같은 이벤트가 2번 이상 Consumer에 도달할 수 있다.**

```
TX 커밋 → 즉시 발행 시도 → Kafka 전송 성공
  └─ markPublished() 전에 서버가 죽음
       └─ published=false 그대로 → 스케줄러가 다시 발행
            └─ Consumer가 같은 이벤트를 2번 수신
```

Outbox가 보장하는 건 **At Least Once**(최소 1회 발행)이지, **Exactly Once**(정확히 1회)가 아니다. 유실은 막았지만 중복이라는 새로운 문제가 생긴 것이다.

이걸 Consumer 쪽에서 어떻게 방어하는지 테스트해봤다.

```java
@DisplayName("같은 eventId로 중복 수신 시 두 번째는 무시된다")
@Test
void duplicateEventId_isProcessedOnce() {
    // given - 같은 eventId를 가진 쿠폰 발급 이벤트
    String eventId = UUID.randomUUID().toString();

    // when - 같은 이벤트를 2번 처리
    boolean firstResult = couponIssueEventService.handle(eventId, couponId, memberId, memberCouponId);
    boolean secondResult = couponIssueEventService.handle(eventId, couponId, memberId, memberCouponId);

    // then
    assertThat(firstResult).isTrue();   // 첫 번째는 정상 처리
    assertThat(secondResult).isFalse(); // 두 번째는 skip

    // 쿠폰은 정확히 1장만 AVAILABLE
    MemberCoupon coupon = memberCouponRepository.findById(memberCouponId).orElseThrow();
    assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.AVAILABLE);
}
```

핵심은 `event_handled` 테이블이다.

```
┌─────────────────────────────────────────────────────┐
│  event_handled (PK: event_id)                       │
├─────────────────────────────────────────────────────┤
│  Consumer가 이벤트를 처리하기 전에 event_id로 조회    │
│  ├─ 이미 존재 → skip (중복 이벤트)                   │
│  └─ 없음 → 처리 후 INSERT (처리 완료 기록)           │
└─────────────────────────────────────────────────────┘
```

| 시점 | event_handled | 쿠폰 상태 | 비고 |
|------|--------------|----------|------|
| 첫 번째 수신 | INSERT(eventId) | REQUESTED → AVAILABLE | 정상 처리 |
| 두 번째 수신 (중복) | eventId 이미 존재 → skip | AVAILABLE 유지 | 중복 방어 |

```java
// CouponIssueEventService — 멱등 처리 구현
public boolean handle(String eventId, Long couponId, Long memberId, Long memberCouponId) {
    if (eventHandledRepository.existsById(eventId)) {
        log.info("이미 처리된 이벤트 — skip: eventId={}", eventId);
        return false;
    }

    // 쿠폰 발급 처리 (REQUESTED → AVAILABLE or FAILED)
    processCouponIssue(couponId, memberId, memberCouponId);

    // 처리 완료 기록
    eventHandledRepository.save(new EventHandled(eventId));
    return true;
}
```

> **Outbox + Consumer 멱등성의 조합**: 사실 분산 시스템에서 완벽한 Exactly Once는 불가능에 가깝다. 네트워크 파티션, 서버 장애, 타이밍 이슈가 겹치면 어딘가에서 반드시 중복이나 유실이 발생할 수 있기 때문이다. 그래서 "Exactly Once처럼 보이게 만드는 것"이 현실적인 목표가 된다. Producer 쪽의 Outbox가 "최소 1번은 반드시 발행(At Least Once)"을 보장하고, Consumer 쪽의 `event_handled`가 "2번 이상 처리되지 않음"을 보장한다. 이 둘의 협업으로 **"정확히 1번 처리"에 가까운 동작**이 만들어진다. 어느 한쪽만으로는 불가능하다 — Outbox 없이 멱등성만 있으면 유실된 이벤트는 재처리할 수 없고, 멱등성 없이 Outbox만 있으면 같은 쿠폰이 2번 발급될 수 있다.

---

### 쿠폰 발급에 Outbox를 적용한 이유

Outbox Pattern의 핵심은 **비즈니스 로직과 이벤트 저장을 같은 TX에서 원자적으로 처리**하는 것이다.

```
@Transactional {
  ① MemberCoupon INSERT (REQUESTED)
  ② OutboxEvent INSERT                ← 같은 TX → 원자적
} COMMIT
  → AFTER_COMMIT에서 즉시 Kafka 발행 시도 (빠른 응답)
    → 성공: published=true
    → 실패: OutboxScheduler가 10초 후 재시도 (안전망)
```

왜 같은 TX에서 Outbox를 INSERT하는가?
- MemberCoupon INSERT가 성공하면 OutboxEvent INSERT도 반드시 성공 (롤백되면 둘 다 롤백)
- "REQUESTED는 저장됐는데 Kafka 메시지는 없는" 상태가 원천 차단된다

왜 커밋 후 즉시 발행을 시도하는가?
- 스케줄러만 의존하면 최대 10초 지연 → 선착순 쿠폰에서 UX 문제
- 즉시 발행 + 스케줄러 재시도 조합으로 빠른 응답과 유실 방지를 동시에 달성

```java
// 실제 사용 예 — CouponIssueFacade.requestIssue()
@Transactional
public CouponIssueStatusInfo requestIssue(Member member, Long couponId) {
    MemberCoupon memberCoupon = couponIssueService.createIssueRequest(member.getId(), couponId);

    OutboxEvent outboxEvent = createCouponIssueOutbox(couponId, member.getId(), memberCoupon.getId());
    outboxEventRepository.save(outboxEvent);

    // TX 커밋 후 즉시 Kafka 발행 시도 (OutboxEventListener가 AFTER_COMMIT에서 처리)
    eventPublisher.publishEvent(new OutboxEvent.Published(outboxEvent));

    return CouponIssueStatusInfo.from(memberCoupon);
}
```

Outbox Pattern이 적합한 이벤트: **유실 시 사용자가 직접 영향을 받는 경우**. 쿠폰 발급 요청이 유실되면 사용자는 REQUESTED 상태에서 영원히 멈추게 된다.

---

## Part 5. 유실 영향도에 따른 발행 전략 구분

Part 4에서 Outbox Pattern으로 이벤트 유실을 방지하는 방법을 설명했다. 그런데 **모든 이벤트에 Outbox가 필요한 건 아니다**. 이벤트마다 "유실 시 비즈니스 영향"이 다르고, Outbox의 운영 비용(테이블 관리, 스케줄러 모니터링)도 고려해야 한다.

### 2가지 이벤트 발행 전략

```
┌──────────────────────────────────────────────────────────────────┐
│ 직접 Kafka 발행 (통계성 이벤트)                                     │
│ "유실돼도 배치 보정 가능"                                           │
│                                                                    │
│ @Transactional {                                                   │
│   ① 비즈니스 로직 (좋아요 INSERT, 주문 완료 등)                      │
│   ② publishEvent(ProductLikedEvent / OrderPaidEvent)               │
│ } COMMIT                                                           │
│   → @TransactionalEventListener(AFTER_COMMIT)                      │
│   → KafkaEventPublishListener가 직접 Kafka 발행                    │
│     → 실패 시 로그만 남김 (유실 허용)                                │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ Outbox Pattern (사용자 대면 기능)                                   │
│ "유실 불가 — 비즈니스와 원자적 저장 + 재시도 보장"                     │
│                                                                    │
│ @Transactional {                                                   │
│   ① 비즈니스 로직 (MemberCoupon INSERT, REQUESTED)                 │
│   ② OutboxEvent INSERT (같은 TX)     ← 원자성 보장                 │
│   ③ publishEvent(OutboxEvent.Published)                            │
│ } COMMIT                                                           │
│   → AFTER_COMMIT에서 즉시 Kafka 발행 시도                          │
│     → 성공: published=true                                         │
│     → 실패: OutboxScheduler가 10초 후 재시도                       │
└──────────────────────────────────────────────────────────────────┘
```

### 왜 이렇게 나누는가 — 실제 적용 사례

| 이벤트 | 전략 | 선택 이유 |
|--------|------|----------|
| **좋아요** (ProductLikedEvent) | 직접 Kafka 발행 | 유실 시 like_count 1 틀림 → 배치 보정 가능. Outbox 비용 대비 이점 없음 |
| **상품 조회** (ProductViewedEvent) | 직접 Kafka 발행 | 조회수 1건 누락 허용. 통계성 이벤트 |
| **주문 완료** (OrderPaidEvent) | 직접 Kafka 발행 | order_count 집계용. 유실 시 판매량 통계 약간 틀림 → 배치 보정 가능 |
| **선착순 쿠폰 요청** (COUPON_ISSUE_REQUESTED) | Outbox Pattern | 유실 시 REQUESTED 상태에서 영원히 멈춤. 사용자가 직접 영향 받음 |

### 이벤트 발행 전략 선택 기준 정리

```
이벤트 유실 시 사용자가 직접 영향을 받는가?
│
├─ NO (통계/집계용) → 직접 Kafka 발행
│   KafkaEventPublishListener가 AFTER_COMMIT에서 발행
│   실패 시 유실 허용 (배치 보정 가능)
│
└─ YES (사용자 대면 기능) → Outbox Pattern
    비즈니스 로직과 같은 TX에서 OutboxEvent INSERT
    커밋 후 즉시 발행 + 스케줄러 재시도
```

> **결론**: "Outbox를 쓰면 안전하다"가 아니라, **유실 시 비즈니스 영향도**로 판단해야 한다. 통계성 이벤트(좋아요, 조회수, 판매량)에 Outbox를 쓰는 건 과잉 엔지니어링이다. Outbox는 유실이 사용자에게 직접 영향을 주는 경우(쿠폰 발급)에만 적용했다.

---

## 마치며

처음에는 `@TransactionalEventListener`를 붙이기만 하면 되는 줄 알았다. "커밋 후에 안전하게 실행된다"니까, 이벤트로 분리하면 끝이라고 생각했다.

근데 실험 1에서 동기 실행이라는 걸 알고 `@Async`를 붙였고, 실험 2에서 예외 전파를 발견하고 try-catch를 넣었다. 실험 3에서 이벤트가 사라지는 걸 보고 `fallbackExecution`을 설정했고, 실험 4에서 DB 쓰기가 안 되는 걸 확인하고 `REQUIRES_NEW`를 추가했다. 하나를 해결할 때마다 다음 문제가 나왔고, 실험 5에서 "이벤트가 유실되면 Spring Event만으로는 복구가 불가능하다"는 결론에 도달했다. 결국 Outbox Pattern까지 오는 데 5단계가 걸렸다. 그리고 Outbox가 보장하는 건 At Least Once이지 Exactly Once가 아니라는 걸 알게 됐다. 유실은 막았지만 중복 발행이라는 새로운 문제가 생겼고, Consumer 쪽에서 `event_handled` 테이블로 멱등성을 보장해야 했다. 또한 Outbox를 실제로 적용하면서 **모든 이벤트에 Outbox가 필요한 건 아니라는 걸 깨달았다**. 통계성 이벤트(좋아요, 조회수, 판매량)는 유실돼도 배치로 보정할 수 있으니 직접 Kafka 발행으로 충분하고, Outbox는 유실 시 사용자가 직접 영향을 받는 경우(선착순 쿠폰)에만 적용했다. **유실 영향도**라는 기준이 생기니, 이벤트마다 적절한 전략을 선택하는 판단이 명확해졌다.

| 함정 | 내 판단 | 고려한 대안 | 적용한 코드 |
|------|--------|-----------|------------|
| 동기 실행 | 무거운 작업은 `@Async` 분리 | — | `KafkaEventPublishListener`, `OutboxEventListener` |
| 예외 전파 | try-catch로 삼킴 | `@Async` 분리, `@EventListener` 전환 | `LikeEventListener.handleProductLiked()` |
| 이벤트 무시 | `fallbackExecution` 선택적 설정 | 일괄 true (버그 감지 불가) | `OrderEventListener`, `UserActionEventListener` |
| DB 쓰기 불가 | `REQUIRES_NEW` 사용 | — | `LikeEventListener` |
| 이벤트 유실 | Transactional Outbox Pattern | 리스너 직접 재시도, Spring Retry, Redis 기록 | `OutboxEventListener` + 스케줄러 |
| 중복 발행 | Consumer 멱등성 (`event_handled` 테이블) | Kafka Exactly Once, 애플리케이션 락 | `CouponIssueEventService` + `event_handled` PK |
| 발행 전략 선택 | 유실 영향도에 따라 직접 발행 vs Outbox 구분 | 일괄 Outbox 적용 | 통계→직접 발행, 쿠폰→Outbox |

결국 이번에 가장 크게 느낀 건, **문서에 적힌 동작과 실제 동작 사이에는 간극이 있다**는 것이다. "커밋 후 실행"이라는 한 줄만 보고 도입했다면, 운영 중에 이 5가지 함정을 하나씩 마주하게 됐을 것이다. 테스트로 미리 확인했기 때문에, 각 함정에 대해 대안을 비교하고 근거를 가지고 판단을 내릴 수 있었다.

그리고 각 판단에는 "포기한 것"이 있다. `@Async`를 쓰면 실패를 즉시 감지하지 못하고, try-catch를 쓰면 집계 불일치가 생길 수 있고, Outbox를 쓰면 DB 쓰기 부하가 늘어난다. 정답이 있는 게 아니라, **"이 상황에서 어떤 비용을 감수할 것인가"**를 선택하는 것이다. 서킷 브레이커 설정에서 "빠른 감지 vs 적은 오판"을 저울질했던 것처럼, 이벤트 설계에서도 "결합도 vs 신뢰성"을 매번 저울질해야 했다.

Spring Event로 경계를 나누면 결합도가 낮아진다. 하지만 **낮은 결합도의 대가로 신뢰성이라는 새로운 문제를 떠안게 된다.** "메모리의 콜백"에서 "DB의 레코드"로 — 이 한 단계가 신뢰성의 차이를 만든다는 걸, 6번의 실험을 거치면서 체감했다.

---

## (부록) 테스트 실행 및 증거 수집 계획

### 테스트 종류

| 실험 | 테스트 레벨 | 필요 인프라 | Mock 여부 |
|------|-----------|-----------|----------|
| 실험 1 (동기 실행) | 단위/통합 | 없음 or Testcontainers | 리스너 spy |
| 실험 2 (예외 전파 + DB 커밋) | 통합 | Testcontainers (MySQL) | 리스너 spy |
| 실험 3 (트랜잭션 없이 발행) | 단위/통합 | 없음 or Testcontainers | 리스너 mock |
| 실험 4 (REQUIRES_NEW) | 통합 | Testcontainers (MySQL) | 없음 |
| 실험 5 (이벤트 유실) | 통합 | Testcontainers (MySQL) | 리스너 spy |
| 실험 6-A (Outbox 원자성) | 통합 | Testcontainers (MySQL) | 없음 |
| 실험 6-B (Outbox 롤백) | 통합 | Testcontainers (MySQL) | 없음 |
| 실험 6-C (Outbox 재시도) | 통합 | Testcontainers (MySQL, Kafka) | Kafka publisher mock |
| 실험 6-D (Consumer 멱등성) | 통합 | Testcontainers (MySQL) | 없음 |

### 스크린샷 수집 계획

1. 각 테스트별 IDE 초록불 스크린샷 (1장씩)
2. 전체 테스트 초록불 스크린샷 (1장)
3. 실험 1의 로그 — 같은 스레드 이름 확인
4. 실험 5 vs 실험 6-C 나란히 비교 스크린샷

### 테스트 클래스 구조

```
src/test/java/com/loopers/application/event/
├── SpringEventBehaviorTest.java          ← 실험 1, 2, 3
├── TransactionalListenerDbWriteTest.java ← 실험 4
├── EventLossTest.java                    ← 실험 5
└── OutboxPatternTest.java                ← 실험 6-A, 6-B, 6-C
```
