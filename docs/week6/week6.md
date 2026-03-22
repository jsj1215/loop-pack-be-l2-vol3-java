
# 개념

## HTTP 클라이언트: RestTemplate vs OpenFeign

외부 시스템(PG)을 호출하려면 HTTP 클라이언트가 필요하다. Spring 생태계에서 대표적인 선택지는 **RestTemplate**과 **OpenFeign**이다.

### RestTemplate

Spring 3.0부터 제공된 동기 HTTP 클라이언트로, 가장 오래되고 익숙한 방식이다.

```java
@Component
public class PgRestTemplateClient {

    private final RestTemplate restTemplate;

    public PgRestTemplateClient(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(1000))
                .setReadTimeout(Duration.ofMillis(3000))
                .rootUri("http://localhost:8082")
                .build();
    }

    public PgPaymentResponse requestPayment(String memberId, PgPaymentRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", memberId);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<PgPaymentRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<PgPaymentResponse> response = restTemplate.exchange(
                "/api/v1/payments",
                HttpMethod.POST,
                entity,
                PgPaymentResponse.class
        );
        return response.getBody();
    }
}
```

**특징**

| 항목 | 설명 |
|------|------|
| 구현 방식 | 직접 HTTP 요청 코드를 작성 (URL, 헤더, 바디, 응답 파싱) |
| 타임아웃 설정 | `RestTemplateBuilder`에서 `setConnectTimeout`, `setReadTimeout` |
| 에러 처리 | `RestClientException` 계열, `HttpStatusCodeException`으로 상태 코드별 분기 |
| 테스트 | `MockRestServiceServer`로 목 서버 구성 가능 |
| 상태 | Spring 5.0부터 **maintenance mode** (신규 기능 추가 없음), `WebClient` 사용 권장 |

**장점**: 별도 의존성 없이 Spring만으로 사용 가능, 단순한 호출에 직관적
**단점**: 보일러플레이트 코드가 많음 (헤더 설정, HttpEntity 생성, 응답 파싱), URL/HTTP 메서드가 코드에 분산됨

---

### OpenFeign (Spring Cloud OpenFeign)

Netflix가 만든 **선언적(Declarative) HTTP 클라이언트**로, 인터페이스와 어노테이션만으로 HTTP 호출을 정의한다.

```java
@FeignClient(name = "pgClient", url = "${pg.base-url}", configuration = PgFeignConfig.class)
public interface PgFeignClient {

    @PostMapping(value = "/api/v1/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    PgPaymentResponse requestPayment(
            @RequestHeader("X-USER-ID") String memberId,
            @RequestBody PgPaymentRequest request);

    @GetMapping("/api/v1/payments")
    PgPaymentStatusResponse getPaymentStatus(
            @RequestHeader("X-USER-ID") String memberId,
            @RequestParam("orderId") String orderId);
}
```

```java
// 타임아웃 및 재시도 설정
public class PgFeignConfig {

    @Bean
    public Request.Options feignOptions(
            @Value("${pg.timeout.connect:1000}") int connectTimeout,
            @Value("${pg.timeout.read:3000}") int readTimeout) {
        return new Request.Options(
                connectTimeout, TimeUnit.MILLISECONDS,
                readTimeout, TimeUnit.MILLISECONDS,
                true);
    }

    @Bean
    public Retryer feignRetryer() {
        return Retryer.NEVER_RETRY;  // 재시도는 수동 또는 Resilience4j에 위임
    }
}
```

**특징**

| 항목 | 설명 |
|------|------|
| 구현 방식 | 인터페이스 + 어노테이션으로 선언, 구현체는 프록시가 자동 생성 |
| 타임아웃 설정 | `Request.Options` Bean 또는 `application.yml`에서 설정 |
| 에러 처리 | `FeignException` 계열, `ErrorDecoder`로 커스텀 가능 |
| Retry | 내장 `Retryer` 제공 (비활성화 후 Resilience4j에 위임 가능) |
| Spring MVC 호환 | `@GetMapping`, `@PostMapping`, `@RequestBody` 등 동일한 어노테이션 사용 |

**장점**: 보일러플레이트 최소화, Controller와 동일한 어노테이션으로 직관적, Spring Cloud 생태계 통합
**단점**: Spring Cloud 의존성 필요, 내부 동작이 프록시 기반이라 디버깅 시 한 단계 더 들어가야 함

---

### 비교 요약

| 비교 항목 | RestTemplate | OpenFeign |
|-----------|-------------|-----------|
| **코드량** | 많음 (HttpEntity, 헤더, URL 직접 구성) | 적음 (인터페이스 선언만) |
| **가독성** | HTTP 호출 로직이 비즈니스 로직과 섞임 | API 명세가 인터페이스에 명확히 분리됨 |
| **타임아웃** | `RestTemplateBuilder`로 설정 | `Request.Options` 또는 yml 설정 |
| **Retry** | 직접 구현 필요 | 내장 `Retryer` + Resilience4j 연동 |
| **에러 핸들링** | `try-catch`로 `RestClientException` 처리 | `ErrorDecoder`로 예외 변환, `FeignException`으로 상태코드 접근 |
| **테스트** | `MockRestServiceServer` | `@MockBean`으로 인터페이스 목킹 (DIP 자연스러움) |
| **유지보수 상태** | Maintenance mode (Spring 5.0~) | 활발히 유지보수 중 |

---

### 이 프로젝트에서 Feign을 선택한 이유

1. **선언적 API 정의로 관심사 분리**
   - PG API 스펙(URL, HTTP 메서드, 헤더)이 인터페이스에 명세로 남아, Infrastructure 레이어의 책임이 명확해진다.
   - RestTemplate이면 `requestPayment()` 메서드 안에 URL 조립, 헤더 설정, 응답 파싱이 모두 섞인다.

2. **Resilience 패턴과의 조합이 자연스러움**
   - Feign의 `Retryer.NEVER_RETRY`로 내장 재시도를 비활성화하고, 수동 Retry/CircuitBreaker 또는 Resilience4j에 제어를 위임할 수 있다.
   - `FeignException`의 `status()` 메서드로 HTTP 상태 코드에 따른 재시도 여부를 판단하기 편리하다 (5xx → 재시도, 4xx → 즉시 실패).
   - `RetryableException`으로 연결 실패와 읽기 타임아웃을 구분할 수 있어, 수동 Resilience 구현에 유리하다.

3. **테스트 용이성 (DIP)**
   - Feign Client는 인터페이스이므로, 테스트에서 `@MockBean`으로 바로 목킹 가능하다.
   - RestTemplate은 구체 클래스라 `MockRestServiceServer`를 별도로 구성해야 한다.
   - 이 프로젝트의 레이어드 아키텍처에서 DIP(의존성 역전)와 자연스럽게 맞아떨어진다.

4. **Spring Cloud 생태계 활용**
   - 프로젝트가 이미 Spring Cloud(`2024.0.1`)를 사용하고 있어, OpenFeign 도입에 추가 비용이 없다.
   - 향후 서비스 디스커버리(Eureka), 로드밸런싱(Spring Cloud LoadBalancer) 등 확장에도 대응 가능하다.

---

# 📝 Round 6 Quests

---

## 💻 Implementation Quest

> 외부 시스템(PG) 장애 및 지연에 대응하는 Resilience 설계를 학습하고 적용해봅니다.
`pg-simulator` 모듈을 활용하여 다양한 비동기 시스템과의 연동 및 실패 시나리오를 구현, 점검합니다.
>

<aside>
🎯

**Must-Have (이번 주에 무조건 가져가야 좋을 것-**무조건 ****하세요**)**

- Fallback
- Timeout
- CircuitBreaker

**Nice-To-Have (부가적으로 가져가면 좋을 것-**시간이 ****허락하면 ****꼭 ****해보세요**)**

- Retryer
</aside>

### 🤖 나의 시니어 파트너

- **외부 시스템과 연동되는 기능 설계를 분석**하고, **개발자와의 질의응답**을 통해 구조를 명확히 하며, **상태 불일치·트랜잭션 경계·장애 시나리오 관점**에서 리스크를 드러낼 수 있는 Skills 를 작성해봅니다.

  작성 예시 ( **`~/.claude/skills/anylize-external-integration/SKILL.md`** )

    ```markdown
    ---
    name: analyze-external-integration
    description:
      외부 시스템(결제, 재고 시스템, 메시징, 서드파티 API 등)과 연동되는 기능의 설계를 분석한다.
      트랜잭션 경계, 상태 일관성, 실패 시나리오, 재시도 및 중복 실행 가능성을 중심으로 리스크를 드러낸다.
      설계를 대신 생성하지 않으며, 이미 작성된 설계를 검증하고 개선 선택지를 제시하는 데 사용한다.
      외부 시스템 호출이 포함된 기능 구현 전/후 설계 리뷰 목적으로 사용한다.
    ---
    
    외부 시스템 연동 설계를 분석할 때 반드시 다음 흐름을 따른다.
    
    ### 1️⃣ 기능이 아니라 "불확실성" 관점으로 재해석한다
    - 단순 호출 순서를 요약하지 않는다.
    - 외부 시스템은 항상 다음을 만족한다고 가정한다:
      - 지연될 수 있다
      - 실패할 수 있다
      - 중복 실행될 수 있다
      - 성공했지만 응답이 유실될 수 있다
    - 현재 설계가 이러한 불확실성을 어떻게 다루는지 설명한다.
    
    ---
    
    ### 2️⃣ 트랜잭션 경계를 검증한다
    - 외부 호출이 트랜잭션 내부에 존재하는지 확인한다.
    - 외부 시스템과 내부 DB 상태가 하나의 트랜잭션처럼 다뤄지고 있는지 분석한다.
    - 다음 질문을 반드시 포함한다:
      - 외부 호출 실패 시 내부 상태는 어떻게 되는가?
      - 내부 커밋 이후 외부 호출 실패 시 복구 가능한가?
      - 외부 성공 후 내부 실패 시 상태는 어떻게 정합성을 유지하는가?
    
    ---
    
    ### 3️⃣ 상태 기반으로 구조를 다시 본다
    - 호출 흐름이 아니라 상태 전이를 중심으로 설명한다.
    - 내부 도메인 상태와 외부 시스템 상태를 분리해서 정리한다.
    - 두 상태가 어긋날 수 있는 지점을 명시한다.
    
    ---
    
    ### 4️⃣ 중복 요청 및 재시도 가능성을 분석한다
    - 네트워크 재시도 상황을 가정한다.
    - 동일 요청이 두 번 실행될 경우 문제를 설명한다.
    - 멱등성(Idempotency) 고려 여부를 확인한다.
    
    ---
    
    ### 5️⃣ 장애 시나리오를 최소 3가지 이상 생성한다
    - 정상 흐름보다 실패 흐름을 우선한다.
    - 각 장애 상황에서:
      - 데이터 정합성
      - 상태 불일치
      - 복구 가능성
      을 분석한다.
    
    ---
    
    ### 6️⃣ 해결책은 정답처럼 제시하지 않는다
    - 현재 구조의 장점과 리스크를 분리한다.
    - 대안 구조가 있다면 선택지 형태로 제시한다.
      예:
      - 동기 호출 유지
      - 상태 기반 단계 분리
      - 비동기 이벤트 전환
    - 각 선택지의 복잡도와 운영 부담을 함께 설명한다.
    
    ---
    
    ### 7️⃣ 톤 & 스타일 가이드
    - 코드 레벨 수정안을 직접 제시하지 않는다.
    - 설계를 비판하지 말고 리스크를 드러내는 리뷰 톤을 유지한다.
    - 외부 시스템은 항상 신뢰할 수 없다는 전제를 유지한다.
    - 구현보다 책임, 경계, 상태 일관성을 중심으로 분석한다.
    
    ```

# 과제
외부 시스템과 연동되는 기능 설계를 분석하고,
개발자와의 질의응답을 통해 구조를 명확히 하며,
상태 불일치·트랜잭션 경계·장애 시나리오 관점에서 리스크를 드러낼 수 있는 Skills 를 작성해봅니다.

외부 시스템과 연동되는 기능 설계를 분석하고,
개발자와의 질의응답을 통해 구조를 명확히 하며,
상태 불일치·트랜잭션 경계·장애 시나리오 관점에서 리스크를 드러낼 수 있는 Skills 를 작성해봅니다.

### **📦 결제 기능 추가**

- 주문에 대한 결제 기능을 추가합니다.
- 주문항목과 결제 수단을 입력받아, 외부 결제 시스템과 연동 후 주문에 대한 결제 처리를 하는 API 를 작성합니다.

```java
## commerce-api
POST {{commerce-api}}/api/v1/payments
X-Loopers-LoginId: 
X-Loopers-LoginPw:
Content-Type: application/json

{
  "orderId": "1351039135",
  "cardType": "SAMSUNG",
  "cardNo": "1234-5678-9814-1451",
}
```

### 💰 **결제 시스템 연동**

```java
## PG-Simulator
### 결제 요청
POST {{pg-simulator}}/api/v1/payments
X-USER-ID: 
Content-Type: application/json

{
  "orderId": "1351039135",
  "cardType": "SAMSUNG",
  "cardNo": "1234-5678-9814-1451",
  "amount" : "5000",
  "callbackUrl": "http://localhost:8080/api/v1/examples/callback"
}

### 결제 정보 확인
GET {{pg-simulator}}/api/v1/payments/20250816:TR:9577c5
X-USER-ID: 

### 주문에 엮인 결제 정보 조회
GET {{pg-simulator}}/api/v1/payments?orderId=1351039135
X-USER-ID: 
```

- PG 기반 카드 결제 기능을 추가합니다.
- PG 시스템은 로컬에서 실행가능한 `pg-simulator` 모듈이 제공됩니다. ( 별도 SpringBootApp )
- PG 시스템은 **비동기 결제** 기능을 제공합니다.

> *비동기 결제란, 요청과 실제 처리가 분리되어 있음을 의미합니다.*
**요청 성공 확률 : 60%
요청 지연 :** 100ms ~ 500ms
**처리 지연** : 1s ~ 5s
**처리 결과**
* 성공 : 70%
* 한도 초과 : 20%
* 잘못된 카드 : 10%
>

- java

