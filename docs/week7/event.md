# Event 개념 정리

## 이벤트(Event)란?

**"과거에 일어난 사실(fact)의 기록"**

---

## Command vs Event

이벤트를 이해하려면 Command와의 차이를 먼저 알아야 한다.

|  | Command | Event |
|---|---------|-------|
| **시제** | 명령형 (미래) | 과거형 |
| **의미** | "이것을 해라" | "이것이 일어났다" |
| **예시** | `createOrder()` | `OrderCreated` |
| **실패** | 실패할 수 있음 | 이미 일어난 사실 |
| **대상** | 특정 수신자를 알고 호출 | 누가 듣는지 모름 |
| **결합도** | 발신자 → 수신자 직접 의존 | 발신자 → 수신자 의존 없음 |

프로젝트 예시:

```
[Command] "주문을 생성해라" → OrderService.createOrder()
                                 ↓
                              주문이 생성됨
                                 ↓
[Event]   "주문이 생성되었다" → OrderCreatedEvent
```

---

## 왜 구분이 중요한가?

### Command 방식 — 발신자가 모든 후속 처리를 직접 호출

```java
// Facade가 부가 로직을 전부 알고 있어야 함
public void placeOrder(...) {
    orderService.createOrder(...);       // 주문 생성
    loggingService.logUserAction(...);   // 로깅
    notificationService.notify(...);     // 알림
    metricsService.updateMetrics(...);   // 집계
    // → 부가 로직이 추가될 때마다 Facade 수정 필요
}
```

### Event 방식 — "무슨 일이 일어났는지"만 알려주면 끝

```java
// 누가 듣든 Facade는 모름
public void placeOrder(...) {
    orderService.createOrder(...);
    eventPublisher.publishEvent(new OrderCreatedEvent(orderId, memberId));
    // → 부가 로직이 추가되어도 Facade 수정 불필요
}
```

---

## 일상 비유

**Command**: 팀장이 "김 대리, 회의록 작성해. 박 대리, 회의실 정리해." 라고 각각 지시
- 팀장이 모든 후속 작업과 담당자를 알아야 함

**Event**: 팀장이 "회의 끝났습니다" 라고 공지
- 김 대리는 알아서 회의록 작성
- 박 대리는 알아서 회의실 정리
- 새 인턴이 와서 "회의 끝나면 간식 정리"를 해도 팀장은 몰라도 됨

---

## 정리

> 이벤트 = **"이미 일어난 사실"** 을 관심 있는 누군가가 **알아서 반응**할 수 있도록 알려주는 것

이 개념이 Spring의 `ApplicationEvent`로 구현되고, 시스템 간 전파가 필요하면 Kafka 같은 메시지 브로커로 확장된다.

---

## 자바에서 이벤트 구현 방식의 발전

### 1단계: Observer 패턴 (순수 자바)

GoF 디자인 패턴 중 하나. 자바에서 이벤트 구현의 가장 기본 형태다.

```java
// 1) 리스너 인터페이스 정의
public interface OrderEventListener {
    void onOrderCreated(Long orderId);
}

// 2) 발행자 — 리스너 목록을 직접 관리
public class OrderService {
    private final List<OrderEventListener> listeners = new ArrayList<>();

    public void addListener(OrderEventListener listener) {
        listeners.add(listener);
    }

    public void createOrder(Long orderId) {
        // 주문 생성 로직 ...

        // 등록된 리스너에게 알림
        for (OrderEventListener listener : listeners) {
            listener.onOrderCreated(orderId);
        }
    }
}

// 3) 리스너 구현
public class LoggingListener implements OrderEventListener {
    @Override
    public void onOrderCreated(Long orderId) {
        System.out.println("주문 " + orderId + " 생성됨 — 로깅 처리");
    }
}

// 4) 조립
OrderService orderService = new OrderService();
orderService.addListener(new LoggingListener());
orderService.addListener(new NotificationListener());
orderService.createOrder(1L);
```

**한계**:
- 발행자가 리스너 목록을 직접 관리해야 함
- 이벤트 종류가 늘어나면 인터페이스와 메서드가 계속 추가됨
- 리스너 등록/해제를 수동으로 해야 함

---

### 2단계: java.util.EventObject (JDK 내장)

JDK 1.1부터 제공된 이벤트 표준. AWT/Swing에서 사용하던 방식이다.

```java
// 1) 이벤트 객체 — EventObject 상속
public class OrderCreatedEvent extends java.util.EventObject {
    private final Long orderId;

    public OrderCreatedEvent(Object source, Long orderId) {
        super(source);  // 이벤트를 발생시킨 객체
        this.orderId = orderId;
    }

    public Long getOrderId() { return orderId; }
}

// 2) 리스너 인터페이스 — EventListener 상속
public interface OrderListener extends java.util.EventListener {
    void onOrderCreated(OrderCreatedEvent event);
}
```

**한계**:
- `EventObject`를 상속해야 하는 제약
- 리스너 관리는 여전히 수동
- 실무에서 직접 사용하는 경우는 거의 없음 (Swing/AWT 전용에 가까움)

