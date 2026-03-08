# WIL - 3주차 (도메인 모델 구현 & 크로스 도메인 트랜잭션 설계)

## 🧠 이번 주에 새로 배운 것

- `@Transactional`이 단순한 어노테이션이 아니라 **아키텍처적 의사결정**이라는 걸 체감했다. 처음엔 습관적으로 모든 Domain Service에 `@Transactional`을 붙였는데, 이것이 DIP 위반이라는 걸 깨닫고 전부 Facade로 올렸다. `org.springframework.transaction.annotation.Transactional`은 Spring 프레임워크 클래스이므로 Domain 레이어가 이것에 의존하면 고수준 모듈이 저수준 모듈에 의존하는 구조가 된다. "트랜잭션을 언제 열고 닫을 것인가"는 비즈니스 규칙이 아니라 유스케이스 조율자의 책임이라는 원칙이 명확해졌다.

- 크로스 도메인 트랜잭션에서 **왜 Facade에 트랜잭션 경계가 있어야 하는지** 주문 기능을 구현하면서 직접 겪었다. 각 Service가 독립 트랜잭션을 가지면 `orderService.prepareOrderItems()` → 커밋 → `pointService.usePoint()` → 실패 시, 재고는 차감됐는데 주문은 반쯤 생성된 상태가 된다. Facade에서 하나의 `@Transactional`로 묶으니 전체 롤백이 보장됐다.

- Domain Model과 JPA Entity를 분리하는 것이 항상 정답은 아니라는 걸 배웠다. 2주차에 "Domain의 OrderItem에는 orderId가 없지만 Entity에서는 `@ManyToOne`으로 order를 참조한다"며 분리의 의미를 이해했는데, 막상 12개 도메인을 구현하니 `from()`/`toXxx()` 변환 보일러플레이트가 모든 Repository에 생기고, 필드 하나 추가할 때마다 3곳을 동시에 수정해야 했다. 결국 프로젝트에 이미 존재하던 `ExampleModel` 패턴(Domain = JPA Entity)을 따라 통합했는데, Repository 인터페이스는 Domain에, 구현체는 Infrastructure에 유지하므로 DIP 자체는 그대로 지켜진다.

- N+1 쿼리 문제를 해결하면서 **fetch join이 만능이 아니라는 것**을 알게 됐다. 컬렉션 조인에 fetch join을 쓰면 페이징이 깨지거나 `MultipleBagFetchException`이 발생할 수 있다. IN절 배치 로딩 + Application 단 조립 방식이 쿼리도 단순하고 `default_batch_fetch_size` 설정과도 일관된다는 걸 실습으로 확인했다.

- JPA의 `save()` 내부 동작(`persist` vs `merge`)이 테스트에서 실제 버그를 일으킬 수 있다는 걸 경험했다. `BaseEntity`의 `id = 0L` 초기값 때문에 JPA가 기존 엔티티로 판단해 `merge()`를 호출하고, 원본 객체의 id는 0L인 채로 남아 있었다. `save()`의 반환값을 사용해야 한다는 게 단순한 코딩 습관이 아니라 JPA의 영속성 컨텍스트 동작 원리에 기반한 필수 패턴이라는 걸 깨달았다.

## 💭 이런 고민이 있었어요

- **Facade에서 비즈니스 로직이 자꾸 자라나는 문제**가 있었다. 주문 생성 시 쿠폰 할인 계산 로직(`calculateCouponDiscount`)을 처음에 OrderFacade에 private 메서드로 작성했는데, 쿠폰 소유 검증 / 사용 가능 여부 / 유효기간 / 최소 주문 금액 검증까지 들어가니 이건 "조율"이 아니라 "비즈니스 로직"이었다. CouponService로 옮기니 Facade는 조율만 남고, 테스트 책임도 명확해졌다. 다만 `calculateApplicableAmount`(scope별 적용 대상 금액 산출)은 주문 아이템 정보가 필요해서 Facade에 유지했는데, 이걸 어디에 둘지는 여전히 고민이다. CouponService에 넣으면 쿠폰 도메인이 주문 도메인에 의존하게 되니까.

- **Domain Service에서 @Transactional을 빼고 나니 통합 테스트가 전부 깨졌다.** 통합 테스트는 Facade를 거치지 않고 Service를 직접 호출하는데, 트랜잭션이 없으니 JPA 변경 감지도 안 되고 `TransactionRequiredException`이 터졌다. 결국 통합 테스트 클래스에 `@Transactional`을 추가해서 테스트 메서드 자체가 트랜잭션 경계가 되도록 했다. "설계 원칙을 바꾸면 테스트 전략도 함께 바뀌어야 한다"는 걸 놓치고 있었다.