[GitHub - Loopers-dev-lab/loopback-be-l2-java-additionals: 루프팩 BE L2 수강생들을 위한 추가사항](https://github.com/Loopers-dev-lab/loopback-be-l2-java-additionals)

- kotlin

[6주차 과제 / pg-simulator 모듈 추가 by hubtwork · Pull Request #1 · Loopers-dev-lab/loopback-be-l2-kotlin-additionals](https://github.com/Loopers-dev-lab/loopback-be-l2-kotlin-additionals/pull/1)

### 📋 과제 정보

- 외부 시스템에 대해 적절한 타임아웃 기준에 대해 고려해보고, 적용합니다.
- 외부 시스템의 응답 지연 및 실패에 대해서 대처할 방법에 대해 고민해 봅니다.
- PG 결제 결과를 적절하게 시스템과 연동하고 이를 기반으로 주문 상태를 안전하게 처리할 방법에 대해 고민해 봅니다.
- 서킷브레이커를 통해 외부 시스템의 지연, 실패에 대해 대응하여 서비스 전체가 무너지지 않도록 보호합니다.

---

## ✅ Checklist

### **⚡ PG 연동 대응**

- [x]  PG 연동 API는 FeignClient 로 외부 시스템을 호출한다.
- [x]  응답 지연에 대해 타임아웃을 설정하고, 실패 시 적절한 예외 처리 로직을 구현한다.
- [x]  결제 요청에 대한 실패 응답에 대해 적절한 시스템 연동을 진행한다.
- [x]  콜백 방식 + **결제 상태 확인 API**를 활용해 적절하게 시스템과 결제정보를 연동한다.

### **🛡 Resilience 설계**

- [x]  서킷 브레이커 혹은 재시도 정책을 적용하여 장애 확산을 방지한다.
- [x]  외부 시스템 장애 시에도 내부 시스템은 **정상적으로 응답**하도록 보호한다.
- [x]  콜백이 오지 않더라도, 일정 주기 혹은 수동 API 호출로 상태를 복구할 수 있다.
- [x]  PG 에 대한 요청이 타임아웃에 의해 실패되더라도 해당 결제건에 대한 정보를 확인하여 정상적으로 시스템에 반영한다.

---

## 🔧 구현 결정사항

### 1. V1/V2 콜백 URL 분리

**문제**: `pg.callback-url`이 `/api/v1/payments/callback`로 하드코딩되어, V2를 통해 결제해도 PG 콜백이 V1 엔드포인트로 들어옴. V1을 제거하면 콜백이 404.

**해결**: `pg.callback-url.v1` / `pg.callback-url.v2`로 분리하여 각 버전이 자신의 콜백 경로를 PG에 전달하도록 변경.

- `ManualPgApiClient` → `${pg.callback-url.v1}`
- `PgApiClient` → `${pg.callback-url.v2}`

### 2. orderId 유니크 제약 + 결제 재시도 시 기존 Payment 재사용

**문제**: 같은 orderId로 결제 실패 후 재시도하면 Payment가 여러 개 생성되어, `findByOrderId`가 어떤 Payment를 반환할지 보장할 수 없음. 콜백이 잘못된 Payment에 적용될 위험.

**해결**:
- `Payment.orderId`에 `unique = true` 제약 추가 — 한 주문에 Payment는 항상 1개
- `Payment.resetForRetry()` 메서드 추가 — FAILED 상태의 기존 Payment를 REQUESTED로 리셋하고 카드 정보 갱신
- `PaymentService.initiatePayment()`에서 기존 FAILED Payment가 있으면 새로 insert하지 않고 재사용
- `findByOrderId`의 결과가 항상 유일하므로 콜백/verify 시 정합성 보장

### 3. 콜백 PG 트랜잭션 ID 검증

**문제**: 콜백 엔드포인트(`/callback`)에 인증이 없어 외부에서 가짜 콜백을 보내 결제 상태를 위조할 수 있음. PG 서버가 호출하는 특성상 로그인 인증은 불가.

**해결**: `PaymentService.syncPaymentResult()`에서 PG 트랜잭션 ID 검증 로직 추가.

- Payment에 저장된 `pgTransactionId`와 콜백으로 들어온 값이 **불일치하면 예외** 발생
- Payment에 `pgTransactionId`가 없는 경우(UNKNOWN/REQUESTED)는 검증 통과 — PG가 요청을 받았지만 응답이 유실된 시나리오 대응
- 이를 통해 우리 시스템에서 PG로 보낸 적 없는 transactionId로는 상태 변경 불가


### 4. 콜백 orderId 파싱 방어

**문제**: 콜백의 `orderId`가 String인데 `Long.valueOf()`로 변환 시 숫자가 아니면 `NumberFormatException` → 500 에러. PG(외부 시스템) 데이터를 무검증 변환하는 것은 위험.

**해결**: 컨트롤러에 `parseOrderId()` 메서드 추가. `NumberFormatException` 발생 시 `CoreException(BAD_REQUEST)`로 변환하여 400 응답 반환.

### 5. REQUESTED 상태 복구 경로 추가

**문제**: `initiatePayment` 후 서버가 죽으면 Payment가 REQUESTED 상태로 영구히 남음. `verifyPayment()`는 PENDING/UNKNOWN만 대상이어서 REQUESTED는 복구 경로가 없음.

**해결**:
- `Payment.needsVerification()`에 REQUESTED 추가
- `PaymentService.markRequestedAsFailed()` 메서드 추가 — REQUESTED 상태만 필터링하여 FAILED로 전환
- `verifyPayment()`에서 PG 조회 결과가 없을 때 `markRequestedAsFailed()` 호출
- FAILED로 전환된 Payment는 `resetForRetry()`로 재결제 가능 (주문을 새로 넣을 필요 없음)

### 6. PG 결제 전용 스레드 풀 (Bulkhead)

**문제**: `CompletableFuture.supplyAsync()`가 기본 `ForkJoinPool.commonPool()`을 사용. PG 지연 시 공유 스레드풀이 고갈되어 결제 외 기능까지 영향.

**해결**: `PgApiClient`에 전용 `Executor`(`pg-executor`, 기본 10 스레드) 주입. `supplyAsync(task, pgExecutor)`로 호출하여 commonPool과 격리. 풀 크기는 `pg.executor.pool-size`로 설정 가능.

### 7. ManualCircuitBreaker 동시성 보장

**문제**: `AtomicInteger`/`AtomicReference` 개별 연산은 thread-safe하지만, `recordInWindow()`에서 여러 변수를 순차 조작하는 복합 연산이 원자적이지 않음. `boolean[]` 배열도 volatile이 아니어서 가시성 미보장.

**해결**: `Atomic*` 타입을 일반 필드로 변경하고, `isCallPermitted()`, `recordSuccess()`, `recordFailure()`, `getState()` 모두 `synchronized`로 선언. 정확성이 성능보다 중요.

### 8. PG 상태 조회 예외 방어 (V2)

**문제**: V2의 `PgApiClient.getPaymentStatus()`에 예외 처리가 없어, PG 지연/장애 시 verify API도 같이 터짐.

**해결**: try-catch 추가. 실패 시 null 반환하여 Facade에서 `markRequestedAsFailed()`로 이어지는 기존 흐름 활용.

### 9. PgPaymentResponse.isAccepted() 판단 보강

**문제**: `transactionId` 존재 여부만으로 접수 성공을 판단. PG가 에러 응답에 transactionId를 포함하면 성공으로 오판 가능.

**해결**: `isAccepted()`에 status가 FAILED/TIMEOUT이 아닌 조건 추가.

### 10. 결제 요청 입력 검증 강화

**문제**: `CreatePaymentRequest`의 `cardType`, `cardNo`가 null이나 빈 문자열이어도 검증 없이 DB에 저장됨. 프로젝트에서 `@Valid`를 사용하지 않으므로 도메인 레이어에서 검증해야 함.

**해결**: `Payment.create()`에 `cardType`/`cardNo` null/blank 검증 추가. 기존 `orderId`, `memberId`, `amount` 검증과 동일한 패턴으로 `CoreException(BAD_REQUEST)` 발생.

### 11. HTTP 테스트 파일 보완

**문제**: `payments-v1.http`에 PG 시뮬레이터 직접 호출만 있고, Commerce API의 결제 엔드포인트 호출이 없어 수동 테스트가 불편.

**해결**: V1/V2 결제 요청(`POST /api/v1/payments`, `POST /api/v2/payments`)과 결제 상태 확인(`GET /verify`) 호출을 추가. PG 시뮬레이터 직접 호출도 유지.

### 12. PaymentService 통합 테스트 추가

**문제**: Payment 관련 통합 테스트(`@SpringBootTest` + Testcontainers)가 없어 실제 DB 연동 검증이 부재. 기존 `OrderPaymentIntegrationTest`는 주문+결제 요청 흐름만 커버.

**해결**: `PaymentServiceIntegrationTest` 추가 — 실제 DB로 검증하는 9개 시나리오:
- 결제 생성 / FAILED 재시도(resetForRetry) / 중복 방지
- 콜백 동기화(SUCCESS/FAILED) / 멱등성 / 트랜잭션 ID 불일치 거부
- REQUESTED 상태 FAILED 복구 / PENDING 미변경 확인

### 13. Order 상태 전이 가드 추가

**문제**: `Order.markPaid()`, `markFailed()`, `cancel()`에 전이 조건 검증이 없어, 이미 PAID/CANCELLED된 주문도 상태가 변경될 수 있음. Payment는 `validateNotFinalized()`로 보호하고 있으나 Order는 미보호.

**해결**: `Order`에 `validateNotFinalized()` 추가. PAID와 CANCELLED를 최종 상태로 취급하여 상태 변경을 차단. FAILED는 재결제(`resetForRetry`) 후 `markPaid()`가 호출될 수 있으므로 최종 상태에서 제외.

### 14. ManualPgApiClient 불필요한 기본 생성자 제거

**문제**: `protected ManualPgApiClient()`가 모든 필드를 null로 초기화하여, Spring이 이 생성자를 사용할 경우 `NullPointerException` 위험.

**해결**: 해당 생성자 삭제. Spring은 `@Value` 파라미터가 있는 생성자 또는 테스트용 3-arg 생성자를 사용하므로 영향 없음.

### 15. getPaymentStatus() null 반환 → Optional 반환

**문제**: `PgApiClient`와 `ManualPgApiClient`의 `getPaymentStatus()`가 실패 시 null을 반환. 호출 측에서 `if (pgStatus != null)` 패턴으로 처리하고 있으나, 프로젝트의 null-safety 컨벤션(Optional 활용)에 맞지 않음.

**해결**:
- 양쪽 클라이언트: `Optional<PgPaymentStatusResponse>` 반환으로 변경
- 양쪽 Facade: `Optional.filter(isCompleted).ifPresentOrElse()` 패턴으로 리팩토링
- null 체크 분기(`if/else`)가 Optional의 선언적 API로 대체되어 의도가 명확해짐

### 16. Resilience4j retry-exceptions 4xx 재시도 범위 수정

**문제**: `application.yml`의 retry-exceptions에 `feign.FeignException` 전체를 지정하고 `BadRequest`/`NotFound`만 ignore하고 있어, 401·403·405 등 다른 4xx 에러도 재시도 대상이 됨. V1(Manual)은 `status >= 500`만 재시도하므로 V1/V2 간 정책 불일치.

**해결**:
- `feign.FeignException` 전체 대신 5xx 서버 에러 클래스만 명시적으로 지정: `InternalServerError`, `BadGateway`, `ServiceUnavailable`, `GatewayTimeout`
- `ignore-exceptions` 섹션 제거 (더 이상 필요 없음)
- V1/V2 모두 5xx만 재시도하는 동일한 정책으로 일관성 확보

### 17. updatePaymentStatus 3중 중복 해소

**문제**: `PaymentFacade`, `ManualPaymentFacade`, `OrderPaymentFacade` 세 곳에 PG 응답 해석 → 상태 업데이트 로직(`updatePaymentStatus`)이 복사되어 있음. 하나를 수정하면 나머지를 놓칠 위험.

**해결**:
- `PaymentService.updatePaymentStatus(Long paymentId, PaymentGatewayResponse response)` 메서드 추가
- `PaymentGatewayResponse`는 Domain 레이어 객체이므로 DIP 위반 없음
- 세 Facade의 private `updatePaymentStatus()` 제거, `paymentService.updatePaymentStatus()` 호출로 교체

### 18. PaymentFacade / ManualPaymentFacade 공통 추상 클래스 추출

**문제**: 두 Facade의 `processPayment()`, `handleCallback()`, `verifyPayment()`, `applyOrderStateChange()` 메서드가 완전히 동일. 차이는 `@Qualifier`로 주입되는 `PaymentGateway` 구현체뿐. 하나에 변경이 생기면 다른 쪽을 놓칠 위험.

**해결**:
- `AbstractPaymentFacade` 추상 클래스 추출 — 공통 로직을 한 곳에서 관리
- `PaymentFacade`는 `@Qualifier("resilience4jPaymentGateway")`를 주입하는 생성자만 보유
- `ManualPaymentFacade`는 `@Qualifier("manualPaymentGateway")`를 주입하는 생성자만 보유
- 로직 변경 시 `AbstractPaymentFacade`만 수정하면 V1/V2 모두 반영

### 19. OrderV1ApiE2ETest PG Mock 누락 수정

**문제**: `OrderV1ApiE2ETest`에 `@MockitoBean PgFeignClient`가 없어, PG 시뮬레이터가 미기동 시 실제 PG 연결을 시도함. Resilience4j fallback으로 테스트가 통과할 수 있지만, 의도치 않은 경로로 통과하는 것이므로 불안정.

**해결**:
- `@MockitoBean PgFeignClient` 추가 + `@BeforeEach`에서 PG 접수 성공 응답 stub 설정
- 주석 스타일 `// arrange`/`// act`/`// assert` → `// given`/`// when`/`// then`으로 통일

### 20. PaymentFacadeTest 반환값 검증 추가

**문제**: `processesPaymentWithSeparateTransactions()` 테스트에서 `result` 변수를 생성하지만 `verify()`로 호출 여부만 확인하고, 반환된 `PaymentInfo`의 필드를 검증하지 않음.

**해결**: `assertAll`로 `result.status()` (PENDING), `result.orderId()` 검증 추가.

### 21. Resilience4j AOP 통합 테스트 추가

**문제**: `PgApiClient`의 `@TimeLimiter`, `@CircuitBreaker`, `@Retry` 어노테이션이 Spring AOP 프록시를 통해 실제 동작하는지 검증하는 `@SpringBootTest` 수준 테스트가 없음. 기존 단위 테스트는 리플렉션으로 fallback 로직만 검증.

**해결**: `PgApiClientIntegrationTest` 추가 — `@SpringBootTest`에서 실제 AOP 프록시 동작 검증:
- 정상 응답 반환
- `RetryableException` 발생 시 fallback FAILED 응답
- 4xx 에러 재시도 안 함 (1회만 호출) 확인
- 일반 예외 시 fallback FAILED 응답

### 22. PgPaymentStatusResponse.isCompleted() 미사용 메서드 제거

**문제**: Infrastructure 레이어의 `PgPaymentStatusResponse.isCompleted()`와 Domain 레이어의 `PaymentGatewayStatusResponse.isCompleted()`에 동일한 로직이 중복. Gateway에서 인프라 응답을 도메인 응답으로 변환하므로 Infrastructure 쪽은 실제 호출되지 않음.

**해결**: `PgPaymentStatusResponse.isCompleted()` 메서드 제거. Domain 레이어의 `PaymentGatewayStatusResponse.isCompleted()`만 사용.

---

### 23. Payment 동시 상태 변경 Race Condition 방지 (`@Version` 낙관적 락)

**문제**: Payment 엔티티에 동시성 제어가 없어, PG 응답 처리(`updatePaymentStatus`)와 PG 콜백(`syncPaymentResult`)이 거의 동시에 도착할 경우 Lost Update가 발생할 수 있음. `isFinalized()` 가드는 같은 트랜잭션 내 읽기 시점에서만 유효하므로, 두 스레드가 동시에 `isFinalized()=false`를 읽고 각각 상태 변경을 시도하면 나중에 커밋하는 쪽이 앞선 변경을 덮어씀.

**시나리오**: PG 콜백이 SUCCESS로 Payment를 확정한 직후, 뒤늦은 `updatePaymentStatus()`가 PENDING으로 덮어쓰면 Order가 PAID로 전이되지 못하는 상태 불일치 발생.

**해결**: Payment 엔티티에 `@Version` 필드를 추가하여 JPA 낙관적 락을 적용. 동시 수정 시 나중에 커밋하는 트랜잭션이 `OptimisticLockException`을 받아 실패하고, 이후 재시도/Verify 시 `isFinalized()=true`로 SKIPPED 처리됨.

## ✍️ (Optional) Technical Writing Quest

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

- PG 응답이 느려서 서킷브레이커가 열렸다…?
- 응답이 안 와서 실패 처리했는데, PG에선 결제가 됐다고…?
- 주문 상태는 Pending 인데, 사용자는 결제 안내를 받았다.
- PG 장애 하나로 주문 전체가 멈춰버렸다.
- 결제가 실패하면 주문을 무조건 롤백해야 할까?
- 재시도 횟수는 몇 번이 적절했을까?
- 폴백 처리를 어떻게 했지?


---
# 과제
## 🧭 루프팩 BE L2 - Round 6

> 외부 시스템(PG) 연동 과정에서 발생할 수 있는 장애와 지연에 대응하기 위해 **타임아웃, 재시도, 서킷 브레이커와 폴백 처리** 등 다양한 회복 전략을 적용합니다.
>

<aside>
🎯

**Summary**

</aside>

- 외부 시스템(PG) 연동 시 발생할 수 있는 지연, 장애, 실패에 대응합니다.
- 타임아웃, 재시도, 서킷 브레이커, 폴백 전략을 통해 회복력 있는 구조를 학습합니다.
- 장애가 전체 시스템에 전파되지 않도록 보호 설계를 실습합니다.

<aside>
📌

**Keywords**

</aside>

- Circuit Breaker
- Timeout & Retry
- Fallback 처리
- 외부 시스템 연동

<aside>
🧠

**Learning**

</aside>

## 🚧 실무에서 겪는 장애 전파 문제

> 💬 외부 시스템과의 연동은 대부분의 실무 서비스에서 필수적이며 특히 이는 단순히 서버간 요청 뿐만 아니라 DB, Redis 와 같은 외부 인프라도 마찬가지예요.
>
>
> 예를 들면 PG 서버가 일시적으로 느려지거나, 아예 응답을 주지 않는 상황이 종종 발생합니다. 이때 클라이언트가 끝까지 기다리면, 해당 요청은 스레드를 점유한 채 대기 상태로 남게 됩니다.
> 이런 요청이 수십~수백 개 쌓이면, 애플리케이션 전체가 마비될 수 있습니다.
>

---

## ⏱ Timeout

> 외부 시스템의 응답 지연을 제어하고, 전체 시스템의 자원을 보호하기 위한 가장 기본적인 전략입니다.
>
- **요청이 일정 시간 내에 응답하지 않으면 실패로 간주하고 종료**합니다.
- 타임아웃이 없다면, 외부 시스템 하나의 장애가 전체 시스템으로 전파됩니다.
- 대부분의 실무 장애는 **실패보다는 지연**에서 시작됩니다.

### 🚧 실무에서 겪는 문제

외부 시스템(PG 등)이 응답을 지연시키거나 멈추는 경우, 요청을 끝까지 기다리면 스레드나 커넥션이 점유된 채로 대기하게 됩니다.

이런 요청이 누적되면 전체 시스템이 느려지거나 멈추게 되며, 장애가 외부에서 시작됐더라도 결국 내부 시스템 전체로 확산됩니다.

### 📁 실전 설정 예시

### Http 요청 (Feign Client)

```java
@Configuration
public class FeignClientTimeoutConfig {
    @Bean
    public Request.Options feignOptions() {
        return new Request.Options(1000, 3000); // 연결/응답 타임아웃 (ms)
    }
}

@FeignClient(
    name = "pgClient",
    url = "https://pg.example.com",
    configuration = FeignClientTimeoutConfig.class
)
public interface PgClient {
    @PostMapping("/pay")
    PaymentResponse requestPayment(@RequestBody PaymentRequest request);
}
```

### JPA (HikariCP)

```yaml
spring:
  datasource:
    hikari:
      connection-timeout: 3000       # 커넥션 풀에서 커넥션 얻는 최대 대기 시간
      validation-timeout: 2000       # 커넥션 유효성 검사 제한 시간
```

### Redis (Lettuce 기반)

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 3000                 # 명령 실행 제한 시간
```

### 💡 실무 TIPs

- **Feign**: connectTimeout과 readTimeout을 명확히 나눠 설정하세요.
- **JPA**: 커넥션 풀에서 대기 없이 바로 실패하도록 `connection-timeout`은 필수입니다.
- **Redis**: Lettuce의 `commandTimeout`을 걸지 않으면 무기한 대기할 수 있습니다.
- 보통 타임아웃은 2~5초 사이로 잡으며, 지연 허용 범위는 기능 특성과 요청 수에 따라 조절합니다.

---

## 🔁 Retry

> Retry는 일시적인 장애 상황에서 재시도를 통해 정상 응답을 받아내는 회복 전략입니다. 특히 네트워크 연결 실패, 서버 과부하 등 **일시적 실패(transient fault)** 에 매우 효과적입니다.
>
- 너무 잦은 재시도는 서버에 부하를 주거나 **DoS 공격처럼 동작할 수 있습니다.**
- 반드시 재시도 간 **대기 시간(backoff)** 을 설정해야 하며, **최대 시도 횟수 제한**도 중요합니다.
- 타임아웃과 조합해서 **최대 몇 초 안에 몇 번까지만** 이라는 제어가 필요합니다.
- 끝내 재시도 요청이 실패했을 경우, `fallback` 로직으로 이어질 수 있도록 하는 처리 또한 고려해야 합니다.

### 🚧 실무에서 겪는 문제

PG 서버가 일시적으로 503 에러를 반환하거나 네트워크에서 패킷 손실이 발생하는 경우, 실패한 요청을 즉시 종료하는 것보다는 **일정 횟수 재시도**만으로도 정상 처리가 가능한 경우가 많습니다.

하지만 별도 설정 없이 무작정 재요청을 반복하거나, 예외 상황을 고려하지 않은 채 재시도하면 오히려 시스템에 더 큰 부하를 유발할 수 있습니다.

### 🔨 Resilience4j Retry

> Resilience4j는 **Retry, CircuitBreaker, TimeLimiter 등** 여러 회복 전략을 조합할 수 있는 라이브러리이며 실무에서 가장 범용적으로 활용되고 있는 오픈소스 라이브러리입니다. 본 과정에서는 **Resilience4j 기반**으로 설명을 진행합니다.
>

[Getting Started](https://resilience4j.readme.io/docs/getting-started-3)

### 📁 실전 설정 예시 - Resilience4j with Spring Boot

### **Gradle 의존성 설정 (non-reactive stack)**

```
dependencies {
  implementation "io.github.resilience4j:resilience4j-spring-boot3"
  implementation "org.springframework.boot:spring-boot-starter-aop"
}
```

### application.yml 설정

```yaml
resilience4j:
  retry:
    instances:
      pgRetry:
        max-attempts: 3
        wait-duration: 1s
        retry-exceptions:
          - feign.RetryableException
        fail-after-max-attempts: true
```

### Retry 적용

```java
@Retry(name = "pgRetry", fallbackMethod = "fallback")
public PaymentResponse requestPayment(PaymentRequest request) {
    return pgClient.requestPayment(request);
}

public PaymentResponse fallback(PaymentRequest request, Throwable t) {
    return new PaymentResponse("결제 대기 상태", false);
}
```

### 💡 실무 TIPs

- `fail-after-max-attempts`를 true로 설정하면, 재시도 실패 시 바로 fallback을 실행할 수 있습니다.
- 재시도할 예외는 반드시 명시해야 합니다. (`RetryableException`, `SocketTimeoutException` 등)
- retry 간 간격은 **wait-duration** 으로 제어하며, **random backoff** 또는 **exponential backoff** 전략도 지원됩니다.

---

## 🚦 Circuit Breaker

> Circuit Breaker는 외부 시스템이 반복적으로 실패하면 **일시적으로 회로를 열어 호출을 차단**하는 전략입니다.
>
>
> 마치 누전 차단기처럼, 계속해서 실패하는 요청을 끊고 전체 시스템을 보호합니다.
>

다음 상태들을 이해하고 있어야 해요.

- **Closed** – 정상 상태, 호출 가능
- **Open** – 실패율이 기준치를 넘으면 차단
- **Half-Open** – 일정 시간 후 일부만 호출 시도 → 성공 시 Close, 실패 시 다시 Open

### 🚧 실무에서 겪는 문제들

외부 시스템(PG 등)이 완전히 죽었을 때, 모든 요청이 계속해서 실패하며 애플리케이션 로그가 뒤덮이고, 불필요한 재시도와 에러가 대량으로 발생합니다.

결과적으로 **CPU 사용률이 급등**하거나, **전체 서비스의 반응 속도가 저하**되는 현상이 발생합니다.

이때 필요한 것이 **"더 이상 호출하지 않도록" 차단하는 장치**, 즉 Circuit Breaker입니다.

### 📁 실전 설정 예시 - Resilience4j with Spring Boot

### application.yml 설정

```yaml
resilience4j:
  circuitbreaker:
    instances:
      pgCircuit:
        sliding-window-size: 10
        failure-rate-threshold: 50       # 실패율이 50% 넘으면 Open
        wait-duration-in-open-state: 10s # Open 상태 유지 시간
        permitted-number-of-calls-in-half-open-state: 2
        slow-call-duration-threshold: 2s
        slow-call-rate-threshold: 50
```

### CircuitBreaker 적용

```java
@CircuitBreaker(name = "pgCircuit", fallbackMethod = "fallback")
public PaymentResponse requestPayment(PaymentRequest request) {
    return pgClient.requestPayment(request);
}

public PaymentResponse fallback(PaymentRequest request, Throwable t) {
    return new PaymentResponse("결제 대기 상태", false);
}
```

### 💡 실무 TIPs

- Circuit Breaker는 **정상/실패 여부만 판단**하는 게 아니라, **느린 응답도 실패로 간주**할 수 있습니다.

  이를 위해 `slow-call-duration-threshold` 와 `slow-call-rate-threshold` 설정이 매우 중요합니다.

- Half-Open 상태에서 몇 개의 요청만 통과시키고, 그 결과에 따라 다시 회로를 닫거나 유지합니다.
- Circuit Breaker는 **Retry와 함께 사용**해야 하면 더 강력하게 활용할 수 있습니다.
    - Retry가 실패를 일정 횟수 누적
    - Circuit Breaker가 **이제는 아예 보내지 말자** 를 결정합니다.
- `fallbackMethod` 를 활용해 **현재 시스템에서 가능한 대응**을 정의해두는 것이 UX와 장애 확산 방지 측면에서 중요합니다.

<aside>
📚

**References**

</aside>

| 구분 | 링크 |
| --- | --- |
| 🔍 Resilience4j | [Resilience4j Introduction](https://resilience4j.readme.io/docs/getting-started) |
| 📖 Resilience4j with SpringBoot | [Baeldung - Resilience4j with SpringBoot](https://www.baeldung.com/spring-boot-resilience4j) |
| ⚙ FeignClient Timeout | [Baeldung - Custom Feign Client Timeouts](https://www.baeldung.com/feign-timeout) |
| 🧰 Spring REST Client : Feign | [spring-cloud-feign](https://cloud.spring.io/spring-cloud-netflix/multi/multi_spring-cloud-feign.html) |
| 🧠 Fallback Pattern | [MSA - Fallback Pattern](https://badia-kharroubi.gitbooks.io/microservices-architecture/content/patterns/communication-patterns/fallback-pattern.html) |

<aside>
🌟

**Next Week Preview**

</aside>

> **주문을 처리하면서 모든 걸 동시에 끝내야 할까요?**
>
>
>
> 지금 우리 시스템은 포인트 차감, 재고 차감, 쿠폰 사용, 결제 처리까지 모든 과정을 한 번에 처리하고 있습니다. 이처럼 **복잡한 도메인 흐름을 하나의 트랜잭션에 모두 담으면** 트랜잭션은 무거워지고, 도메인 간 결합도도 높아집니다.
> 다음 주에는 이러한 유스케이스를 **애플리케이션 이벤트 기반으로 분리**하고, 시스템을 더 **느슨하고 확장 가능하게 설계하는 방법**을 학습합니다.
>

---
# 결제 연동 데이터
{pg-simulator} = http://localhost:8082

### 결제 요청
POST {{pg-simulator}}/api/v1/payments
X-USER-ID: 135135
Content-Type: application/json

{
"orderId": "1351039135",
"cardType": "SAMSUNG",
"cardNo": "1234-5678-9814-1451",
"amount" : "5000",
"callbackUrl": "http://localhost:8080/api/v1/examples/callback"
}

### 결제 정보 확인
GET {{pg-simulator}}/api/v1/payments/20250816:TR:9577c5
X-USER-ID: 135135

### 주문에 엮인 결제 정보 조회
GET {{pg-simulator}}/api/v1/payments?orderId=1351039135
X-USER-ID: 135135

---

# 공부해야 할 내용 정리

> **학습 순서**: 과제 Must-Have → 과제에서 요구하는 연동 패턴 → Nice-To-Have → 실무 확장 순으로 정리
> **PG Simulator 특성**: 요청 성공률 60%, 지연 100~500ms, 비동기 결제(요청과 처리가 분리), 처리 지연 1~5초

---

## 1. Timeout (타임아웃) — Must-Have

**핵심**: 외부 시스템 응답을 무한정 기다리지 않고, 일정 시간 초과 시 실패로 간주하고 끊는 것
**이것 없이는 나머지 전략이 전부 의미 없음** — 모든 Resilience 설계의 전제 조건

### 왜 필요한가?
- 외부 시스템(PG 등)이 느려지면 요청 스레드가 대기 상태로 쌓임
- 수백 개 쌓이면 앱 전체가 마비 (장애 전파)
- 대부분의 실무 장애는 "실패"가 아니라 **"지연"**에서 시작됨

### 알아야 할 것
- 타임아웃은 **3가지로 분리**해서 설정해야 함
  - **Connection timeout**: TCP 연결 자체를 맺기까지의 최대 대기 시간 (보통 1~2초)
  - **Read timeout**: 연결 이후 응답 데이터를 받기까지의 최대 대기 시간 (보통 3~5초)
  - **전체 요청 timeout**: 요청~응답 전체 흐름의 제한 시간
- PG Simulator가 100~500ms 지연이므로 connection 1~2초, read 3~5초가 적절한 기준
- HTTP 클라이언트(Feign/RestTemplate), DB(HikariCP), Redis(Lettuce) 각각의 타임아웃 설정법

### 이 과제에서의 핵심 포인트
- PG 호출 시 타임아웃이 발생해도 **PG 쪽에서는 실제로 결제가 진행될 수 있음**
- 타임아웃 = "우리가 응답을 못 받은 것"이지 "PG가 처리를 안 한 것"이 아님
- → 이 차이가 뒤의 "비동기 확인" 전략이 필요한 이유

---

## 2. Circuit Breaker (서킷 브레이커) — Must-Have

**핵심**: 외부 시스템이 반복 실패하면 아예 호출을 차단해서 시스템을 보호하는 패턴 (누전 차단기)

### 왜 필요한가?
- PG 요청 성공률이 60%밖에 안 됨 — 연속 실패 시 불필요한 요청을 계속 보내면 우리 시스템만 손해
- 외부가 죽었을 때 빠르게 실패(fail-fast)하여 스레드/커넥션 자원을 보호
- CPU 사용률 급등, 전체 서비스 반응 속도 저하를 방지

### 3가지 상태 (반드시 이해)

| 상태 | 설명 |
|------|------|
| **Closed** | 정상 상태, 호출 허용 |
| **Open** | 실패율이 임계치 초과 → 호출 차단, **즉시 Fallback 실행** |
| **Half-Open** | 일정 시간 후 소량의 요청만 시도 → 성공이면 Closed, 실패면 다시 Open |

### 알아야 할 것
- `sliding-window-size`: 몇 개의 요청을 기준으로 실패율 계산할 것인지
- `failure-rate-threshold`: 실패율 몇 %에서 Open할 것인지 (예: 50%)
- `wait-duration-in-open-state`: Open 상태를 얼마나 유지할 것인지 (예: 10s)
- `permitted-number-of-calls-in-half-open-state`: Half-Open에서 몇 개의 요청을 시도할 것인지
- **느린 응답도 실패로 간주** 가능 (`slow-call-duration-threshold`, `slow-call-rate-threshold`)
- 서킷이 Open되면 → **Fallback**으로 연결됨 (다음 항목)

---

## 3. Fallback (폴백 처리) — Must-Have

**핵심**: 외부 시스템 호출이 실패했을 때 대안 응답을 반환하여 사용자 경험과 시스템을 보호하는 전략

### 왜 필요한가?
- Timeout, Circuit Breaker 모두 **최종적으로 "실패했을 때 뭘 할 것인가"**에 답해야 함
- 외부 장애가 내부 시스템 에러로 그대로 전파되면 안 됨

### 이 과제에서의 Fallback 전략
- PG가 비동기 결제이므로, **"결제 보류(PENDING)" 상태로 주문을 접수**하는 것이 가장 자연스러움
- 사용자에게는 "결제 처리 중"으로 안내 → 콜백 or 상태 확인으로 나중에 최종 결과 반영

### 알아야 할 것
- Fallback 전략은 비즈니스 요구사항에 따라 달라짐:
  - **결제 보류 후 재시도**: 일단 주문을 받아두고 나중에 결제를 재시도 (이 과제에 적합)
  - **대체 PG사로 라우팅**: 메인 PG 장애 시 서브 PG로 전환 (실무 확장)
  - **즉시 실패 반환**: 결제를 아예 받지 않음 (UX 불리)
- `@CircuitBreaker(fallbackMethod = "...")` 또는 `@Retry(fallbackMethod = "...")`로 연결
- Fallback 메서드는 원본 메서드와 **같은 파라미터 + Throwable**을 받아야 함

---

## 4. 트랜잭션 경계 설계 — 과제 연동의 핵심

**핵심**: 외부 PG 호출을 트랜잭션 안에 넣을 것인가 밖에 넣을 것인가

### 왜 중요한가?
- 현재 OrderFacade.createOrder()가 하나의 @Transactional로 묶여 있음
- 이 트랜잭션 안에서 PG를 호출하면: PG 지연(1~5초) 동안 **DB 커넥션을 점유**
- HikariCP 커넥션 풀이 고갈되면 → 결제와 무관한 다른 API까지 전부 멈춤

### 알아야 할 것
- **원칙: 외부 호출은 트랜잭션 밖에서**
  1. 주문을 PENDING 상태로 DB에 먼저 저장 (트랜잭션 커밋)
  2. PG 호출 (트랜잭션 밖)
  3. PG 응답에 따라 주문 상태 업데이트 (새로운 트랜잭션)
- 이렇게 분리하면 PG가 아무리 느려도 DB 커넥션은 안전
- 대신 **"PG 성공했는데 주문 상태 업데이트 실패"** 같은 불일치가 발생할 수 있음 → 비동기 확인으로 보완

### 상태 전이 설계

```
주문 생성 → [PENDING] → PG 요청 → PG 응답/콜백 → [PAID] 또는 [FAILED]
                                    ↘ 타임아웃 → [PENDING 유지] → 상태 확인 API로 보정
```

---

## 5. 비동기 결제 연동 (콜백 + 상태 확인) — 과제 체크리스트

**핵심**: PG Simulator가 비동기 결제이므로, 요청 성공 ≠ 결제 완료. 최종 결과를 별도로 확인해야 함

### PG Simulator의 동작 방식
1. `POST /api/v1/payments` → 요청 접수 (성공률 60%, 지연 100~500ms)
2. PG 내부에서 비동기 처리 (처리 지연 1~5초)
3. 처리 완료 시 `callbackUrl`로 결과 전송 (성공 70% / 한도초과 20% / 잘못된카드 10%)

### 알아야 할 것
- **콜백 수신 엔드포인트 구현**: PG가 callbackUrl로 보내는 결제 결과를 받아 주문 상태 업데이트
- **결제 상태 확인 API 활용**: `GET /api/v1/payments/{txId}` 또는 `GET /api/v1/payments?orderId={orderId}`
- **콜백이 오지 않는 경우 대비**: 타임아웃 실패, 네트워크 문제 등으로 콜백이 유실될 수 있음
  - 주기적 폴링 또는 수동 API 호출로 상태 복구
- **"결제됐는데 우리는 실패로 처리"** — 이 불일치를 방지하는 것이 핵심
  - 타임아웃으로 응답을 못 받았지만, PG에서는 실제로 결제가 된 경우
  - → 상태 확인 API로 확인하여 정상 반영 (과제 체크리스트 항목)

---

## 6. Retry (재시도) — Nice-To-Have

**핵심**: 일시적 장애(transient fault)에서 재시도로 정상 응답을 받아내는 전략

### 왜 필요한가?
- PG 서버 503 에러, 네트워크 패킷 손실 등은 재시도만으로 해결되는 경우가 많음
- PG 요청 성공률이 60%이므로 재시도 효과가 클 수 있음

### 알아야 할 것
- **멱등키(Idempotency Key)**: 결제는 돈이 걸려 있으므로, 같은 요청을 두 번 보내도 이중 결제 방지 필요
  - PG Simulator는 orderId 기반 조회를 지원하므로, orderId를 멱등키처럼 활용 가능
- **Backoff 전략**: 재시도 간 대기 시간
  - 고정(fixed): 매번 같은 시간 대기
  - random: 랜덤한 시간 대기
  - **exponential**: 1초 → 2초 → 4초 식으로 점점 늘어남 (권장)
- 재시도는 **최대 2~3회**로 제한
- 재시도할 예외를 **명시적으로 지정**해야 함 (모든 예외에 재시도하면 안 됨)
- **Retry + Circuit Breaker 조합**: Retry가 실패를 누적 → CB가 "이제 아예 보내지 말자"를 결정
- 최종 실패 시 **Fallback**으로 연결

---

## 7. 보상 트랜잭션 (Compensation) — 이해하면 좋은 것

**핵심**: PG에서는 결제가 됐는데 주문 생성이 실패하거나, 그 반대 상황에서 정합성을 보장하는 로직

### 왜 필요한가?
- 외부 시스템(PG)과 내부 시스템(주문)은 하나의 DB 트랜잭션으로 묶을 수 없음
- 한쪽은 성공, 한쪽은 실패하는 **상태 불일치**가 반드시 발생할 수 있음

### 알아야 할 것
- **결제 성공 + 주문 상태 업데이트 실패** → 결제 취소 API를 호출하여 보상
- **주문 생성 성공 + 결제 실패** → 주문 상태를 FAILED/CANCELLED로 변경
- 이 과제에서는 풀 보상 트랜잭션보다는 **상태 확인 API로 정합성을 맞추는 수준**이면 충분
- 실무에서는 정기 배치로 주문-결제 상태를 대조하여 불일치 건을 보정하기도 함

---

## 8. 모니터링 + 알림 — 이해하면 좋은 것

**핵심**: PG사별 실패율, 응답시간, 서킷 브레이커 상태를 실시간으로 추적하고, 임계치 초과 시 즉시 알림

### 알아야 할 것
- Resilience4j는 **Micrometer 메트릭**을 기본 지원 → Prometheus + Grafana 연동 가능
- 이미 프로젝트에 monitoring 모듈(Micrometer + Prometheus)이 구성되어 있음
- 추적해야 할 지표: PG 실패율, 평균 응답시간, 서킷 브레이커 상태 변화, 재시도 횟수

### 구현 완료: Grafana 대시보드

기존에 Prometheus + Grafana Docker 컨테이너와 Spring Boot Actuator 연동은 구성되어 있었으나, **시각화 대시보드가 없었다.**
Grafana 대시보드 프로비저닝 설정과 JSON 대시보드 2개를 추가하여, 컨테이너 실행 시 대시보드가 자동 로드되도록 구현했다.

#### 추가/수정한 파일

| 파일 | 역할 |
|------|------|
| `docker/grafana/provisioning/dashboards/dashboard.yml` | 대시보드 자동 프로비저닝 설정 |
| `docker/grafana/dashboards/jvm-dashboard.json` | JVM 모니터링 대시보드 |
| `docker/grafana/dashboards/http-dashboard.json` | HTTP 요청 모니터링 대시보드 |
| `docker/monitoring-compose.yml` | dashboards 볼륨 마운트 추가 |

#### JVM Dashboard (8개 패널)

| 패널 | 메트릭 | 설명 |
|------|--------|------|
| Heap Memory Used | `jvm_memory_used_bytes{area="heap"}` | 힙 메모리 영역별 사용량 |
| Heap Memory Max | `jvm_memory_max_bytes` + `used_bytes` | 최대치 대비 사용량 비교 |
| Non-Heap Memory Used | `jvm_memory_used_bytes{area="nonheap"}` | Metaspace, CodeCache 등 |
| GC Pause Duration | `jvm_gc_pause_seconds_sum/count` | GC 평균 소요 시간 |
| GC Count | `jvm_gc_pause_seconds_count` | 분당 GC 발생 횟수 |
| Live Threads | `jvm_threads_live/daemon/peak` | 스레드 수 추이 |
| CPU Usage | `process_cpu_usage`, `system_cpu_usage` | 프로세스/시스템 CPU 사용률 |
| Loaded Classes | `jvm_classes_loaded_classes` | 로드된 클래스 수 (Stat 패널) |

#### HTTP Requests Dashboard (8개 패널)

| 패널 | 메트릭 | 설명 |
|------|--------|------|
| Request Rate | `rate(http_server_requests_seconds_count)` | 초당 요청 수 (RPS) |
| Total Requests | `sum(rate(...)) * 60` | 분당 총 요청 수 (Stat 패널) |
| Average Response Time | `seconds_sum / seconds_count` | 엔드포인트별 평균 응답 시간 |
| Response Time Percentiles | `histogram_quantile(0.50/0.95/0.99)` | p50, p95, p99 백분위 |
| Error Rate (4xx + 5xx) | `status=~"4.."`/`"5.."` | 에러 요청 추이 |
| Error Rate (%) | `5xx / total * 100` | 5xx 에러율 게이지 |
| Requests by Status Code | `sum by (status)` | 상태 코드별 분포 (파이 차트) |
| Top 10 Slowest Endpoints | `topk(10, avg response time)` | 느린 엔드포인트 테이블 |

#### 실행 방법

```bash
# 모니터링 스택 실행
docker-compose -f ./docker/monitoring-compose.yml up

# Grafana 접속: http://localhost:3000 (admin/admin)
# 대시보드가 자동으로 로드됨
```

#### 메트릭 수집 흐름

```
Spring Boot App                Prometheus              Grafana
┌──────────────┐   pull       ┌──────────────┐  query  ┌──────────────┐
│ /actuator/   │ ◄──────────  │  메트릭 수집   │ ◄───── │  대시보드     │
│ prometheus   │   (5초 간격)  │  + 저장       │        │  시각화       │
│ (port 8081)  │              └──────────────┘        └──────────────┘
└──────────────┘              localhost:9090           localhost:3000
```

---

## 배경 개념 정리

| 개념 | 설명 |
|------|------|
| **비동기 결제** | 요청과 실제 처리가 분리됨. 요청 성공 ≠ 결제 완료. 콜백으로 최종 결과 수신 |
| **멱등성 (Idempotency)** | 같은 요청을 여러 번 보내도 결과가 동일해야 함 (재시도 안전성) |
| **장애 전파** | 외부 시스템 하나의 문제가 내부 시스템 전체로 번지는 현상 |
| **트랜잭션 경계** | 외부 호출과 DB 트랜잭션을 분리하여 커넥션 풀 고갈 방지 |
| **Resilience4j** | Timeout, Retry, CircuitBreaker, Fallback을 구현하는 Java 라이브러리 (Spring Boot 통합) |
| **fail-fast** | 장애 상황에서 빠르게 실패를 반환하여 자원을 보호하는 원칙 |

---

## 학습 우선순위 요약

### Must-Have (과제 필수)
1. **Timeout** → 모든 Resilience의 전제 조건
2. **Circuit Breaker** → 상태 전이(Closed→Open→Half-Open) 확실히 이해
3. **Fallback** → CB/타임아웃 실패 시 "결제 보류" 상태로 대응

### 과제 연동 패턴 (체크리스트 항목)
4. **트랜잭션 경계 설계** → 외부 호출을 트랜잭션 밖으로 분리
5. **비동기 결제 연동** → 콜백 수신 + 상태 확인 API로 정합성 확보

### Nice-To-Have
6. **Retry + 멱등키** → CB와 조합, exponential backoff

### 실무 확장 (이해만)
7. **보상 트랜잭션** → 상태 불일치 시 복구 전략
8. **모니터링 + 알림** → 장애 감지 및 대응 속도 확보


---
# 고민한 것들...

## PG 외부 API 호출 시점 설계

### 핵심 판단: PG 호출을 트랜잭션 안에 넣을 것인가, 밖에 넣을 것인가?

PG-simulator가 **비동기 결제** (요청 → 콜백으로 결과 수신) 방식이므로, 주문과 결제를 분리된 흐름으로 설계한다.

### 결제 흐름

주문 생성 API(`POST /api/v1/orders`)에서 주문과 결제를 한 번에 처리하되, **트랜잭션은 분리**한다.

```
POST /api/v1/orders  (클라이언트 요청 — cardType, cardNo 포함)
   │
   ├─ [트랜잭션 1] OrderPlacementService.placeOrder() — 주문 생성 (PENDING)
   │   - 쿠폰 검증 및 할인 계산
   │   - 재고 검증/차감 (비관적 락)
   │   - Order(status=PENDING) 저장
   │   - 쿠폰 사용, 포인트 차감, 장바구니 삭제
   │   → DB 커밋 (트랜잭션 종료, DB 커넥션 반환)
   │
   ├─ [분기] 결제 금액 확인
   │   ├─ paymentAmount <= 0 → 전액 할인, PG 호출 없이 바로 PAID 처리
   │   └─ paymentAmount > 0  → PG 결제 진행 ↓
   │
   ├─ [트랜잭션 밖] PaymentFacade.processPayment() — PG 결제 요청
   │   │
   │   ├─ [트랜잭션 2] PaymentService.initiatePayment()
   │   │   - 주문 검증, Payment(REQUESTED) 생성/저장
   │   │
   │   ├─ [트랜잭션 밖] PaymentGateway.requestPayment() — DIP 인터페이스
   │   │   └─ Resilience4jPaymentGateway → PgApiClient (TimeLimiter/CircuitBreaker/Retry)
   │   │   - 실패 시 Fallback → 안전한 응답 반환
   │   │
   │   └─ [트랜잭션 3] PG 응답에 따라 Payment 상태 업데이트
   │       - 접수 성공 → PENDING (콜백 대기)
   │       - 타임아웃 → UNKNOWN
   │       - 실패 → FAILED
   │
   └─ 응답 반환 (주문 상세 정보)

POST /api/v2/payments/callback  (PG 콜백)
   │
   └─ [트랜잭션 4] 최종 결제 결과 반영
       - 성공 → Payment = SUCCESS, Order = PAID
       - 실패 → Payment = FAILED, Order = FAILED
```

#### 트랜잭션 분리 구조

기존에는 `OrderFacade.createOrder()`에 `@Transactional`이 걸려 있었으나,
PG 호출까지 같은 트랜잭션에 포함되면 외부 API 응답 대기 동안 DB 커넥션을 점유하는 문제가 있다.

이를 해결하기 위해 **별도 클래스(`OrderPlacementService`)로 트랜잭션 로직을 분리**했다.

```
OrderFacade (트랜잭션 없음 — 오케스트레이터)
   ├─ OrderPlacementService.placeOrder()  ← @Transactional (트랜잭션 1)
   └─ PaymentFacade.processPayment()      ← 트랜잭션 밖에서 PG 호출
```

> **왜 별도 클래스인가?**
> Spring AOP 프록시는 같은 클래스 내부 메서드 호출 시 `@Transactional`이 동작하지 않는다.
> `self-injection` 방식도 가능하지만, 순환 참조 문제가 생길 수 있고 코드 의도가 불명확해진다.
> **별도 클래스로 분리하는 것이 일반적이고 테스트도 용이하다.**

### 왜 PG 호출을 트랜잭션 밖에서 하는가?

| 판단 포인트 | 이유 |
|---|---|
| **PG 호출을 트랜잭션 밖에서** | PG 응답 지연(1~5초)이 DB 커넥션/락을 점유하면 전체 시스템에 장애 전파 |
| **Payment를 먼저 REQUESTED로 저장** | PG 호출 전에 "결제 시도했다"는 기록이 있어야, 타임아웃으로 응답을 못 받아도 추적 가능 |
| **주문 생성 트랜잭션을 먼저 커밋** | 주문은 재고/쿠폰/포인트 차감이 있어 트랜잭션이 무거움. 커밋 후 결제를 호출해야 DB 커넥션 점유 시간 최소화 |

### PG를 트랜잭션 안에 넣으면 발생하는 문제

#### 1) 트랜잭션 장기 보유 → DB 커넥션 풀 고갈

```java
@Transactional
public Order createOrder(OrderCommand cmd) {
    Order order = orderRepository.save(cmd.toEntity());   // DB 커넥션 획득
    paymentGateway.requestPayment(order);                  // 외부 HTTP 호출 (1~5초 대기)
    order.markPaid();                                      // commit은 HTTP 응답 이후
}
```

- 외부 API 응답을 기다리는 동안 **DB 커넥션을 계속 점유**한다.
- PG Simulator의 처리 지연이 1~5초이므로, 그 동안 커넥션 1개가 묶여 있다.
- PG 장애로 타임아웃(30초)이 걸리면 커넥션 풀이 빠르게 고갈된다.
- HikariCP 기본 풀 사이즈가 10이라면, **동시 10건의 주문만으로 서비스 전체가 멈출 수 있다.**

#### 2) 롤백 불일치 (분산 트랜잭션 문제)

PG 결제는 성공했는데 이후 로직에서 예외가 발생하는 경우:

```java
@Transactional
public Order createOrder(OrderCommand cmd) {
    Order order = orderRepository.save(cmd.toEntity());
    paymentGateway.requestPayment(order);   // ✅ PG 결제 성공 (돈 빠져나감)
    inventoryService.deduct(order);          // ❌ 재고 부족 예외 발생
    // → DB 롤백됨, 하지만 PG 결제는 이미 완료됨
}
```

- DB 트랜잭션은 롤백되지만, **PG에서 이미 승인된 결제는 롤백되지 않는다.**
- HTTP 호출은 DB 트랜잭션의 관리 대상이 아니기 때문이다.
- **결과**: 고객 돈은 빠졌는데 주문은 없는 상태 → 데이터 정합성 깨짐

#### 3) PG 타임아웃의 모호성

```java
paymentGateway.requestPayment(order);  // 타임아웃 발생!
```

타임아웃이 발생했을 때:
- PG에서 **실제로 결제가 됐는지 안 됐는지 알 수 없다.**
- 요청은 PG 서버에 도달했고 처리됐지만, 응답만 늦어진 것일 수 있다.
- 트랜잭션이 롤백되면 → 돈은 빠졌는데 주문이 없는 상태
- 트랜잭션을 커밋하면 → 실제론 결제 실패인데 주문이 생긴 상태
- **어느 쪽이든 정합성이 깨진다.**

#### 4) DB Lock 장기 보유

트랜잭션 내에서 `SELECT ... FOR UPDATE`나 비관적 락을 사용하는 경우:
- 외부 API 호출 동안 **row-level lock이 유지**된다.
- 같은 row에 접근하는 다른 요청들이 전부 대기 → **데드락 또는 lock timeout** 발생 가능
- 특히 재고 차감 등 경합이 심한 row에서 치명적이다.
- 현재 `OrderFacade.createOrder()`에서 재고에 비관적 락을 사용한다면, PG 응답 대기 동안 해당 상품의 모든 주문이 블로킹된다.

#### 5) 장애 전파 (Cascading Failure)

```
PG 응답 지연 → 커넥션 풀 고갈 → 주문 외 다른 API도 커넥션 못 얻음 → 서비스 전체 장애
```

- 외부 시스템 장애가 **내부 시스템 전체로 전파**된다.
- 결제와 무관한 상품 조회, 회원 정보 등 다른 API까지 모두 영향을 받는다.
- 이것이 가장 심각한 문제이며, Timeout/CircuitBreaker/트랜잭션 분리가 필요한 핵심 이유다.

### 결론

주문 생성 API에서 결제까지 한 번에 처리하되, 트랜잭션은 분리한다.

1. **`OrderPlacementService.placeOrder()`** — 주문 생성 트랜잭션 (PENDING 상태로 커밋)
2. **`PaymentFacade.processPayment()`** — 트랜잭션 밖에서 PG 호출 (Resilience4j 적용)

`OrderFacade`는 `@Transactional` 없이 오케스트레이터 역할만 수행하며, 두 단계를 순차 호출한다.
이 구조는 DB 커넥션 점유를 최소화하면서도, 클라이언트는 한 번의 API 호출로 주문+결제를 처리할 수 있다.


---

## 보상 트랜잭션 (Compensation Transaction) 학습 정리

### 핵심 문제

외부 시스템(PG)과 내부 DB는 **하나의 트랜잭션으로 묶을 수 없다.**

```java
@Transactional  // 이 트랜잭션은 우리 DB만 관리함
public void pay(PaymentRequest request) {
    Order order = orderRepository.findById(orderId);
    order.startPayment();          // 1. 내부 상태 변경
    pgClient.requestPayment(...);  // 2. 외부 호출 (트랜잭션 밖의 세계)
    order.completePayment();       // 3. 여기서 실패하면?
}
```

2번이 성공했는데 3번에서 예외가 터지면 → DB는 롤백되지만 **PG에서는 이미 결제가 진행 중**이다.

---

### 구현 전략: 상태 기반 단계 분리

보상 트랜잭션의 핵심은 **외부 호출 전후로 트랜잭션을 분리하고, 상태(state)로 추적**하는 것이다.

#### 1단계: 결제 요청 (내부 상태 먼저 저장)

```java
// 트랜잭션 1: 결제 요청 전 상태 저장
@Transactional
public Payment initiatePayment(PaymentCommand command) {
    Order order = orderRepository.findById(command.getOrderId());

    Payment payment = Payment.create(
        order.getId(),
        command.getCardType(),
        command.getCardNo(),
        order.getTotalAmount()
    );
    payment.markRequested();  // 상태: REQUESTED

    return paymentRepository.save(payment);
}
```

#### 2단계: PG 호출 (트랜잭션 밖)

```java
// 트랜잭션 없이 외부 호출
public PgResponse callPg(Payment payment) {
    try {
        return pgClient.requestPayment(
            payment.getOrderId(),
            payment.getCardType(),
            payment.getCardNo(),
            payment.getAmount(),
            callbackUrl
        );
    } catch (TimeoutException e) {
        return PgResponse.timeout();
    } catch (Exception e) {
        return PgResponse.failed(e.getMessage());
    }
}
```

#### 3단계: 결과에 따라 상태 업데이트

```java
// 트랜잭션 2: PG 응답에 따라 상태 갱신
@Transactional
public void handlePgResponse(Long paymentId, PgResponse response) {
    Payment payment = paymentRepository.findById(paymentId);

    if (response.isAccepted()) {
        // PG가 요청을 접수함 (비동기이므로 아직 결제 완료는 아님)
        payment.markPending(response.getTransactionId());
    } else if (response.isTimeout()) {
        // 타임아웃: PG에서 처리됐을 수도 있음 → 확인 필요
        payment.markUnknown();
    } else {
        // PG가 요청 자체를 거부
        payment.markFailed(response.getReason());
    }
}
```

#### 전체 흐름을 조율하는 Facade

```java
public class PaymentFacade {

    public PaymentInfo processPayment(PaymentCommand command) {
        // 1. 내부 상태 저장 (트랜잭션 1)
        Payment payment = paymentService.initiatePayment(command);

        // 2. PG 호출 (트랜잭션 없음)
        PgResponse response = pgClient.callPg(payment);

        // 3. 결과 반영 (트랜잭션 2)
        paymentService.handlePgResponse(payment.getId(), response);

        return PaymentInfo.from(payment);
    }
}
```

---

### 보상이 필요한 3가지 시나리오와 대응

#### 시나리오 A: 타임아웃 → PG에서는 결제됨

```
우리: 타임아웃 → payment 상태 = UNKNOWN
PG:  결제 성공 → 돈이 빠짐
```

**대응: 상태 확인 후 동기화**

```java
// 콜백 수신 엔드포인트
@PostMapping("/api/v1/payments/callback")
public ResponseEntity<Void> handleCallback(@RequestBody PgCallbackRequest request) {
    paymentService.syncPaymentResult(
        request.getOrderId(),
        request.getTransactionId(),
        request.getStatus()  // SUCCESS / FAILED / LIMIT_EXCEEDED 등
    );
    return ResponseEntity.ok().build();
}
```

```java
@Transactional
public void syncPaymentResult(String orderId, String txId, String pgStatus) {
    Payment payment = paymentRepository.findByOrderId(orderId);

    switch (pgStatus) {
        case "SUCCESS" -> {
            payment.markSuccess(txId);
            orderService.completeOrder(orderId);
        }
        case "FAILED", "LIMIT_EXCEEDED", "INVALID_CARD" -> {
            payment.markFailed(pgStatus);
            orderService.failOrder(orderId);
        }
    }
}
```

#### 시나리오 B: 콜백이 안 오는 경우

**대응: 수동 또는 주기적 상태 확인 API**

```java
// 수동으로 결제 상태를 PG에 조회하여 동기화
public void verifyAndSync(String orderId) {
    Payment payment = paymentRepository.findByOrderId(orderId);

    // PENDING이나 UNKNOWN 상태인 건만 확인
    if (!payment.needsVerification()) return;

    // PG에 상태 조회
    PgPaymentStatus pgStatus = pgClient.getPaymentStatus(orderId);

    if (pgStatus.isCompleted()) {
        syncPaymentResult(orderId, pgStatus.getTxId(), pgStatus.getStatus());
    }
    // 아직 처리 중이면 그대로 둠
}
```

#### 시나리오 C: PG 결제 성공 → 내부 주문 상태 업데이트 실패

**대응: 콜백 재처리 가능하도록 멱등하게 설계**

```java
@Transactional
public void syncPaymentResult(String orderId, String txId, String pgStatus) {
    Payment payment = paymentRepository.findByOrderId(orderId);

    // 이미 처리된 건이면 무시 (멱등성 보장)
    if (payment.isFinalized()) return;

    // ... 상태 업데이트 로직
}
```

---

### Payment 상태 전이도

```
  REQUESTED ──PG 접수 성공──> PENDING ──콜백 성공──> SUCCESS
      │                        │
      │                        ├──콜백 실패──> FAILED
      │                        │
      │                        └──콜백 안옴──> (수동 확인으로 동기화)
      │
      ├──PG 접수 실패──> FAILED
      │
      └──타임아웃──> UNKNOWN ──상태 확인──> SUCCESS or FAILED
```

---

### 정리

| 원칙 | 설명 |
|---|---|
| **외부 호출 전후로 트랜잭션 분리** | 하나의 트랜잭션에 외부 호출을 넣지 않음 |
| **상태로 추적** | REQUESTED → PENDING → SUCCESS/FAILED |
| **UNKNOWN 상태 도입** | 타임아웃 시 "모르겠다"를 명시적으로 표현 |
| **콜백 + 수동 확인 병행** | 콜백이 안 오면 PG API로 직접 조회 |
| **멱등하게 설계** | 같은 콜백이 두 번 와도 안전하게 처리 |

핵심은 **"외부 호출은 실패할 수 있다"를 전제로, 모든 중간 상태를 DB에 기록하고 나중에 복구할 수 있게 만드는 것**이다.

---

# 구현 계획

## 설계 원칙

- **주문과 결제를 별도 트랜잭션으로 분리** — PG 호출은 트랜잭션 밖에서 수행
- **상태 기반 추적** — Payment 엔티티로 결제 상태 전이를 관리
- **Resilience4j** — Timeout + CircuitBreaker + Fallback (Must-Have), Retry (Nice-To-Have)
- **기존 패턴과의 차이점** — PaymentService 메서드에 개별 `@Transactional`을 부여 (Facade에서 트랜잭션을 분리해야 하므로)

---

## Phase 1: Red — 실패하는 테스트 작성

### 1-1. Payment 도메인 단위 테스트

| 테스트 클래스 | 검증 대상 |
|---|---|
| `PaymentTest` | 엔티티 생성, 상태 전이 (REQUESTED → PENDING → SUCCESS/FAILED), UNKNOWN 처리 |
| `PaymentServiceTest` | Mock Repository로 비즈니스 로직 (initiatePayment, handlePgResponse, syncPaymentResult) |

### 1-2. PG 연동 단위 테스트

| 테스트 클래스 | 검증 대상 |
|---|---|
| `PgApiClientTest` | PG API 호출/응답 매핑, 타임아웃 예외 처리, fallback 동작 |

### 1-3. PaymentFacade 단위 테스트

| 테스트 클래스 | 검증 대상 |
|---|---|
| `PaymentFacadeTest` | 트랜잭션 분리 흐름 (Payment 생성 → PG 호출 → 상태 업데이트), Fallback 시나리오 |

### 1-4. Controller 테스트

| 테스트 클래스 | 검증 대상 |
|---|---|
| `PaymentV1ControllerTest` | `POST /api/v1/payments` 결제 요청, `POST /api/v1/payments/callback` 콜백 수신 |

---

## Phase 2: Green — 테스트를 통과하는 코드 작성

### 2-1. Domain Layer

**위치**: `com.loopers.domain.payment`

| 파일 | 설명 |
|---|---|
| `Payment.java` | 엔티티 — orderId, memberId, amount, cardType, cardNo, status, pgTransactionId, failureReason |
| `PaymentStatus.java` | enum — `REQUESTED`, `PENDING`, `SUCCESS`, `FAILED`, `UNKNOWN` |
| `PaymentService.java` | 비즈니스 로직 — 각 메서드에 `@Transactional` (트랜잭션 분리를 위해) |
| `PaymentRepository.java` | 도메인 인터페이스 |

**Payment 상태 전이:**

```
REQUESTED ──PG 접수 성공──> PENDING ──콜백 성공──> SUCCESS
    │                        │
    │                        └──콜백 실패──> FAILED
    ├──PG 접수 실패──> FAILED
    └──타임아웃──> UNKNOWN ──상태 확인──> SUCCESS or FAILED
```

**PaymentService 핵심 메서드:**

```java
@Transactional
public Payment initiatePayment(Long memberId, PaymentCommand command)
// 주문 검증 + Payment(REQUESTED) 생성/저장 → DB 커밋

@Transactional
public void handlePgResponse(Long paymentId, PgPaymentResponse response)
// PG 응답에 따라 PENDING / FAILED / UNKNOWN 상태 업데이트

@Transactional
public void syncPaymentResult(PgCallbackRequest callback)
// 콜백 수신 → 최종 결제 결과 반영 (SUCCESS/FAILED) + 주문 상태 갱신
// 멱등성 보장: 이미 처리된 건이면 무시
```

### 2-2. Infrastructure Layer

**위치**: `com.loopers.infrastructure.payment`

| 파일 | 설명 |
|---|---|
| `PaymentRepositoryImpl.java` | Repository 구현체 (DIP) |
| `PaymentJpaRepository.java` | Spring Data JPA |
| `PgFeignClient.java` | Feign 기반 PG API 선언적 HTTP 클라이언트 |
| `PgFeignConfig.java` | Feign 타임아웃 및 Retryer 설정 |
| `PgApiClient.java` | Feign 기반 PG API 호출 + Resilience4j 적용 |
| `PgPaymentRequest.java` | PG 요청 DTO |
| `PgPaymentResponse.java` | PG 응답 DTO |

**PgFeignClient 선언적 인터페이스:**

```java
@FeignClient(name = "pgClient", url = "${pg.base-url}", configuration = PgFeignConfig.class)
public interface PgFeignClient {

    @PostMapping(value = "/api/v1/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    PgPaymentResponse requestPayment(
            @RequestHeader("X-USER-ID") String memberId,
            @RequestBody PgPaymentRequest request);

    @GetMapping("/api/v1/payments")
    PgPaymentStatusResponse getPaymentStatus(
            @RequestHeader("X-USER-ID") String memberId,
            @RequestParam("orderId") String orderId);
}
```

**PgApiClient (Resilience4j 래퍼):**

```java
@Component
public class PgApiClient {
    private final PgFeignClient pgFeignClient;

    @CircuitBreaker(name = "pgCircuit", fallbackMethod = "requestPaymentFallback")
    public PgPaymentResponse requestPayment(Long memberId, PgPaymentRequest request) {
        return pgFeignClient.requestPayment(String.valueOf(memberId), requestWithCallback);
    }

    private PgPaymentResponse requestPaymentFallback(Long memberId, PgPaymentRequest request, Throwable t) {
        return PgPaymentResponse.failed("PG 시스템 장애: " + t.getMessage());
    }
}
```

### 2-3. Application Layer

**위치**: `com.loopers.application.payment`

| 파일 | 설명 |
|---|---|
| `PaymentFacade.java` | 결제 흐름 조율 — 트랜잭션 분리의 핵심 |
| `PaymentInfo.java` | 응답 DTO (record) |

**PaymentFacade 핵심 흐름:**

```java
@Component
public class PaymentFacade {

    // Facade 자체에는 @Transactional 없음 — 트랜잭션 분리를 위해
    public PaymentInfo processPayment(Long memberId, PaymentCommand command) {
        // [트랜잭션 1] 주문 검증 + Payment(REQUESTED) 생성/저장
        Payment payment = paymentService.initiatePayment(memberId, command);

        // [트랜잭션 밖] PG API 호출 (Timeout/CircuitBreaker 적용)
        PgPaymentResponse pgResponse = pgApiClient.requestPayment(
            PgPaymentRequest.from(payment)
        );

        // [트랜잭션 2] PG 응답에 따라 Payment 상태 업데이트
        paymentService.handlePgResponse(payment.getId(), pgResponse);

        return PaymentInfo.from(payment);
    }

    // 콜백 처리
    public void handleCallback(PgCallbackRequest callback) {
        // [트랜잭션 3] 최종 결제 결과 반영 + 주문 상태 갱신
        paymentService.syncPaymentResult(callback);
    }

    // 수동 상태 확인 (콜백 유실 대비)
    public void verifyPayment(String orderId) {
        PgPaymentStatusResponse pgStatus = pgApiClient.getPaymentStatus(orderId);
        if (pgStatus.isCompleted()) {
            paymentService.syncFromPgStatus(orderId, pgStatus);
        }
    }
}
```

### 2-4. Interfaces Layer

**위치**: `com.loopers.interfaces.api.payment`

| 파일 | 설명 |
|---|---|
| `PaymentV1Controller.java` | REST API |
| `PaymentV1Dto.java` | Request/Response DTO |

**엔드포인트:**

```
POST /api/v1/payments              — 결제 요청
POST /api/v1/payments/callback     — PG 콜백 수신
GET  /api/v1/payments/verify       — 수동 상태 확인 (콜백 유실 대비)
```

### 2-5. Resilience 설정

**application.yml 추가:**

```yaml
# PG API 설정
pg:
  base-url: http://localhost:8082
  callback-url: http://localhost:8080/api/v1/payments/callback

# Feign 타임아웃: PgFeignConfig에서 설정 (connect: 1s, read: 3s)

# Resilience4j
resilience4j:
  circuitbreaker:
    instances:
      pgCircuit:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 2
        slow-call-duration-threshold: 2s
        slow-call-rate-threshold: 50
  retry:
    instances:
      pgRetry:
        max-attempts: 3
        wait-duration: 1s
        retry-exceptions:
          - java.net.SocketTimeoutException
          - org.springframework.web.client.ResourceAccessException
```

### 2-6. 의존성 추가 (승인 필요)

```kotlin
// build.gradle.kts
implementation("io.github.resilience4j:resilience4j-spring-boot3")
implementation("org.springframework.boot:spring-boot-starter-aop")
```

### 2-7. Order 엔티티 변경 (승인 필요)

현재 Order에 status 필드가 없으므로, 결제 결과에 따라 주문 상태를 갱신하려면 추가 필요:

```java
@Enumerated(EnumType.STRING)
private OrderStatus status;  // PENDING, PAID, FAILED, CANCELLED
```

---

## Phase 3: Refactor

- Payment 상태 전이 로직을 엔티티 내부로 캡슐화 (잘못된 전이 방지)
- ErrorType에 결제 관련 에러 코드 추가 (`PAYMENT_NOT_FOUND`, `PAYMENT_ALREADY_PROCESSED` 등)
- unused import 제거, 코드 정리
- 전체 테스트 통과 확인

---

## 의사결정 기록

### 1. PaymentService에 @Transactional 부여 — A안 채택

**결정**: PaymentService 각 메서드에 개별 `@Transactional`을 부여한다.

**배경**: 기존 프로젝트 패턴은 Domain Service는 non-transactional, Facade에서 하나의 `@Transactional`로 묶는 구조다.
그러나 결제 도메인은 의도적으로 트랜잭션을 분리해야 한다:

```
[트랜잭션 1] Payment(REQUESTED) 저장 → 커밋
[트랜잭션 밖] PG API 호출
[트랜잭션 2] PG 응답에 따라 상태 업데이트 → 커밋
```

**검토한 대안:**

| 안 | 방식 | 장점 | 단점 |
|---|---|---|---|
| **A (채택)** | PaymentService 메서드에 `@Transactional` | 구조 단순, 실무에서 일반적 | 기존 패턴과 달라짐 |
| **B** | 별도 `PaymentTransactionHandler` 클래스 도입 | 기존 패턴 일관성 유지 | 클래스 증가, 구조를 위한 구조 |

**채택 이유:**
- 실무에서 외부 시스템 연동이 있는 도메인은 Service에 `@Transactional`을 거는 것이 일반적
- OrderService 같은 순수 내부 로직은 기존대로 non-transactional 유지하고, 외부 연동 특수성이 있는 PaymentService만 예외로 둔다
- 도메인 특성이 다르면 패턴이 달라지는 것이 자연스럽다
- B안은 트랜잭션 분리만을 위해 불필요한 클래스가 생긴다

### 2. PG 호출 방식 — OpenFeign 채택

**결정**: Spring Cloud OpenFeign을 사용한다.

#### OpenFeign이란?

Netflix에서 만들고 Spring Cloud에서 통합한 **선언적 HTTP 클라이언트**. 인터페이스와 어노테이션만 정의하면 Spring이 런타임에 프록시 구현체를 생성한다.

```java
@FeignClient(name = "pgClient", url = "${pg.base-url}", configuration = PgFeignConfig.class)
public interface PgFeignClient {

    @PostMapping(value = "/api/v1/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    PgPaymentResponse requestPayment(
            @RequestHeader("X-USER-ID") String memberId,
            @RequestBody PgPaymentRequest request);

    @GetMapping("/api/v1/payments")
    PgPaymentStatusResponse getPaymentStatus(
            @RequestHeader("X-USER-ID") String memberId,
            @RequestParam("orderId") String orderId);
}
```

**타임아웃 설정:**

```java
public class PgFeignConfig {
    @Bean
    public Request.Options feignOptions() {
        return new Request.Options(1000, TimeUnit.MILLISECONDS, 3000, TimeUnit.MILLISECONDS, true);
    }

    @Bean
    public Retryer feignRetryer() {
        return Retryer.NEVER_RETRY;  // Feign 자체 재시도 비활성화 (Resilience4j에 위임)
    }
}
```

**예외 체계:**

| HTTP 상태 | 예외 |
|---|---|
| 4xx | `FeignException.BadRequest`, `FeignException.NotFound` 등 |
| 5xx | `FeignException.InternalServerError` 등 |
| 연결 실패 | `RetryableException` (내부 `ConnectException`) |
| 응답 타임아웃 | `RetryableException` (내부 `SocketTimeoutException`) |

**Resilience4j 조합 구조:**

Feign 인터페이스에는 Resilience4j 어노테이션을 직접 붙일 수 없으므로, wrapper 클래스(`PgApiClient`)에서 Resilience4j를 적용한다.

```
PgFeignClient (선언적 HTTP 호출)  ←  PgApiClient (@CircuitBreaker wrapper)
                                  ←  ManualPgApiClient (수동 retry/CB wrapper)
```

#### 채택 이유

**1. 커넥션 관리 개선**

기존 RestTemplate은 `SimpleClientHttpRequestFactory`를 사용하여 **요청마다 새로운 TCP 연결을 생성**했다. PG 요청이 많아지면 연결 생성/해제 오버헤드가 커지고, 트래픽 급증 시 리소스 고갈 가능성이 있었다. OpenFeign은 내부 HTTP 클라이언트를 통해 커넥션 재사용을 지원하며, Apache HttpClient5(`feign-hc5`)를 추가하면 본격적인 커넥션 풀링도 가능하다.

**2. 보일러플레이트 제거**

RestTemplate은 매 요청마다 `HttpHeaders`, `HttpEntity`, `ResponseEntity` 등의 조립 코드가 반복되었다.

```java
// Before (RestTemplate) — 매 메서드마다 이 패턴 반복
HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.APPLICATION_JSON);
headers.set("X-USER-ID", String.valueOf(memberId));
HttpEntity<PgPaymentRequest> entity = new HttpEntity<>(request, headers);
ResponseEntity<PgPaymentResponse> response = pgRestTemplate.postForEntity(
        pgBaseUrl + "/api/v1/payments", entity, PgPaymentResponse.class);
return response.getBody();

// After (Feign) — 인터페이스 메서드 하나로 끝
return pgFeignClient.requestPayment(String.valueOf(memberId), request);
```

**3. Spring Cloud 생태계 통합**

프로젝트가 이미 Spring Cloud 2024.0.1을 사용하고 있어 OpenFeign 도입이 자연스럽다. 로드밸런서, 서비스 디스커버리 등 Spring Cloud 기능과의 통합이 용이하다.

**4. 테스트 용이성**

Feign 인터페이스는 단순한 Java 인터페이스이므로 Mock 처리가 쉽다. RestTemplate은 `MockRestServiceServer`를 사용해야 하는 반면, Feign 인터페이스는 `@Mock`으로 바로 주입 가능하다.

**5. 유지보수 관점**

RestTemplate은 Spring 6에서 maintenance mode로 전환되었다. 신규 기능 추가가 없으며, Spring 팀은 RestClient 또는 WebClient 사용을 권장한다. OpenFeign은 활발히 유지보수되고 있다.

#### 장점 vs 단점

| 장점 | 단점 |
|------|------|
| 선언적 코드 — 인터페이스만 정의하면 구현 자동 생성 | `spring-cloud-starter-openfeign` 의존성 추가 필요 |
| 보일러플레이트 제거 — 헤더/바디 조립 코드 불필요 | Resilience4j 적용 시 wrapper 클래스 필요 |
| API 추가가 메서드 하나로 끝남 | 프록시 기반이라 디버깅 시 한 단계 더 추적 필요 |
| yml 또는 Java Config로 타임아웃 설정 가능 | |
| Spring Cloud 생태계와 자연스러운 통합 | |
| 인터페이스 기반이라 테스트 Mock 용이 | |

#### RestTemplate과의 비교

| 판단 기준 | RestTemplate | OpenFeign (채택) |
|---|---|---|
| 코드 스타일 | 명령적 (직접 호출 코드 작성) | 선언적 (인터페이스만 정의) |
| 커넥션 관리 | SimpleClientHttpRequestFactory는 매 요청 새 연결 | 커넥션 재사용 지원 |
| 보일러플레이트 | 헤더/바디/URL 매번 조립 | 어노테이션으로 해결 |
| 의존성 | 추가 불필요 | spring-cloud-openfeign 추가 |
| 타임아웃 설정 | Bean에서 직접 설정 | Configuration 클래스 또는 yml |
| 유지보수 상태 | Spring 6에서 maintenance mode | 활발히 유지 |
| Resilience4j 적용 | 같은 클래스에서 바로 | wrapper 클래스 필요 |
| 테스트 | MockRestServiceServer | 인터페이스 Mock |

### 3. 의존성 추가 — 승인 완료

**결정**: `apps/commerce-api/build.gradle.kts`에 추가 완료.

```kotlin
// feign
implementation("org.springframework.cloud:spring-cloud-starter-openfeign")

// resilience
implementation("io.github.resilience4j:resilience4j-spring-boot3")
implementation("org.springframework.boot:spring-boot-starter-aop")
```

### 4. Order에 status 필드 추가 — A안 채택

**결정**: Order 엔티티에 `OrderStatus` 필드를 추가한다.

**검토한 대안:**

| 안 | 방식 | 장점 | 단점 |
|---|---|---|---|
| **A (채택)** | Order에 status 필드 추가 | 주문 상태를 직접 관리, 조회 시 join 불필요, 배송/취소 등 확장 가능 | Order 엔티티 변경 + DB 마이그레이션 필요 |
| **B** | Payment의 status로 간접 판단 | Order 엔티티 변경 없음 | 매번 Payment join 필요, 결제 외 상태(배송/취소) 표현 불가 |

**채택 이유:**
- 주문 상태는 결제뿐 아니라 배송, 취소 등 여러 흐름에서 필요하므로 Order 자체가 상태를 갖는 것이 자연스럽다
- 주문 목록 조회 시 Payment를 매번 join하는 것은 비효율적이다
- 실무에서 주문에 status가 없는 경우는 거의 없다

**추가할 내용:**

```java
// OrderStatus.java
public enum OrderStatus {
    PENDING,    // 주문 생성 (결제 대기)
    PAID,       // 결제 완료
    FAILED,     // 결제 실패
    CANCELLED   // 주문 취소
}

// Order.java에 추가
@Enumerated(EnumType.STRING)
@Column(nullable = false)
private OrderStatus status;
```

- 기존 `Order.create()` 정적 팩토리 메서드에서 초기 상태를 `PENDING`으로 설정
- 상태 전이 메서드 추가: `markPaid()`, `markFailed()`, `cancel()`

---

## V1/V2 구조 분리

**V1 (수동 방식)**: `/api/v1/payments`
```
PaymentV1Controller → ManualPaymentFacade → ManualPgApiClient
```
- Resilience4j 어노테이션 없이 순수 try-catch로 타임아웃/리트라이/서킷브레이커/폴백 구현

**V2 (Resilience4j)**: `/api/v2/payments`
```
PaymentV2Controller → PaymentFacade → PgApiClient (@CircuitBreaker, @Retry 등)
```
- Resilience4j 어노테이션 기반으로 동일 기능 구현

---

## 수동 구현 (V1)

### 타임아웃

Feign 레벨에서 connection/read 타임아웃을 설정하고(`PgFeignConfig`), `ManualPgApiClient`에서 Feign 예외를 직접 분기 처리한다.

**설정값:**
- Connection timeout: 1초 (TCP 연결)
- Read timeout: 3초 (응답 대기)

**예외 분기 처리:**

| 예외 | 의미 | 처리 |
|---|---|---|
| `RetryableException` (내부 `SocketTimeoutException`) | 응답 타임아웃 — PG에 요청이 도달했을 수 있음 | **UNKNOWN** 상태 (이중 결제 위험, 확인 필요) |
| `RetryableException` (내부 `ConnectException`) | 연결 실패 — PG에 요청이 도달하지 못함 | **FAILED** 상태 (안전하게 실패 처리) |
| `FeignException` (status >= 500) | PG 서버 5xx 에러 | **FAILED** 상태 |
| `FeignException` (status < 500) | PG 서버 4xx 에러 | **FAILED** 상태 |

핵심은 **타임아웃 = "PG가 처리 안 한 것"이 아님**이라는 점. 타임아웃은 "우리가 응답을 못 받은 것"이지 PG에서 실제로 결제가 진행됐을 수 있으므로 UNKNOWN으로 두고 콜백/수동확인으로 보정한다.

### 리트라이

결제는 돈이 걸려있으므로 **PG에 요청이 도달하지 않았다고 확신할 수 있는 경우에만 리트라이**한다.

**리트라이 판단 기준:**

| 상황 | 리트라이 | 이유 |
|---|---|---|
| **연결 실패** (RetryableException → ConnectException) | O | PG에 요청이 도달하지 않았으므로 안전 |
| **5xx 서버 에러** (FeignException, status >= 500) | O | PG가 요청을 처리하지 못한 일시적 장애 |
| **응답 타임아웃** (RetryableException → SocketTimeoutException) | **X** | PG에 요청이 도달했을 수 있음 → 이중 결제 위험 |
| **4xx 클라이언트 에러** (FeignException, status < 500) | **X** | 잘못된 요청이므로 재시도해도 같은 결과 |
| **PG 정상 응답이지만 거부** (한도초과 등) | **X** | 비즈니스 로직 실패이므로 재시도 의미 없음 |

**설정값:**
- 최대 재시도: 2회 (총 3회 시도)
- 재시도 간격: 500ms (고정)

**구현 흐름:**

```
requestPayment() 호출
  ├─ 시도 1 → 성공 → 즉시 반환
  │            SocketTimeoutException → 즉시 TIMEOUT 반환 (리트라이 안 함)
  │            HttpClientErrorException → 즉시 FAILED 반환 (리트라이 안 함)
  │            ConnectException → 500ms 대기 → 시도 2
  │            HttpServerErrorException → 500ms 대기 → 시도 2
  ├─ 시도 2 → 성공 → 즉시 반환
  │            (같은 분기 로직)
  │            리트라이 대상 예외 → 500ms 대기 → 시도 3
  └─ 시도 3 → 성공 → 즉시 반환
               실패 → 최종 FAILED 반환
```

**리트라이 효과 (PG 요청 성공률 60% 기준):**

| 시도 횟수 | 최종 성공률 | 계산 |
|---|---|---|
| 1회 (리트라이 없음) | 60% | 0.6 |
| 2회 (리트라이 1회) | 84% | 0.6 + 0.4 × 0.6 |
| 3회 (리트라이 2회) | 93.6% | 0.6 + 0.4 × 0.6 + 0.16 × 0.6 |

### 서킷브레이커

외부 시스템이 반복적으로 실패하면 **호출 자체를 차단**하여 시스템을 보호한다. Resilience4j 없이 `ManualCircuitBreaker` 클래스로 직접 구현.

**3가지 상태:**

```
CLOSED ──실패율 50% 이상──> OPEN ──10초 경과──> HALF_OPEN
   ↑                                              │
   └──2건 모두 성공──────────────────────────────────┤
                                                    └──1건이라도 실패──> OPEN
```

| 상태 | 동작 |
|---|---|
| **CLOSED** | 정상 상태, 모든 호출 허용. 슬라이딩 윈도우로 실패율 추적 |
| **OPEN** | 호출 차단, PG 호출 없이 즉시 fallback 반환 |
| **HALF_OPEN** | 제한된 호출만 허용(2건), 결과에 따라 CLOSED 복구 또는 OPEN 유지 |

**설정값:**

| 항목 | 값 | 설명 |
|---|---|---|
| 슬라이딩 윈도우 크기 | 10 | 최근 10건의 요청으로 실패율 계산 |
| 실패율 임계치 | 50% | 10건 중 5건 이상 실패 시 OPEN |
| OPEN 유지 시간 | 10초 | OPEN 후 10초 경과 시 HALF_OPEN 전이 |
| HALF_OPEN 허용 호출 | 2건 | 2건 모두 성공해야 CLOSED 복구 |

**서킷브레이커에 기록하지 않는 경우:**
- **타임아웃**: PG 도달 여부가 불확실하므로 실패로 기록하지 않음. 타임아웃을 실패로 기록하면 실제로는 PG가 정상인데 네트워크 지연만으로 서킷이 열릴 수 있음

**`ManualPgApiClient` 통합 흐름:**

```
requestPayment() 호출
  ├─ circuitBreaker.isCallPermitted()?
  │   ├─ NO (OPEN) → 즉시 fallback 반환 (PG 호출 안 함)
  │   └─ YES (CLOSED/HALF_OPEN) → PG 호출 (리트라이 포함)
  │       ├─ 성공 → circuitBreaker.recordSuccess()
  │       ├─ 실패 → circuitBreaker.recordFailure()
  │       └─ 타임아웃 → 기록 안 함
```

### 폴백

모든 장애 대응 패턴의 최종 단계. 타임아웃, 리트라이 소진, 서킷브레이커 OPEN 등 **어떤 이유로든 PG 호출이 실패했을 때 사용자에게 반환하는 안전한 응답**.

**폴백이 적용되는 시점:**

| 시점 | 폴백 내용 |
|---|---|
| 서킷브레이커 OPEN | `PgPaymentResponse.failed("서킷 브레이커 OPEN: PG 호출 차단")` |
| 타임아웃 | `PgPaymentResponse.timeout()` → Payment를 UNKNOWN 상태로 |
| 리트라이 소진 | 마지막 실패 응답 반환 → Payment를 FAILED 상태로 |
| PG 정상 응답이지만 거부 | PG 응답 그대로 전달 → Payment를 FAILED 상태로 |

**`ManualPaymentFacade`의 폴백 처리:**
- 모든 경우에 HTTP 500이 아닌 **200 + 결제 상태**를 반환
- 타임아웃 → UNKNOWN 상태로 저장 → 콜백/수동확인으로 복구
- 기타 실패 → FAILED 상태로 저장 → 사용자에게 실패 안내
- 핵심: PG 장애가 우리 시스템의 에러(500)로 전파되지 않음

---

# k6 부하 테스트 전략 (Resilience 검증)

## k6란?

Grafana Labs에서 만든 오픈소스 부하 테스트 도구. JavaScript로 스크립트를 작성하고 CLI로 실행한다.
- JUnit과 달리 **프로젝트 외부에서 독립적으로 실행**하는 도구
- `src/test`가 아닌 별도 디렉토리(`k6/`)에 `.js` 파일로 작성
- 실제 서버를 띄운 상태에서 HTTP 요청을 대량으로 보내 시스템의 Resilience를 검증

### 설치 (macOS)

```bash
brew install k6
```

### 디렉토리 구조

```
k6/
├── scripts/
│   ├── timeout-test.js             # Timeout 검증
│   ├── circuit-breaker-test.js     # Circuit Breaker 상태 전이 검증
│   ├── retry-test.js               # Retry 성공률 개선 검증
│   ├── fallback-test.js            # Fallback 응답 검증
│   └── resilience-full-test.js     # 종합 시나리오
```

---

## 테스트 대상 흐름

```
Client → [POST /api/v1/payments] → [PgApiClient] → [PG Simulator (localhost:8082)]
                                        ↓
                                Timeout / CircuitBreaker / Retry / Fallback
```

---

## 시나리오 1: Timeout 검증

**목적**: PG가 느려도 우리 서비스가 설정된 시간 내에 응답하는지 확인

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 50,
  duration: '1m',
  thresholds: {
    http_req_duration: ['p(95)<5000'],  // 95%가 5초 이내 응답
    http_req_failed: ['rate<0.5'],       // 실패율 50% 미만
  },
};

const BASE_URL = 'http://localhost:8080';

export default function () {
  const payload = JSON.stringify({
    orderId: `order-${__VU}-${__ITER}`,
    cardType: 'SAMSUNG',
    cardNo: '1234-5678-9814-1451',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Loopers-LoginId': 'testuser',
      'X-Loopers-LoginPw': 'password1!',
    },
  };

  const res = http.post(`${BASE_URL}/api/v1/payments`, payload, params);

  check(res, {
    'status is not 504': (r) => r.status !== 504,
    'response time < 5s': (r) => r.timings.duration < 5000,
    'no timeout hang': (r) => r.timings.duration < 10000,
  });

  sleep(1);
}
```

### 검증 포인트

| 지표 | 기대값 | 의미 |
|------|--------|------|
| p95 응답시간 | < timeout 설정값(3~5초) + α | PG 지연이 있어도 우리 서비스는 빠르게 응답 |
| 무한 대기 케이스 | 0건 | timeout이 정상 동작하여 끊김 |
| timeout 시 응답 | 200 (PENDING fallback) | 500이 아닌 정상 응답 |

---

## 시나리오 2: Circuit Breaker 검증

**목적**: PG 실패율이 임계치를 넘으면 circuit이 열리고, PG 호출 없이 즉시 fallback 응답하는지 확인

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const responseTime = new Trend('payment_response_time');

export const options = {
  stages: [
    { duration: '30s', target: 100 },  // Stage 1: ramp up → PG 실패 누적 → circuit OPEN
    { duration: '30s', target: 100 },  // Stage 2: circuit OPEN 유지 → 즉시 fallback 응답
    { duration: '30s', target: 10 },   // Stage 3: ramp down → HALF_OPEN → CLOSED 복구
  ],
};

const BASE_URL = 'http://localhost:8080';

export default function () {
  const payload = JSON.stringify({
    orderId: `order-${__VU}-${__ITER}-${Date.now()}`,
    cardType: 'SAMSUNG',
    cardNo: '1234-5678-9814-1451',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Loopers-LoginId': 'testuser',
      'X-Loopers-LoginPw': 'password1!',
    },
  };

  const res = http.post(`${BASE_URL}/api/v1/payments`, payload, params);

  responseTime.add(res.timings.duration);

  check(res, {
    'status is 200': (r) => r.status === 200,
    'not 500 error': (r) => r.status !== 500,
    'has response body': (r) => r.body.length > 0,
  });

  sleep(0.5);
}
```

### 검증 포인트

| 상태 | Stage | 기대 동작 |
|------|-------|-----------|
| **CLOSED** (정상) | Stage 1 초반 | PG 호출 O, 성공/실패 혼재, 응답시간 100~500ms |
| **OPEN** (차단) | Stage 1 후반 ~ Stage 2 | PG 호출 X, 즉시 fallback, **응답시간 급감** (< 50ms) |
| **HALF_OPEN** (시험) | Stage 3 | 일부만 PG 호출, 성공 시 CLOSED 복구 |

**핵심**: Stage 2의 응답시간이 Stage 1보다 현저히 빨라야 함 → circuit이 PG 호출을 건너뛰고 있다는 증거

---

## 시나리오 3: Retry 검증

**목적**: 일시적 실패 시 retry로 성공률이 개선되는지, 과도한 retry로 PG에 부하가 가중되지 않는지

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const paymentSuccess = new Rate('payment_success');

export const options = {
  vus: 10,
  duration: '2m',
  thresholds: {
    payment_success: ['rate>0.75'],  // retry 효과로 최종 성공률 75% 이상
  },
};

const BASE_URL = 'http://localhost:8080';

export default function () {
  const payload = JSON.stringify({
    orderId: `order-${__VU}-${__ITER}-${Date.now()}`,
    cardType: 'SAMSUNG',
    cardNo: '1234-5678-9814-1451',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Loopers-LoginId': 'testuser',
      'X-Loopers-LoginPw': 'password1!',
    },
  };

  const res = http.post(`${BASE_URL}/api/v1/payments`, payload, params);

  const body = JSON.parse(res.body);
  const isSuccess = res.status === 200 &&
    (body.data?.paymentStatus === 'PENDING' || body.data?.paymentStatus === 'REQUESTED');

  paymentSuccess.add(isSuccess);

  check(res, {
    'payment accepted': () => isSuccess,
  });

  sleep(1);
}
```

### 검증 포인트

| 지표 | retry 없음 | retry 적용 (max 3회) |
|------|-----------|-----------|
| PG 요청 성공률 | ~60% | ~60% (PG 자체는 변함없음) |
| 최종 결제 접수 성공률 | ~60% | ~84~94% (retry 효과) |
| 평균 응답시간 | 100~500ms | 약간 증가 (retry 대기 포함) |

> **수학적 근거**: PG 성공률 60%일 때
> - retry 1회: 0.6 + 0.4 x 0.6 = 84%
> - retry 2회: 0.6 + 0.4 x 0.6 + 0.16 x 0.6 = 93.6%

---

## 시나리오 4: Fallback 검증

**목적**: PG 장애 시에도 HTTP 500이 아닌 정상 응답(PENDING)을 반환하는지

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 200,          // 높은 동시성 → PG 실패 유도
  duration: '30s',
  thresholds: {
    'checks{name:not_500}': ['rate>0.99'],  // 500 에러가 1% 미만
  },
};