---

### 3단계: Spring ApplicationEvent (Spring 전통 방식)

Spring이 Observer 패턴을 프레임워크 레벨로 끌어올린 것.
리스너 등록/관리를 Spring IoC 컨테이너가 자동으로 처리한다.

```java
// 1) 이벤트 — ApplicationEvent 상속 (Spring 4.2 이전 필수)
public class OrderCreatedEvent extends ApplicationEvent {
    private final Long orderId;

    public OrderCreatedEvent(Object source, Long orderId) {
        super(source);
        this.orderId = orderId;
    }

    public Long getOrderId() { return orderId; }
}

// 2) 리스너 — ApplicationListener 인터페이스 구현
@Component
public class OrderLoggingListener implements ApplicationListener<OrderCreatedEvent> {
    @Override
    public void onApplicationEvent(OrderCreatedEvent event) {
        // 로깅 처리
    }
}

// 3) 발행 — ApplicationEventPublisher 주입
@Service
@RequiredArgsConstructor
public class OrderService {
    private final ApplicationEventPublisher eventPublisher;

    public void createOrder(Long orderId) {
        // 주문 생성 로직 ...
        eventPublisher.publishEvent(new OrderCreatedEvent(this, orderId));
    }
}
```
**동작원리**:
- 리스너 목록을 List에 모아두고, 이벤트가 발행되면 for문으로 돌면서 타입이 맞는 리스너의 메서드를 호출.

| 구성 요소 | 역할 |
|-----------|------|
| `ApplicationEventPublisher` | 이벤트를 발행하는 인터페이스. `publishEvent()` 호출 |
| `ApplicationEventMulticaster` | 리스너 목록을 관리하고, 이벤트를 적절한 리스너에게 전달 |
| `EventListenerMethodProcessor` | 부팅 시 `@EventListener` 붙은 메서드를 찾아서 리스너로 등록 |
| `ApplicationListenerMethodAdapter` | `@EventListener` 메서드를 `ApplicationListener` 인터페이스로 감싸는 어댑터 |


**Observer 패턴 대비 개선점**:
- 리스너를 `@Component`로 등록하면 Spring이 자동으로 연결
- 발행자는 `ApplicationEventPublisher`만 의존, 리스너 목록을 직접 관리하지 않음

**남아있는 한계**:
- `ApplicationEvent` 상속 강제 → 이벤트가 Spring에 종속
- 리스너마다 인터페이스 구현 필요 → 보일러플레이트

---

### 4단계: Spring @EventListener (Spring 4.2+, 현재 권장)

**상속 없이 POJO만으로 이벤트를 정의**할 수 있게 됨. 현재 프로젝트에서 사용할 방식이다.

```java
// 1) 이벤트 — 순수 POJO (상속 없음)
public record OrderPaidEvent(Long orderId, Long memberId, Long totalAmount) {}

// 2) 발행 — 동일
@Service
@RequiredArgsConstructor
public class OrderService {
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void completePayment(Long orderId, Long memberId, Long amount) {
        // 결제 완료 로직 ...
        eventPublisher.publishEvent(new OrderPaidEvent(orderId, memberId, amount));
    }
}

// 3) 리스너 — 어노테이션 기반 (인터페이스 구현 불필요)
@Component
public class OrderEventListener {

    // 메서드 파라미터 타입으로 어떤 이벤트를 수신할지 결정
    @EventListener
    public void handleLogging(OrderPaidEvent event) {
        log.info("주문 결제 완료: orderId={}", event.orderId());
    }

    @EventListener
    public void handleMetrics(OrderPaidEvent event) {
        // 메트릭 집계 처리
    }
}
```

**핵심 개선점**:
- 이벤트 클래스가 Spring에 비종속 (순수 자바 객체)
- `record`로 불변 이벤트를 간결하게 정의
- 한 클래스에 여러 이벤트 핸들러 메서드 선언 가능
- 메서드 파라미터 타입만으로 이벤트 매칭

---

### 5단계: @TransactionalEventListener (트랜잭션 연동)

`@EventListener`의 확장. 트랜잭션의 특정 시점에 맞춰 실행된다.

```java
@Component
public class OrderEventListener {

    // 트랜잭션 커밋 후에만 실행 → 부가 로직에 적합
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleAfterCommit(OrderPaidEvent event) {
        // 주문 트랜잭션이 커밋된 후 실행
        // 이 메서드가 실패해도 주문은 이미 성공
    }
}
```

| Phase | 실행 시점 | 용도 |
|-------|----------|------|
| `BEFORE_COMMIT` | 커밋 직전 | 트랜잭션 내 추가 검증 |
| **`AFTER_COMMIT`** (기본값) | **커밋 성공 후** | **로깅, 알림, Kafka 발행** |
| `AFTER_ROLLBACK` | 롤백 후 | 실패 알림 |
| `AFTER_COMPLETION` | 커밋/롤백 후 | 리소스 정리 |

**주의**: `AFTER_COMMIT` 리스너 안에서 DB 쓰기를 하려면 새로운 트랜잭션이 필요하다.
(기존 트랜잭션은 이미 끝난 상태)

