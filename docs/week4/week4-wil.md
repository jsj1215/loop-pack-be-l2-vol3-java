# WIL - 4주차 (동시성 제어 & 트랜잭션 정합성)

## 🧠 이번 주에 새로 배운 것

- **낙관적 락과 비관적 락의 선택 기준**이 단순히 "충돌이 잦으면 비관적, 드물면 낙관적"이 아니라 **도메인의 특성과 트랜잭션 구조까지 고려해야 한다**는 걸 배웠다. 쿠폰 사용(`MemberCoupon`)은 AVAILABLE → USED 단방향 1회성 전환이라 충돌 시 재시도가 무의미하고, 같은 쿠폰으로 동시 주문하는 빈도가 극히 낮아서 낙관적 락(`@Version`)이 적합했다. 반면 재고(`ProductOption`)는 인기 상품일수록 경합이 높고 초과 판매가 치명적이라 비관적 락(`SELECT FOR UPDATE`)으로 확실하게 직렬화해야 했다. 포인트(`Point`)는 회원당 1 row라서 비관적 락을 걸어도 다른 회원에게 영향이 없어, 비관적 락의 단점(대기 오버헤드)이 사실상 발생하지 않는다는 점이 인상적이었다.

- **JPA 1차 캐시(영속성 컨텍스트)와 비관적 락의 상호작용**에서 심각한 버그를 경험했다. `Product`를 조회할 때 `ProductOption`이 함께 1차 캐시에 로드되고, 이후 `entityManager.find(ProductOption.class, id, PESSIMISTIC_WRITE)`를 호출하면 DB 레벨에서는 `SELECT FOR UPDATE`가 실행되지만 **이미 1차 캐시에 존재하는 엔티티는 DB에서 읽은 최신 값으로 갱신되지 않는다.** 결과적으로 락은 잡았지만 stale한 재고 값을 사용하게 되어 Lost Update가 발생했다. `refresh()`로 우회할 수도 있었지만, 근본 원인인 "PC에 옵션이 미리 로드되는 것"을 해결하기 위해 `findProductOnly()` 메서드를 도입하여 옵션 로드 없이 Product만 조회하도록 구조를 변경했다. **비관적 락을 쓸 때는 해당 엔티티가 이미 영속성 컨텍스트에 있는지 반드시 확인해야 한다**는 교훈을 얻었다.

- **Atomic UPDATE가 락보다 나은 경우**가 있다는 걸 좋아요 카운터 구현에서 배웠다. `product.incrementLikeCount()` + `save()`는 Read-Modify-Write 패턴이라 Lost Update에 취약한데, `UPDATE product SET like_count = like_count + 1 WHERE id = ?`로 바꾸면 DB가 원자적으로 처리하므로 락 자체가 불필요하다. 단순 카운터 증감에 행 잠금을 거는 건 과도한 오버헤드라는 걸 깨달았다.

- **장바구니의 Hard Delete + UPSERT 전환 과정**에서 설계 결정의 연쇄 효과를 체감했다. 원자적 UPSERT(`INSERT ... ON DUPLICATE KEY UPDATE`)를 적용하려면 유니크 제약조건이 필요한데, Soft Delete를 쓰면 삭제된 row와 충돌이 발생한다(MySQL은 partial unique index 미지원). 장바구니는 주문 완료 후 삭제되는 임시 데이터라 삭제 이력을 보존할 이유가 없어서 Hard Delete로 전환했다. "기술적 제약이 도메인 특성과 맞아떨어질 때 깔끔한 해결책이 나온다"는 걸 느꼈다.

## 💭 이런 고민이 있었어요

- **낙관적 락 예외를 어디서 어떻게 처리할지**가 가장 큰 고민이었다. 방안 1(`saveAndFlush` + Service 내부 catch)은 "쿠폰이 이미 사용되었습니다"라는 명확한 메시지를 줄 수 있지만, `flush()`가 영속성 컨텍스트 전체를 flush하면서 의도치 않은 다른 엔티티의 SQL도 실행되고 락 보유 시간도 증가하는 부작용이 있었다. 결국 `save()`로 쓰기 지연을 유지하고, 트랜잭션 커밋 시 발생하는 `OptimisticLockingFailureException`을 `ApiControllerAdvice`에서 409 CONFLICT로 처리하는 2단계 방식을 채택했다. 구체적인 비즈니스 메시지를 포기한 대신, `useCoupon()` 내부의 `isAvailable()` 검증이 1차 방어선 역할을 하고, `@Version` 충돌은 극소수 케이스의 2차 방어선이 되는 구조가 깔끔했다.