const BASE_URL = 'http://localhost:8080';

export default function () {
  const payload = JSON.stringify({
    orderId: `order-${__VU}-${__ITER}-${Date.now()}`,
    cardType: 'SAMSUNG',
    cardNo: '1234-5678-9814-1451',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Loopers-LoginId': 'testuser',
      'X-Loopers-LoginPw': 'password1!',
    },
  };

  const res = http.post(`${BASE_URL}/api/v1/payments`, payload, params);

  check(res, {
    'not_500': (r) => r.status !== 500,
    'status is 200': (r) => r.status === 200,
    'has payment status': (r) => {
      try {
        return JSON.parse(r.body).data?.paymentStatus !== undefined;
      } catch (e) {
        return false;
      }
    },
    'fallback returns valid status': (r) => {
      try {
        const status = JSON.parse(r.body).data?.paymentStatus;
        return ['PENDING', 'REQUESTED', 'FAILED'].includes(status);
      } catch (e) {
        return false;
      }
    },
  });

  sleep(0.2);
}
```

### 검증 포인트

| 지표 | 기대값 | 의미 |
|------|--------|------|
| HTTP 500 비율 | < 1% | PG 장애가 우리 서비스 에러로 전파되지 않음 |
| 응답에 paymentStatus 존재 | 99% 이상 | fallback이 정상 응답 구조를 반환 |
| DB에 주문/결제 데이터 존재 | 전건 저장 | PG 실패해도 내부 데이터는 보존 |

---

## 시나리오 5: 종합 Resilience 검증 (스파이크 테스트)

**목적**: 실제 운영과 유사한 트래픽 패턴에서 Timeout + CircuitBreaker + Retry + Fallback이 조화롭게 동작하는지

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const responseTime = new Trend('payment_response_time');
const successRate = new Rate('payment_success_rate');

export const options = {
  scenarios: {
    // 평상시 트래픽
    normal_load: {
      executor: 'constant-vus',
      vus: 20,
      duration: '2m',
    },
    // 30초 후 트래픽 급증 (스파이크)
    spike: {
      executor: 'ramping-vus',
      startTime: '30s',
      stages: [
        { duration: '10s', target: 200 },  // 급증
        { duration: '30s', target: 200 },  // 유지
        { duration: '10s', target: 20 },   // 복구
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(99)<10000'],     // 99%가 10초 이내
    http_req_failed: ['rate<0.3'],           // 전체 실패율 30% 미만
    payment_success_rate: ['rate>0.5'],      // 결제 접수 성공률 50% 이상
  },
};

const BASE_URL = 'http://localhost:8080';

export default function () {
  const payload = JSON.stringify({
    orderId: `order-${__VU}-${__ITER}-${Date.now()}`,
    cardType: 'SAMSUNG',
    cardNo: '1234-5678-9814-1451',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Loopers-LoginId': 'testuser',
      'X-Loopers-LoginPw': 'password1!',
    },
  };

  const res = http.post(`${BASE_URL}/api/v1/payments`, payload, params);

  responseTime.add(res.timings.duration);
  successRate.add(res.status === 200);

  check(res, {
    'not 500': (r) => r.status !== 500,
    'response under 10s': (r) => r.timings.duration < 10000,
  });

  sleep(0.5);
}
```