---

## 발전 흐름 요약

```
Observer 패턴 (순수 자바)
  → 리스너 목록 직접 관리, 수동 등록
    ↓
java.util.EventObject (JDK 내장)
  → 표준화했지만 상속 강제, 실무에서 거의 안 씀
    ↓
Spring ApplicationEvent + ApplicationListener
  → Spring IoC가 리스너 자동 연결, 그러나 상속/인터페이스 강제
    ↓
Spring @EventListener (4.2+)
  → POJO 이벤트, 어노테이션 기반, 상속 불필요  ← 현재 권장
    ↓
Spring @TransactionalEventListener
  → 트랜잭션 phase에 맞춰 실행, 주요/부가 로직 분리에 핵심
```

> 결국 방향은 하나다: **발행자와 리스너의 결합을 줄이고, 이벤트 자체를 단순한 데이터로 만드는 것**

---

## event_handled vs event_log — 왜 테이블을 분리하는가?

Kafka Consumer 측에서 이벤트를 처리할 때, **멱등성 보장용 테이블(event_handled)**과 **감사/디버깅용 테이블(event_log)**을 분리하는 이유를 정리한다.

---

### event_handled란?

**"이 이벤트를 이미 처리한 적 있는가?"**에 답하기 위한 멱등성 필터 테이블이다.

Kafka Consumer는 **At Least Once** 전달을 보장하므로, 같은 이벤트가 중복으로 들어올 수 있다.

```
1. Consumer가 이벤트를 받아서 product_metrics를 업데이트
2. Ack를 보내기 직전에 Consumer가 죽음
3. Kafka는 Ack를 못 받았으니 같은 이벤트를 다시 전달
4. Consumer가 재시작 → 같은 이벤트를 또 처리 → 좋아요 수가 2번 올라감
```

이를 방지하기 위해, 처리 완료한 event_id를 기록해둔다.

```sql
CREATE TABLE event_handled (
    event_id VARCHAR(255) PRIMARY KEY,
    handled_at DATETIME NOT NULL
);
```

**Consumer 처리 흐름**:

```
이벤트 수신 (event_id = "abc-123")
│
├─ event_handled에 "abc-123" 있는가?
│   │
│   ├─ YES → 이미 처리함, skip
│   │
│   └─ NO
│        │
│     BEGIN TX
│       ① INSERT INTO event_handled (event_id, handled_at)
│       ② 비즈니스 로직 실행 (product_metrics upsert 등)
│     COMMIT
│        │
│     manual Ack
```

핵심: **①과 ②가 같은 트랜잭션**이다. 비즈니스 로직이 실패하면 handled 기록도 롤백되므로, "처리 안 했는데 처리했다고 기록되는" 상황이 발생하지 않는다.

---

### 분리하는 이유

#### 1. 트랜잭션 경계가 다르다

가장 중요한 이유다.

`event_handled`는 **비즈니스 로직과 같은 트랜잭션**에 묶여야 한다. 처리 마킹과 비즈니스 로직의 원자성이 보장되어야 멱등성이 성립하기 때문이다. 따라서 이 테이블은 **가볍고 빨라야** 한다.

반면 `event_log`는 감사/디버깅 목적이라 트랜잭션 밖에서 비동기로 써도 된다. 오히려 로그 INSERT 실패 때문에 비즈니스 트랜잭션이 롤백되면 안 된다.

#### 2. 쿼리 패턴이 완전히 다르다

| | event_handled | event_log |
|---|---|---|
| **쿼리** | `SELECT 1 WHERE event_id = ?` (PK 조회) | 기간별 검색, 상태별 필터링 등 |
| **빈도** | 이벤트마다 매번 (hot path) | 장애 발생 시 또는 모니터링 |
| **데이터** | event_id (PK) + handled_at 정도 | payload, status, error, retry_count 등 |

한 테이블에 합치면 payload 같은 큰 컬럼이 포함되면서 **PK 인덱스 조회 성능이 불필요하게 떨어진다**. handled 체크는 Consumer의 hot path에 있으므로 가능한 한 가벼워야 한다.

#### 3. 데이터 생명주기가 다르다

- **event_handled**: 멱등 보장 기간만 유지하면 됨 (예: 7일). 같은 이벤트가 7일 뒤에 다시 올 가능성은 없으므로 이후 삭제 가능
- **event_log**: 감사/장애 추적 목적으로 장기 보관이 필요할 수 있음

한 테이블이면 오래된 handled 레코드를 정리하고 싶어도 로그 때문에 못 지우거나, 로그를 아카이빙하면 멱등 체크가 깨지는 상황이 생긴다.

---

### 정리

> `event_handled` = **멱등 게이트(gate)**. "이거 처리한 적 있어?"에 최소 비용으로 답하는 테이블
> `event_log` = **감사 기록(audit)**. "뭐가 어떻게 됐어?"에 상세하게 답하는 테이블
>
> **역할이 다르면 최적화 방향도 다르다.** 그래서 분리한다.