- **`@Transactional`을 Domain Service에서 제거하는 결정**이 3주차 고민의 연장선이었다. `CouponService.useCoupon()`과 `ProductService.deductStock()`에 `@Transactional(REQUIRED)`가 있었는데, 어차피 `OrderFacade`의 트랜잭션에 참여하므로 독립 트랜잭션이 아니었다. 오히려 "Service에 트랜잭션이 있으니까 Service 내부에서 예외를 잡을 수 있겠지?"라는 오해를 불러일으킬 수 있어서 제거했다. 트랜잭션 경계가 Facade에만 존재한다는 걸 명시적으로 드러내기 위해 Javadoc으로 "호출자가 트랜잭션을 보장해야 한다"고 명시했다.

- **주문 트랜잭션 내 실행 순서 최적화**에서 "쿠폰 검증을 재고 차감 전으로 옮기면 비관적 락 보유 시간을 줄일 수 있다"는 아이디어를 적용했는데, 이로 인해 `calculateApplicableAmount()`가 아직 생성되지 않은 `OrderItem` 대신 `ProductService.findProductOnly()`로 직접 상품 가격을 조회해야 했다. 최적화를 위해 코드 구조가 변경되는 trade-off를 직접 경험했다.

- **데드락 방지를 위한 락 획득 순서 일관화**는 이론으로만 알고 있었는데 실제로 적용해보니 간단했다. `prepareOrderItems()` 진입 시 `productOptionId` 오름차순으로 정렬하는 한 줄 추가로 해결됐다. 하지만 이걸 놓쳤으면 운영 중에 간헐적 데드락이 발생해서 디버깅하기 매우 어려웠을 것이다.

## 💡 앞으로 실무에 써먹을 수 있을 것 같은 포인트

- **비관적 락 사용 시 1차 캐시 상태를 반드시 확인하는 습관.** `entityManager.find(class, id, lockMode)`는 1차 캐시에 이미 존재하는 엔티티를 refresh하지 않는다. 락을 걸 엔티티가 이전에 로드되지 않았는지 확인하거나, 필요하면 `refresh()`를 사용해야 한다.

- **락 전략 선택 체크리스트**: 경합 빈도가 높고 실패가 치명적이면 비관적 락, 경합이 드물고 fail-fast가 적합하면 낙관적 락, 단순 카운터 증감이면 Atomic UPDATE, 유니크 제약이 가능하면 UPSERT. 하나의 트랜잭션 안에서 이 전략들을 혼합해도 원자성은 보장된다.

- **여러 행에 비관적 락을 거는 경우, 항상 일관된 순서(ID 오름차순 등)로 락을 획득하여 데드락을 방지.** 간단하지만 놓치기 쉬운 패턴이다.

- **Soft Delete와 유니크 제약의 충돌 문제.** MySQL은 partial unique index를 지원하지 않으므로, 유니크 제약이 필요한 테이블에서 Soft Delete를 쓰면 삭제된 row와 충돌한다. 도메인 특성상 삭제 이력이 불필요하면 Hard Delete가 더 깔끔한 해결책이 될 수 있다.

- **글로벌 예외 핸들러에서 락 관련 예외(`OptimisticLockingFailureException`, `PessimisticLockingFailureException`, `DataIntegrityViolationException`)를 409 CONFLICT로 매핑하는 패턴.** 500으로 빠지면 클라이언트가 재시도 판단을 할 수 없다.

## 🤔 아쉬웠던 점 & 다음 주에 해보고 싶은 것

- 비관적 락 1차 캐시 stale read 버그를 발견하기까지 동시성 테스트 없이는 알 수 없었을 것이다. 동시성 테스트를 "검증용"이 아니라 "설계 검증 도구"로 먼저 작성하는 습관을 들여야겠다.

- `OrderFacade.createOrder()`의 트랜잭션 범위가 쿠폰 검증 → 재고 차감 → 주문 생성 → 쿠폰 사용 → 포인트 사용 → 장바구니 삭제까지 꽤 넓다. 현재는 단일 DB라서 괜찮지만, 서비스가 분리되면 분산 트랜잭션이나 Saga 패턴이 필요할 텐데, 그때 이 구조를 어떻게 전환할 수 있을지 더 공부해보고 싶다.

- 낙관적 락 실패 시 글로벌 핸들러에서 "동시 요청으로 처리에 실패했습니다"라는 범용 메시지만 내려주는 게 아쉽다. 어떤 엔티티의 충돌인지 구분할 수 없어서 클라이언트 입장에서 사용자 안내가 애매하다. 커스텀 예외로 감싸서 도메인 맥락을 포함시키는 방법을 고민해봐야겠다.

- 동시성 테스트에서 `TransactionTemplate`을 직접 다루면서 테스트 코드가 꽤 장황해졌다. 반복되는 동시성 테스트 패턴(N개 스레드 실행 → 성공/실패 카운트 → 최종 상태 검증)을 유틸로 추출하면 가독성이 많이 좋아질 것 같다.