### 종합 검증 매트릭스

| 구간 | 기대 동작 |
|------|-----------|
| 평상시 (0~30초) | 정상 요청/응답, PG 성공률 ~60% |
| 스파이크 시작 (30~40초) | PG 실패 누적 → circuit OPEN 시작 |
| 스파이크 유지 (40~70초) | circuit OPEN → 즉시 fallback, 응답시간 급감 |
| 스파이크 감소 (70~80초) | HALF_OPEN → 일부 요청 시도 → CLOSED 복구 |
| 안정화 (80~120초) | 정상 상태 복귀 확인 |

---

## 실행 방법

```bash
# 1. 인프라 실행
docker-compose -f ./docker/infra-compose.yml up -d

# 2. PG Simulator 실행 (별도 Spring Boot App, localhost:8082)

# 3. Commerce API 실행
./gradlew :apps:commerce-api:bootRun

# 4. k6 테스트 실행
k6 run k6/scripts/timeout-test.js
k6 run k6/scripts/circuit-breaker-test.js
k6 run k6/scripts/retry-test.js
k6 run k6/scripts/fallback-test.js
k6 run k6/scripts/resilience-full-test.js
```

---

## Grafana 연동으로 실시간 관찰 (선택)

```bash
# InfluxDB + Grafana 활용
k6 run --out influxdb=http://localhost:8086/k6 k6/scripts/circuit-breaker-test.js
```