- **BaseEntity의 `id` 필드가 `final`이라 테스트에서 리플렉션으로 ID를 설정할 수 없는 문제**를 만났다. Java 21에서는 `final` 필드에 대한 리플렉션 변경이 차단되어서 `ReflectionTestUtils.setField()`가 동작하지 않았다. `final`을 제거하는 게 맞는 건지 고민했지만, `BaseEntity`는 JPA가 관리하는 영속 객체의 기반 클래스이므로 id를 불변으로 강제할 이유가 없다고 판단했다.

- **E2E 테스트에서 RestTemplate의 URL 이중 인코딩 문제**가 생각보다 디버깅하기 어려웠다. `URLEncoder.encode()`로 타임존 오프셋의 `+`를 `%2B`로 변환했는데, `testRestTemplate.exchange(String url, ...)`이 이걸 URI 템플릿으로 다시 처리하면서 `%`가 `%25`로 이중 인코딩됐다. 서버에서는 400 BAD_REQUEST만 내려와서 원인을 찾는 데 시간이 걸렸다. URI 템플릿 변수(`{startAt}`)를 사용하면 RestTemplate이 인코딩을 알아서 처리한다는 걸 알게 됐다.

- **`findAll().stream().filter()` 패턴의 위험성**을 코드 리뷰 중에 발견했다. 브랜드 삭제 시 소속 상품을 찾기 위해 전체 상품 테이블을 메모리에 로딩한 뒤 Java에서 `brandId`로 필터링하고 있었다. 데이터가 적을 때는 동작하지만, 상품이 수만 건이 되면 OOM 위험이 있다. QueryDSL WHERE절로 DB에서 필터링하도록 바꿨다.

## 💡 앞으로 실무에 써먹을 수 있을 것 같은 포인트

- **트랜잭션 경계는 유스케이스 단위(Facade/Application Service)에서 관리**하고, Domain Service는 순수하게 유지하는 패턴. 크로스 도메인 작업에서 부분 커밋 위험을 원천 차단할 수 있다.

- **`@Transactional(readOnly = true)`를 클래스 레벨에, 쓰기 메서드에만 `@Transactional` 오버라이드**하는 전략. 읽기/쓰기 트랜잭션을 명시적으로 구분할 수 있고, 실수로 트랜잭션을 빠뜨리는 것도 방지된다.

- **N+1 해결 시 fetch join 대신 IN절 배치 + Application 단 조립**이 페이징과 함께 쓸 때 더 안전하다. `default_batch_fetch_size` 설정과 일관된 방향이기도 하다.

- **JPA `save()`의 반환값을 반드시 사용**하는 습관. `persist`와 `merge`의 동작 차이 때문에 원본 객체와 반환 객체의 상태가 다를 수 있다. 특히 `id = 0L` 같은 초기값이 있으면 `merge()`로 분기되므로 주의해야 한다.

- **"이 로직은 조율인가, 비즈니스 규칙인가?"를 기준으로 Facade와 Service의 책임을 나누는 원칙**. Facade에 private 메서드가 늘어나기 시작하면, 그건 비즈니스 로직이 잘못된 곳에 있다는 신호다.

- **테스트에서 URLEncoder를 직접 쓰지 말고 URI 템플릿 변수를 활용**하면 이중 인코딩 문제를 원천 차단할 수 있다.

## 🤔 아쉬웠던 점 & 다음 주에 해보고 싶은 것

- QueryDSL 기반 동적 검색 쿼리(상품 목록 조회의 정렬/필터/키워드 검색)를 아직 TODO로 남겨둔 것이 아쉽다. 현재 플레이스홀더 상태인데, 다음에는 BooleanBuilder나 BooleanExpression을 활용한 동적 쿼리 패턴을 적용해보고 싶다.

- 2주차에 고민했던 주문 트랜잭션 범위 문제를 이번 주에 직접 구현해보니, 단일 `@Transactional`로 묶는 것까지는 구현했지만 동시성 이슈(동시 주문 시 재고 차감 경합)는 아직 미해결이다. 비관적 락이나 낙관적 락을 적용해보고 성능 차이를 비교해보고 싶다.

- Domain Model에 JPA 어노테이션을 통합하면서 편의성은 높아졌지만, "Domain 레이어가 JPA에 의존하는 게 정말 괜찮은가?"라는 찝찝함이 남아있다. 프로젝트 규모가 커지면 다시 분리해야 할 수도 있는데, 그 시점을 어떻게 판단해야 할지 더 공부해봐야겠다.

- 설계 단계에서 "인증은 Interceptor로 분리"까지 결정했으면서도, 구현 단계에서 @Transactional 위치나 Facade 책임 분리 같은 새로운 설계 이슈가 계속 나왔다. 설계와 구현 사이에는 항상 갭이 존재하고, 그 갭을 메우는 과정에서 진짜 배움이 일어난다는 걸 느꼈다. 다음에는 구현하면서 발견한 설계 변경을 더 체계적으로 기록하는 습관을 들여야겠다.