### 관찰해야 할 메트릭 (Prometheus + Grafana)

| 메트릭 | 의미 |
|--------|------|
| `resilience4j_circuitbreaker_state` | circuit 상태 전이 (closed/open/half_open) |
| `resilience4j_circuitbreaker_calls_seconds_count` | 성공/실패/차단 호출 수 |
| `resilience4j_retry_calls_total` | retry 횟수 |
| `http_server_requests_seconds_bucket` | API 응답시간 분포 |
| `hikaricp_connections_active` | DB 커넥션 사용량 (트랜잭션 분리 효과 확인) |

---

## 테스트 순서 추천

| 순서 | 테스트 | 검증 핵심 | 선행 조건 |
|------|--------|-----------|-----------|
| 1 | Timeout | PG 지연 시 우리 서비스가 멈추지 않는지 | 결제 API + Feign timeout 구현 |
| 2 | Fallback | 실패해도 500이 아닌 PENDING으로 응답하는지 | Fallback 메서드 구현 |
| 3 | Circuit Breaker | 연속 실패 시 PG 호출을 차단하고 즉시 응답하는지 | Resilience4j CB 설정 |
| 4 | Retry | 일시적 실패의 성공률을 개선하는지 | Resilience4j Retry 설정 |
| 5 | 종합 시나리오 | 스파이크 상황에서 전체가 조화롭게 동작하는지 | 위 1~4 완료 |

---

## JUnit 테스트와의 역할 분담

| 구분 | JUnit (src/test) | k6 (k6/scripts) |
|------|-------------------|------------------|
| **실행 환경** | 빌드 시 자동 실행 | 서버 띄운 후 수동 실행 |
| **검증 대상** | 단위 로직, 통합 동작, API 스펙 | 부하 상황에서의 시스템 동작 |
| **Resilience 검증** | CB/Retry 설정값이 적용되는지 | 실제 트래픽에서 CB가 열리고 복구되는지 |
| **사용 시점** | 개발 중 (Red-Green-Refactor) | 구현 완료 후 성능/안정성 검증 |

---

## Resilience4j 를 통한 구현

### 현재 상태 (V1)

| 패턴 | 적용 여부 |
|------|----------|
| Timeout | Feign 레벨 (connect: 1s, read: 3s) |
| CircuitBreaker | `@CircuitBreaker` on `PgApiClient.requestPayment()` |
| Fallback | CircuitBreaker의 fallbackMethod로 처리 |
| Retry | 미적용 |

### V2 제안: 4가지 패턴 조합 적용

#### 데코레이터 실행 순서

Resilience4j 데코레이터는 바깥에서 안으로 실행된다:

```
요청 → Retry → CircuitBreaker → TimeLimiter → 실제 PG 호출
                                                    ↓ (실패 시)
                                                 Fallback
```

- **Retry**: 최외곽. 실패 시 전체 흐름을 재시도
- **CircuitBreaker**: Retry 안쪽. 장애 누적 시 빠르게 차단
- **TimeLimiter**: 가장 안쪽. 개별 호출의 시간 제한
- **Fallback**: 모든 패턴이 실패했을 때 안전한 응답 반환

#### 이 순서가 지켜져야 하는 이유

데코레이터는 바깥쪽이 안쪽을 감싸는 구조이므로, 순서가 바뀌면 동작이 완전히 달라진다.

**각 위치의 근거:**

- **TimeLimiter가 가장 안쪽**: 개별 호출 1건의 시간만 제한한다
- **CB가 그 바깥**: TimeLimiter 타임아웃도 "실패"로 카운트하여 실패율을 집계하고, 임계치를 넘으면 OPEN 상태로 전환한다
- **Retry가 가장 바깥**: CB가 닫혀있는 상태에서 일시적 실패(타임아웃 등)가 발생하면 재시도한다

**순서가 잘못되면 생기는 문제:**

| 잘못된 순서 | 문제 |
|------------|------|
| CB → Retry → TimeLimiter | Retry가 CB 안쪽이므로 재시도 3번이 **1건의 CB 호출**로 집계됨. 실패율 통계가 왜곡 |
| TimeLimiter → CB → Retry | 타임아웃이 Retry 전체를 감싸서, 재시도 도중에 시간 초과로 전부 끊김 |
| Retry → TimeLimiter → CB | CB가 가장 안쪽이라 OPEN 상태여도 TimeLimiter가 먼저 3초를 기다림. 빠른 실패(fast-fail) 불가 |

#### 타임아웃(Timeout) vs TimeLimiter — 개념 구분

Resilience4j에는 별도의 "Timeout" 모듈이 없다. 시간 제한 기능은 **TimeLimiter가 유일**하다.

하지만 TimeLimiter는 일반적으로 말하는 "타임아웃"과는 다른 개념이다:

- **타임아웃(Timeout)**: 지정 시간 내 응답이 없으면 **연결을 끊는다** → 소켓/커넥션 레벨
- **TimeLimiter**: 지정 시간 내 결과가 안 오면 **기다리기를 포기한다** → Future 취소 레벨

비유하면:
- 타임아웃 = 전화를 **끊는** 것
- TimeLimiter = 전화는 그대로 두고 **듣기를 그만두는** 것

Resilience4j가 제공하는 핵심 모듈:

| 모듈 | 역할 |
|------|------|
| CircuitBreaker | 장애 감지 및 차단 |
| Retry | 재시도 |
| **TimeLimiter** | 시간 제한 (Resilience4j의 유일한 시간 제한 기능) |
| RateLimiter | 호출 빈도 제한 |
| Bulkhead | 동시 호출 수 제한 |

#### Feign Timeout vs Resilience4j TimeLimiter 비교

| 구분 | Feign Timeout (현재 적용) | Resilience4j TimeLimiter |
|------|-------------------------------|--------------------------|
| **레벨** | HTTP 클라이언트 (소켓) | 애플리케이션 로직 |
| **제어 대상** | TCP 연결 시간, 응답 대기 시간 | 메서드 실행 전체 시간 |
| **동작 방식** | 소켓 자체를 닫음 → 스레드 즉시 해제 | 별도 스레드에서 호출 후 시간 초과 시 Future 취소 |
| **CB 연동** | 별개로 동작 (예외를 CB가 실패로 인식은 함) | CB/Retry와 하나의 데코레이터 체인으로 동작 |

**TimeLimiter만 쓰고 Feign Timeout을 제거하면 안 되는 이유:**

TimeLimiter는 "기다리기를 포기"할 뿐, **실제 HTTP 연결을 끊지 못한다.**

```
TimeLimiter (3초 후 호출자에게 TimeoutException 반환)
  └─ 별도 스레드에서 Feign 호출 중...
      └─ 소켓 타임아웃 없음 → PG가 응답 안 하면 스레드가 영원히 블로킹
```

- 호출자는 3초 후 응답을 받지만, 뒷단 스레드는 PG 응답을 계속 기다리며 살아있음
- PG가 장애 상태면 블로킹 스레드가 계속 쌓임 → **스레드 풀 고갈 → 시스템 전체 장애**
- 따라서 **Feign Timeout은 반드시 유지**해야 하며, TimeLimiter는 부가적인 안전장치

**결론: V2 적용 전략 — 둘 다 적용**

| 타임아웃 | 역할 | 설정값 |
|---------|------|--------|
| Feign Timeout (connect: 1s, read: 3s) | 소켓 레벨에서 HTTP 연결을 확실히 끊음 (1차 방어) | connect: 1s, read: 3s |
| Resilience4j TimeLimiter | HTTP 외 지연(직렬화, GC 등) 포함 전체 실행 시간 제한 (2차 안전망) | 4s |

TimeLimiter의 `timeout-duration`은 Feign `readTimeout`보다 약간 길게 설정한다.
그렇지 않으면 소켓이 끊기기 전에 TimeLimiter가 먼저 취소해서 Feign Timeout이 무의미해진다.

```
Feign readTimeout: 3s  →  소켓 끊김 (1차 방어)
TimeLimiter timeout: 4s  →  소켓이 못 끊은 경우의 최종 안전망 (2차 방어)
```

V2에는 **Feign Timeout + TimeLimiter + Retry + CircuitBreaker + Fallback** 5가지를 모두 적용한다.

데코레이터 적용 순서:
```
요청 → Retry → CircuitBreaker → TimeLimiter → Feign(소켓 타임아웃) → 실제 PG 호출
                                                                          ↓ (모두 실패 시)
                                                                       Fallback
```

#### 구성 방법: 어노테이션 기반 + V1/V2 클래스 분리

V1의 `PgApiClient`는 기존 그대로 유지하고, V2 전용 `PgApiClientV2`를 새로 만든다.

```
PgApiClient   → V1용 (@CircuitBreaker + Fallback)         ← 기존 그대로
PgApiClientV2 → V2용 (@Retry + @CircuitBreaker + @TimeLimiter + Fallback)  ← 신규
```

```
PaymentV1Controller → PaymentFacade   → PgApiClient      (기존)
PaymentV2Controller → PaymentV2Facade → PgApiClientV2    (신규)
```

**PgApiClientV2 구조:**

```java
@Slf4j
@Component
public class PgApiClientV2 {

    private final PgFeignClient pgFeignClient;
    private final String callbackUrl;

    @Retry(name = "pgRetry")
    @CircuitBreaker(name = "pgCircuitV2")
    @TimeLimiter(name = "pgTimeout", fallbackMethod = "requestPaymentFallback")
    public CompletableFuture<PgPaymentResponse> requestPayment(Long memberId, PgPaymentRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            // Feign HTTP 호출 (PgFeignClient 사용)
            return pgFeignClient.requestPayment(String.valueOf(memberId), requestWithCallback);
        });
    }

    private CompletableFuture<PgPaymentResponse> requestPaymentFallback(
            Long memberId, PgPaymentRequest request, Throwable t) {
        log.warn("PG 결제 요청 실패 - fallback 실행. memberId={}, orderId={}, error={}",
                memberId, request.orderId(), t.getMessage());
        return CompletableFuture.completedFuture(PgPaymentResponse.failed("PG 시스템 장애: " + t.getMessage()));
    }
}
```

**이 구조의 장점:**
- 기존 V1 코드 수정 없음
- 구조가 단순 (클래스 1개 추가)
- 어노테이션 방식으로 선언적이고 간결
- V2에서 자유롭게 Resilience 전략 실험 가능

**어노테이션 실행 순서 설정 (application.yml):**

```yaml
resilience4j:
  # 숫자가 높을수록 바깥쪽 (먼저 실행)
  # Retry(바깥) → CircuitBreaker(중간) → TimeLimiter(안쪽)
  retry:
    retry-aspect-order: 2
  circuitbreaker:
    circuit-breaker-aspect-order: 1
  timelimiter:
    time-limiter-aspect-order: 0
```

#### application.yml 설정

```yaml
resilience4j:
  # 어노테이션 실행 순서
  retry:
    retry-aspect-order: 2
  circuitbreaker:
    circuit-breaker-aspect-order: 1
  timelimiter:
    time-limiter-aspect-order: 0

  circuitbreaker:
    instances:
      pgCircuit:          # V1용 (기존)
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 2
        slow-call-duration-threshold: 2s
        slow-call-rate-threshold: 50
      pgCircuitV2:        # V2용
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        slow-call-duration-threshold: 2s
        slow-call-rate-threshold: 50

  retry:
    instances:
      pgRetry:
        max-attempts: 3
        wait-duration: 500ms
        exponential-backoff-multiplier: 2  # 500ms → 1s → 2s
        retry-exceptions:
          - java.net.ConnectException                              # TCP 연결 실패 (PG가 요청을 못 받은 게 확실)
        ignore-exceptions:
          - java.net.SocketTimeoutException                        # 응답 대기 타임아웃 → PG가 받았을 수 있음
          - io.github.resilience4j.circuitbreaker.CallNotPermittedException

  timelimiter:
    instances:
      pgTimeout:
        timeout-duration: 4s
        cancel-running-future: true
```

#### Retry 대상 예외 — PG 시뮬레이터 스펙 기반 결정

PG 시뮬레이터는 **멱등성을 지원하지 않는다.**
- 같은 `orderId`로 2번 요청하면 2건의 별도 결제가 생성됨 (각각 다른 `transactionKey`)
- 따라서 "PG가 요청을 받았을 가능성이 있는 경우"는 재시도하면 이중 결제 위험

| 상황 | 재시도 | 이유 |
|------|--------|------|
| `ConnectException` (TCP 연결 실패) | **O** | PG가 요청을 아예 못 받음 |
| `SocketTimeoutException` (응답 대기 타임아웃) | **X** | PG가 요청을 받았을 수 있음 → 이중 결제 위험 |
| PG 명시적 거절 (카드 한도 초과 등) | X | 재시도해도 같은 결과 |
| `CallNotPermittedException` (CB OPEN) | X | PG 호출 자체가 차단됨, 재시도 무의미 |

참고: `PaymentService.initiatePayment()`에서 애플리케이션 레벨 멱등성 체크는 하고 있지만,
이는 Retry 전에 이미 DB에 REQUESTED 상태로 저장된 후이므로 PG 쪽 이중 결제를 막지 못한다.

#### 전체 흐름 (V2)

```
POST /api/v2/payments
  → PaymentV2Controller
    → PaymentV2Facade.processPayment()
      → [Tx1] PaymentService.initiatePayment()
      → PgApiClientV2.requestPayment()   ← @Retry + @CircuitBreaker + @TimeLimiter
          ├─ Retry (max 3회, 500ms/1s/2s backoff)
          │   ├─ CircuitBreaker (10건 중 50% 실패 → OPEN)
          │   │   ├─ TimeLimiter (4초 초과 → TimeoutException)
          │   │   │   └─ CompletableFuture.supplyAsync()
          │   │   │       └─ Feign HTTP 호출 (소켓 타임아웃 3s)
          │   │   │
          │   │   └─ CB OPEN → CallNotPermittedException (retry 안 함)
          │   │
          │   └─ ConnectException → 재시도 (PG가 못 받은 게 확실)
          │   └─ SocketTimeoutException → 재시도 안 함 (PG가 받았을 수 있음)
          │
          └─ 모두 실패 → Fallback (PgPaymentResponse.failed())
      → [Tx2] PaymentService.handlePgResponse()
```

#### 주의사항

- **타임아웃 후 복구**: 타임아웃으로 FAILED 처리했지만 PG에서 승인된 경우 → 콜백 또는 `/verify`로 복구
- **CB OPEN 시 즉시 실패**: `CallNotPermittedException`은 Retry의 `ignore-exceptions`에 등록하여 불필요한 재시도 방지
- **TimeLimiter > Feign**: TimeLimiter(4s) > Feign readTimeout(3s)으로 설정하여 소켓이 먼저 끊기도록 함
- **CompletableFuture**: `@TimeLimiter`는 `CompletableFuture` 반환 필수. Facade에서 `.get()` 또는 `.join()`으로 결과 추출

---

## 코드 리뷰 후 리팩토링

### 1. 전액 할인 시 고아 주문 방지

#### 문제

쿠폰 + 포인트로 전액 할인되면 `Order.getPaymentAmount() <= 0`이 된다.
이 경우 주문은 PENDING으로 이미 커밋됐는데, `Payment.create(amount=0)`에서 예외가 발생하여
**결제 레코드가 없는 고아 주문**이 남는 문제가 있었다.

```
[트랜잭션 1] placeOrder() → 주문 PENDING 커밋 완료
[트랜잭션 밖] processPayment() → Payment.create(amount=0) → 예외 발생!
→ 결제 없는 PENDING 주문이 DB에 남음
```

#### 해결

`OrderFacade.createOrder()`에서 결제 금액이 0 이하면 PG 호출 없이 바로 PAID 처리한다.

```java
if (orderInfo.paymentAmount() <= 0) {
    orderService.completeOrder(orderInfo.id());
} else {
    paymentFacade.processPayment(memberId, orderInfo.id(), cardType, cardNo);
}
```

---

### 2. E2E 테스트 검증 수정

#### 문제

`PaymentV2ApiE2ETest.marksRequestedAsFailed_whenPgQueryFails` 테스트가
"REQUESTED → FAILED 전환"을 검증한다고 하면서, 실제로는 PG 타임아웃으로 **UNKNOWN 상태**의 Payment를 만들고 있었다.
`markRequestedAsFailed()`는 REQUESTED 상태만 필터하므로 UNKNOWN 상태의 결제는 변경되지 않았고,
최종 Payment 상태도 DB에서 검증하지 않았다.

#### 해결

REQUESTED 상태의 Payment를 직접 DB에 생성하여 테스트 시나리오를 정확하게 만들고,
verify 호출 후 DB에서 FAILED 전환과 실패 사유까지 검증하도록 수정했다.

```java
// 변경 전: PG 타임아웃으로 UNKNOWN 상태 생성 (잘못된 시나리오)
when(pgFeignClient.requestPayment(...)).thenReturn(PgPaymentResponse.timeout());

// 변경 후: REQUESTED 상태를 직접 DB에 생성 (올바른 시나리오)
Payment requestedPayment = Payment.create(orderId, member.getId(), 50000, "SAMSUNG", "1234");
paymentRepository.save(requestedPayment);

// DB 검증 추가
Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
assertThat(payment.getFailureReason()).contains("PG 요청 미도달");
```

---

### 3. DIP(의존성 역전 원칙) 적용 — 어댑터 패턴

#### 문제

Application 레이어(`PaymentFacade`)가 Infrastructure 레이어(`PgApiClient`, `PgPaymentRequest`, `PgPaymentResponse` 등)를
직접 의존하고 있어 아키텍처 규칙을 위반하고 있었다.

```
변경 전: PaymentFacade (Application) → PgApiClient (Infrastructure)  // DIP 위반
```

#### 해결: 어댑터 패턴

Domain 레이어에 `PaymentGateway` 인터페이스를 정의하고, Infrastructure에서 어댑터로 구현한다.
기존 `PgApiClient`, `ManualPgApiClient`는 변경하지 않고 래핑한다.

```
변경 후:
  PaymentFacade (Application) → PaymentGateway (Domain)       // DIP 준수
  Resilience4jPaymentGateway (Infrastructure) → PgApiClient   // 어댑터가 래핑
```

#### 구조

```
domain/payment/
├── PaymentGateway.java               ← 인터페이스 (신규)
├── PaymentGatewayResponse.java       ← 도메인 응답 DTO (신규)
├── PaymentGatewayStatusResponse.java ← 도메인 상태 응답 DTO (신규)
├── PaymentService.java
└── Payment.java

infrastructure/payment/
├── Resilience4jPaymentGateway.java   ← 어댑터 (@Primary, 신규)
├── ManualPaymentGateway.java         ← 어댑터 (신규)
├── PgApiClient.java                  ← 기존 유지 (변경 없음)
├── ManualPgApiClient.java            ← 기존 유지 (변경 없음)
└── PgFeignClient.java

application/payment/
├── PaymentFacade.java                ← PaymentGateway 의존 (@Qualifier 주입)
└── ManualPaymentFacade.java          ← PaymentGateway 의존 (@Qualifier 주입)
```

#### 이점

1. **Application 레이어가 Infrastructure를 모름**: PG 구현체 교체 시 Facade 코드 변경 불필요
2. **callbackUrl 중복 설정 해소**: 기존에 Facade와 PgApiClient 양쪽에서 callbackUrl을 설정하던 중복이 어댑터 내부로 캡슐화
3. **테스트 단순화**: Facade 테스트에서 `PaymentGateway` 하나만 Mock하면 되며, PG 전용 DTO 불필요
4. **기존 코드 보존**: `PgApiClient`, `ManualPgApiClient`와 그 단위 테스트는 일절 변경 없음

---

## 주문 실패 보상(Compensation) 설계

### 문제

주문 생성 시 `OrderPlacementService.placeOrder()` 트랜잭션에서 다음 리소스를 선점한다:

1. 재고 차감 (비관적 락)
2. 쿠폰 사용 처리 (원자적 UPDATE)
3. 포인트 차감 (비관적 락)
4. 장바구니 삭제

이후 PG 결제가 **별도 트랜잭션**에서 진행되므로, 결제 실패 시 이미 커밋된 리소스를 수동으로 원복해야 한다.

기존에는 `OrderService.failOrderAndRestoreStock()`에서 **재고만 복원**하고, 쿠폰·포인트는 원복하지 않았다.

### 설계 선택지

| 선택지 | 장점 | 단점 |
|--------|------|------|
| 1. `failOrderAndRestoreStock()` 확장 | 변경 범위 작음 | OrderService가 쿠폰/포인트/장바구니 서비스에 전부 의존, 단일 메서드 책임 비대 |
| 2. **별도 보상 서비스** (선택) | 보상 로직 응집, 레이어 구조와 일관 | 클래스 1개 추가, 호출 단계 1단계 증가 |

**선택: 2번 — `OrderCompensationService`** (Application 레이어)

이유:
- 여러 도메인 서비스를 조율하는 보상 로직은 Application 레이어의 책임
- `OrderPlacementService`(주문 생성 조율)와 대칭 구조
- `OrderService`는 주문 본연의 책임만 유지

### 보상 대상

| 항목 | 보상 메서드 | 원자성 보장 방식 |
|------|------------|-----------------|
| 주문 상태 → FAILED | `OrderService.failOrder()` | JPA 변경 감지 |
| 재고 복원 | `ProductService.restoreStock()` | 비관적 락 |
| 쿠폰 사용 취소 | `CouponService.cancelUsedCoupon()` | `UPDATE ... WHERE status='USED'` 원자적 업데이트 |
| 포인트 환불 | `PointService.restorePoint()` | 비관적 락 + RESTORE 이력 기록 |
| 장바구니 복원 | 미구현 (Order에 출처 정보 미저장) | — |

### 호출 흐름

```
[결제 실패 감지]
   AbstractPaymentFacade.applyOrderStateChange()
      └─ case FAILED →
            OrderCompensationService.compensateFailedOrder(orderId)
               ├─ orderService.failOrder(orderId)          // 주문 FAILED 전이
               ├─ productService.restoreStock(...)          // 재고 복원 (주문 아이템별)
               ├─ couponService.cancelUsedCoupon(...)       // 쿠폰 USED→AVAILABLE (사용한 경우)
               └─ pointService.restorePoint(...)            // 포인트 환불 (사용한 경우)
```

### 변경 파일

**신규**
- `OrderCompensationService.java` — Application 레이어, 보상 조율
- `OrderCompensationServiceTest.java` — 단위 테스트

**도메인 변경**

| 파일 | 변경 |
|------|------|
| `PointType` | `RESTORE` enum 추가 |
| `PointHistory` | `createRestore()` 팩토리 메서드 |
| `PointService` | `restorePoint()` — 잔액 복원 + RESTORE 이력 |
| `MemberCouponRepository` | `updateStatusToAvailable(id)` 인터페이스 |
| `MemberCouponJpaRepository` | `UPDATE ... WHERE status='USED'` 원자적 쿼리 |
| `MemberCouponRepositoryImpl` | 구현 위임 |
| `CouponService` | `cancelUsedCoupon()` — USED→AVAILABLE 원복 |
| `OrderService` | `failOrderAndRestoreStock()` → `failOrder()` (Order 반환, 재고 로직 분리) |

**Facade 변경**

| 파일 | 변경 |
|------|------|
| `AbstractPaymentFacade` | `OrderCompensationService` 주입, FAILED 시 `compensateFailedOrder()` 호출 |
| `PaymentFacade` | 생성자에 `OrderCompensationService` 추가 |
| `ManualPaymentFacade` | 생성자에 `OrderCompensationService` 추가 |

### 장바구니 복원 미구현 사유

`Order` 엔티티에 "어떤 아이템이 장바구니에서 왔는지" 정보가 저장되지 않는다.
`productOptionIdsFromCart`는 `placeOrder()` 파라미터로만 전달되고, 트랜잭션 커밋 후에는 추적할 수 없다.
필요 시 `Order`에 장바구니 출처 정보를 저장하는 설계 변경이 선행되어야 한다.

---

## 주문+결제 도메인 설계 리뷰 및 보완

코드 리뷰를 통해 발견된 설계 결함을 보완한 내용을 정리한다.

### 1. 보상 경로 누락 보완

#### 1-1. OrderPaymentFacade 동기 흐름 보상 누락

**문제**

`OrderPaymentFacade.createOrder()` → `processPayment()` 경로에서 PG 호출 후 Payment가 FAILED로 마킹되더라도 보상이 호출되지 않았다.
`AbstractPaymentFacade.applyOrderStateChange()`는 콜백/verify 경로에서만 동작하며, 주문 생성 동기 흐름에서는 타지 않는다.

- 결과: 주문은 PENDING, 재고/쿠폰/포인트는 차감된 채로 방치

**수정**

`OrderPaymentFacade`에 `OrderCompensationService` 의존성을 추가하고,
`processPayment()` 끝에 Payment 상태를 조회하여 FAILED인 경우 즉시 보상을 실행한다.

```
OrderPaymentFacade.processPayment() {
    [TX1] initiatePayment()              → Payment REQUESTED 생성
    [TX 밖] PG API 호출                   → Resilience4j 적용
    [TX2] updatePaymentStatus()          → PENDING / UNKNOWN / FAILED
    [TX3] Payment 상태 확인 → FAILED이면
          compensateFailedOrder()        ← 추가 (재고/쿠폰/포인트 원복)
}
```

PENDING/UNKNOWN 상태는 PG 콜백 또는 verify API로 최종 결정되므로 이 시점에서 보상하지 않는다.

**변경 파일**

| 파일 | 변경 |
|------|------|
| `OrderPaymentFacade` | `OrderCompensationService` 주입, FAILED 시 보상 호출 |
| `OrderPaymentFacadeTest` | `OrderCompensationService` Mock 추가, 보상 호출/미호출 검증 테스트 |
| `OrderPaymentIntegrationTest` | PG 실패 시 기대값을 `PENDING` → `FAILED`로 수정, 재고 복원 검증 추가 |

#### 1-2. verifyPayment 시 REQUESTED→FAILED 보상 누락

**문제**

`AbstractPaymentFacade.verifyPayment()`에서 PG 조회 결과가 없을 때 `markRequestedAsFailed()`로 Payment만 FAILED 처리하고, Order 보상이 호출되지 않았다.

**수정**

`PaymentService.markRequestedAsFailed()` 반환 타입을 `void` → `boolean`으로 변경하여, Facade에서 실제 상태 전이 발생 여부에 따라 보상을 실행한다.

```
AbstractPaymentFacade.verifyPayment() {
    PG 조회 결과 없음 →
        markRequestedAsFailed(orderId) → true(상태 변경됨) →
            compensateFailedOrder(orderId)
}
```

| 반환값 | 의미 | 보상 실행 |
|--------|------|----------|
| `true` | REQUESTED → FAILED 전이 발생 | O |
| `false` | REQUESTED 아니거나 결제 없음 | X |

**변경 파일**

| 파일 | 변경 |
|------|------|
| `PaymentService` | `markRequestedAsFailed()` 반환 타입 `void` → `boolean` |
| `AbstractPaymentFacade` | 반환값 `true`일 때 `compensateFailedOrder()` 호출 |

### 2. Order 상태 전이 결함 수정

**문제**

`Order.validateNotFinalized()`에서 `PAID`와 `CANCELLED`만 최종 상태로 취급하여 `FAILED`에서 다른 상태로 전이가 가능했다.
보상 처리(`compensateFailedOrder` → `markFailed()`) 후 뒤늦게 PG 콜백이 SUCCESS로 들어오면 **FAILED → PAID로 뒤집힘**.
FAILED 주문은 이미 재고/쿠폰/포인트가 복원된 상태이므로 다시 PAID가 되면 데이터 정합성이 깨진다.

**수정**

`Order.validateNotFinalized()`에 `FAILED` 상태를 추가하여, 보상 완료 후 상태 전이를 차단한다.

```java
private void validateNotFinalized() {
    if (this.status == OrderStatus.PAID
            || this.status == OrderStatus.FAILED
            || this.status == OrderStatus.CANCELLED) {
        throw new CoreException(ErrorType.CONFLICT, "이미 처리 완료된 주문입니다.");
    }
}
```

**보상 경로별 정합성 보장 정리**

| 경로 | 보상 트리거 | Order 최종 상태 | 뒤늦은 콜백 방어 |
|------|-----------|----------------|----------------|
| `OrderPaymentFacade.processPayment()` | Payment FAILED 확인 → 즉시 보상 | FAILED | `validateNotFinalized()` 차단 |
| `AbstractPaymentFacade.handleCallback()` | `SyncResult.FAILED` → `applyOrderStateChange()` | FAILED | `validateNotFinalized()` 차단 |
| `AbstractPaymentFacade.verifyPayment()` | `markRequestedAsFailed()` 반환 true → 보상 | FAILED | `validateNotFinalized()` 차단 |

### 3. 도메인 입력 검증 강화

#### 3-1. Order.create() 검증 추가

**문제**: `discountAmount + usedPoints > totalAmount`인 경우 `getPaymentAmount()`가 음수를 반환할 수 있었다.

**수정**:
- 빈 주문 항목 검증: `items == null || items.isEmpty()` → BAD_REQUEST
- 할인 초과 검증: `discountAmount + usedPoints > totalAmount` → BAD_REQUEST

#### 3-2. Interfaces 계층 @Valid 적용

**문제**: 컨트롤러에서 입력 검증이 없어 빈 items, 음수 quantity, null 필드 등이 도메인까지 전파되었다.

**수정**:

| DTO | 검증 |
|-----|------|
| `OrderV1Dto.CreateOrderRequest` | `@NotEmpty items`, `@Valid items`, `@Min(0) usedPoints`, `@NotBlank cardType/cardNo` |
| `OrderV1Dto.OrderItemRequest` | `@NotNull productId/productOptionId`, `@Min(1) quantity` |
| `PaymentV1Dto.CreatePaymentRequest` | `@NotNull orderId`, `@NotBlank cardType/cardNo` |

컨트롤러 적용: `OrderV1Controller`, `PaymentV1Controller`, `PaymentV2Controller`에 `@Valid @RequestBody` 추가

### 4. 알려진 제한 사항

학습 프로젝트 범위에서 코드 수정 대신 문서화로 대체한 항목:

| 항목 | 현재 상태 | 실무 대응 |
|------|----------|----------|
| **PG 콜백 인증 부재** | `/callback` 엔드포인트에 인증 없음. 외부에서 위조 콜백 전송 가능 | HMAC 서명 검증, IP 화이트리스트, 시크릿 토큰 헤더 |
| **카드번호 평문 저장** | `Payment.cardNo`가 평문으로 DB에 저장됨 | PCI-DSS 준수: 마스킹(`****-****-****-1234`)만 저장, 원본은 PG에만 전달 |
| **상품 중복 조회** | `calculateApplicableAmount()`와 `prepareOrderItems()`에서 동일 상품 2회 조회 | 상품 정보를 미리 조회하여 Map으로 전달, N+1 해소 |
| **장바구니 복원 미구현** | `Order`에 장바구니 출처 정보 미저장으로 보상 시 복원 불가 | `Order`에 출처 정보를 저장하는 설계 변경 선행 필요 |
