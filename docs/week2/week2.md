# Week 2 - 고객 서비스 설계 기록

## 개요
이커머스 고객 서비스 기능(브랜드/상품 조회, 좋아요, 장바구니, 주문)에 대한 시퀀스 다이어그램, 클래스 다이어그램, ERD를 설계했다.
요구사항의 애매한 부분을 하나씩 짚어가며 의사결정을 진행했고, 그 과정을 아래에 기록한다.

---

## 설계 의사결정 기록

### 1. Controller 파라미터 검증 방식
- **문제**: 요구사항에 "Controller에서 브랜드 ID가 있는지 검증"이라고 명시되어 있음
- **논의**: `@PathVariable Long brandId`로 선언하면 Spring이 타입 변환 실패 시 자동으로 400을 반환. 기존 `ApiControllerAdvice`에 `MethodArgumentTypeMismatchException` 핸들러가 이미 존재
- **결정**: Controller에 if문 검증 로직을 쓰지 않고 **Spring 파라미터 바인딩에 위임**. 시퀀스 다이어그램에서 Controller 검증 분기 생략 (방식 A)

### 2. 상품 목록 조회 - 결과 0건 정책
- **문제**: 요구사항에 "데이터가 하나도 없다면 404 error"로 되어 있음
- **논의**: 목록 조회에서 결과 0건은 "리소스가 없다"가 아니라 "검색 결과가 없다"에 해당. 404와 의미가 다름
- **결정**: **200 + 빈 리스트** 반환. 404는 단건 조회에서 리소스가 존재하지 않을 때만 사용

### 3. 상품 목록 조회 - 검색 대상 필드
- **문제**: 요구사항에 검색어 파라미터가 있지만 어떤 필드를 대상으로 하는지 명시되지 않음
- **결정**: **상품명(name)만** 대상으로 검색

### 4. 좋아요 수 정렬 방식
- **문제**: `like_desc(좋아요수)` 정렬 시 좋아요 카운트를 어디서 가져올지
- **선택지**:
  - A: Product에 `likeCount` 비정규화 필드 → 정렬 성능 좋음
  - B: Like 테이블 COUNT 집계 → 정규화, 쿼리 비용 증가
- **결정**: **A (비정규화)**. 좋아요 등록/취소 시 likeCount를 증감

### 5. 검색 파라미터 전달 방식
- **문제**: keyword, sort, brandId 3개 파라미터를 레이어 간 어떻게 넘길지
- **선택지**:
  - A: `ProductSearchCondition` 조건 객체로 묶기 → 기존 `SignupCommand` 패턴과 일관
  - B: 개별 파라미터로 전달 → 시그니처가 길어짐
- **결정**: **A (조건 객체)**. `ProductSearchCondition` record 사용

### 6. 상품 상세 응답 범위
- **문제**: 상품 정보 조회 시 브랜드 정보를 함께 내려줄지
- **결정**: **상품 + 브랜드 정보 + 옵션 목록** 포함. 클라이언트가 별도 API 호출 없이 표시 가능

### 7. Product-Brand 관계 설계
- **문제**: Product 도메인 모델이 Brand를 직접 포함할지, brandId만 보유할지
- **선택지**:
  - A: Brand 직접 포함 → 조회 편의, Product→Brand 의존
  - B: brandId만 보유 → 도메인 간 독립, Facade에서 조합 필요
- **결정**: **A (Brand 직접 포함)**. 커머스에서 상품은 항상 브랜드에 속하므로 자연스러운 구조

### 8. 상품 옵션 구조
- **문제**: 장바구니 시퀀스에 "동일 상품코드에 옵션으로 추가"라는 요구사항
- **결정**: **Product - ProductOption 1:N 관계** 도입. 재고는 옵션 단위로 관리. 장바구니/주문도 옵션 단위

### 9. 좋아요 데이터 관리 방식
- **문제**: 좋아요 등록/취소를 어떤 방식으로 관리할지
- **선택지**:
  - A: 요구사항 원문대로 `LIKE_YN` 컬럼 사용
  - B: 기존 BaseEntity의 `deletedAt` 패턴 활용
- **결정**: **A (LIKE_YN 컬럼)**. 요구사항 원문 준수

### 10. 인증 처리 위치
- **문제**: 헤더로 받은 loginId/password를 어디서 memberId로 변환할지
- **결정**: **Facade에서** MemberService.authenticate() 호출. 기존 MemberFacade 패턴과 일관

### 11. 좋아요 등록 시 상품 존재 검증
- **문제**: 없는 상품에 좋아요 시 고아 데이터 발생 가능
- **선택지**:
  - A: LikeService에서 ProductRepository로 검증 → 서비스 간 의존 발생
  - B: DB FK 제약으로 처리 → 서비스 코드에 검증 없음
- **결정**: **A (서비스 레벨 검증)**. LikeService → ProductRepository 의존 수용

### 12. 좋아요 취소 시 데이터 없음 상태 코드
- **문제**: 요구사항에 "데이터가 없다면 500 error"로 되어 있지만, 500은 서버 내부 오류
- **선택지**:
  - A: 요구사항대로 500
  - B: 400 Bad Request — 클라이언트의 잘못된 요청
  - C: 404 Not Found
- **결정**: **B (400 Bad Request)**. "좋아요한 적 없는 상품입니다"

### 13. 장바구니 재고 부족 상태 코드
- **문제**: 요구사항에 "재고가 없으면 500 error"로 되어 있음
- **결정**: **400 Bad Request**. "재고가 부족합니다" (12번과 동일 맥락)

### 14. 장바구니 동일 상품 처리
- **문제**: 옵션 구조 도입으로 "동일 상품코드에 옵션으로 추가" 재해석 필요
- **결정**: 같은 상품+옵션 조합이면 **수량만 증가**. 합산 수량이 재고를 초과하면 400 에러

### 15. 주문 방식 (단건 vs 장바구니)
- **문제**: 단건 주문과 장바구니 주문을 어떻게 구분할지
- **결정**: **단일 엔드포인트** `POST /api/v1/orders`. body에 `cartItemIds[]` 또는 `productId+optionId+quantity`를 받아 분기

### 16. 결제 설계
- **문제**: 요구사항에 결제 → 잔액 부족 → 재고 복원 흐름이 있음
- **결정**: **결제 맥락 제거**. 주문 단계에서 재고 검증 → 차감 → 주문 생성으로 완료. 실패 시 @Transactional 롤백

### 17. 주문 스냅샷 필드
- **결정**: 상품명, 옵션명, 브랜드명, **판매가, 공급가, 배송비**, 수량

### 18. 주문 목록 조회 - 기간 파라미터
- **문제**: startAt, endAt이 필수인지 선택인지 명시되지 않음
- **결정**: **필수**. 없으면 400 Bad Request

### 19. 주문 목록/좋아요 목록 페이징
- **결정**: 둘 다 **페이징 적용**

### 20. 주문 상세 조회 - 타인 주문 접근 방지
- **문제**: 인증된 사용자 본인의 주문만 조회 가능해야 함
- **선택지**:
  - A: Service에서 `order.getMemberId() != memberId` 검증
  - B: Repository 쿼리에서 `findByIdAndMemberId(orderId, memberId)`
- **결정**: **B (Repository 쿼리)**. 한 번의 쿼리로 검증과 조회를 동시에 처리. 타인에게 주문 존재 여부조차 노출하지 않음

---

## 산출물

| 산출물 | 파일 |
|--------|------|
| 시퀀스 다이어그램 | `docs/design/02-sequence-diagrams.md` |
| 클래스 다이어그램 | `docs/design/03-class-diagram.md` |
| ERD | `docs/design/04-erd.md` |

---

## 잠재 리스크

1. **주문 트랜잭션 비대화**: 재고 차감 ~ 주문 저장 ~ 장바구니 삭제가 하나의 트랜잭션. 트래픽 증가 시 lock 범위가 넓어질 수 있음
2. **LikeService/CartService/OrderService → ProductRepository 의존**: 도메인 간 결합. 현재 규모에서는 수용하되, 도메인이 커지면 이벤트 기반 분리 고려
3. **like_count 비정규화**: 좋아요 등록/취소와 likeCount 증감의 정합성. 동시성 이슈 시 비관적 락 또는 별도 집계 방식 검토 필요

---

## 어드민 서비스 설계 의사결정 기록

### 21. LDAP 인증 설계 수준
- **문제**: 요구사항에 "LDAP 연동" 명시. 실제 LDAP 서버를 연동할지, 설계만 할지
- **결정**: **인터페이스 정의 + Fake 구현**. `X-Loopers-Ldap: loopers.admin` 헤더 값 검증만 수행

### 22. LDAP 인증 위치
- **문제**: Filter/Interceptor에서 공통 처리할지, Facade에서 개별 호출할지
- **결정**: **Facade에서 LdapAuthService.authenticate() 호출**. 고객 서비스의 MemberService.authenticate() 패턴과 일관

### 23. 어드민 컨트롤러 분리
- **결정**: **별도 컨트롤러** — `AdminBrandV1Controller`, `AdminProductV1Controller`. 고객 서비스와 명확히 분리

### 24. 브랜드명 중복 검증 (등록)
- **결정**: **중복 검증 적용**. 동일 브랜드명 존재 시 409 Conflict

### 25. 브랜드명 중복 검증 (수정)
- **결정**: **본인 제외 중복 검증**. `existsByNameAndIdNot(name, brandId)`로 다른 브랜드와의 이름 중복만 체크

### 26. 브랜드 삭제 시 연관 데이터 처리
- **문제**: 브랜드 삭제 → 상품 삭제 시 좋아요/장바구니를 어떻게 할지
- **결정**: **장바구니만 삭제 (hard delete), 좋아요는 유지**. 조회 시 삭제된 상품은 필터링

### 27. 삭제 방식
- **결정**: **Soft delete** (deletedAt 세팅). 기존 BaseEntity 패턴 활용. 장바구니만 hard delete

### 28. 상품 등록 시 옵션 처리
- **결정**: **상품 + 옵션 한 번에 등록**. body에 options[] 포함

### 29. 상품명 중복 검증
- **결정**: **같은 브랜드 내에서 중복 불가**. `existsByBrandIdAndName`

### 30. 상품 수정 시 브랜드 변경 방지
- **문제**: 요구사항에 "상품의 브랜드는 수정할 수 없다" 명시
- **선택지**: 무시 / 에러 반환 / DTO에서 제외
- **결정**: **DTO에서 brandId 필드 자체를 제외**. 원천 차단

### 31. 상품 수정 시 옵션 처리
- **결정**: **상품 + 옵션 한 번에 수정**. 옵션 추가/삭제/수정 모두 포함

### 32. 상품 삭제 시 연관 데이터 처리
- **결정**: **브랜드 삭제 (26)과 동일 정책**. 장바구니 hard delete, 좋아요 유지, 상품+옵션 soft delete

---

## 설계 리뷰 및 개선 기록

### 33. 시퀀스 다이어그램 - 메서드명을 한글 설명으로 변경
- **문제**: 시퀀스 다이어그램의 동작 설명이 `getBrand(brandId)`, `findById(brandId)`, `authenticate(loginId, password)` 등 메서드명으로 표기되어 있어 비개발자나 다른 팀원이 흐름을 직관적으로 이해하기 어려움
- **변경 내용**:
  - 요청 방향(→): `getBrand(brandId)` → `브랜드 조회 요청`, `authenticate(loginId, password)` → `회원 인증 요청` 등
  - 응답 방향(-->>): `Optional.empty()` → `조회 결과 없음`, `CoreException(NOT_FOUND)` → `브랜드 없음 예외`, `void` → `처리 완료` 등
  - 반환값: `Brand` → `브랜드 정보`, `Page(Product)` → `상품 목록` 등
- **유지한 항목**: API 엔드포인트(GET/POST/PUT/DELETE URL), HTTP 응답 코드, 설계 포인트 섹션
- **결정 이유**: 설계 문서는 개발자뿐 아니라 누구나 쉽게 읽을 수 있어야 함. 구현 단계에서 메서드명은 코드에서 확인 가능

### 34. ERD - 변경한 사람(Audit) 컬럼 추가
- **문제**: 기존 BaseEntity에 `created_at`, `updated_at`, `deleted_at`만 존재하여 "누가" 작업했는지 추적 불가
- **변경 내용**: 모든 테이블에 `created_by`, `updated_by`, `deleted_by` (varchar) 컬럼 추가
- **결정 이유**: 운영 환경에서 데이터 변경 이력 추적은 필수. 특히 어드민에서 브랜드/상품을 수정·삭제할 때 책임 소재 파악에 필요
- **BaseEntity 패턴 변경**: `(id, created_at, updated_at, deleted_at)` → `(id, created_at, created_by, updated_at, updated_by, deleted_at, deleted_by)`

### 35. ERD - 물리적 FK 제약조건 미사용
- **문제**: 실무에서는 물리적 FK 제약조건을 걸지 않는 경우가 많은데, 현재 ERD에 FK로 표기되어 있음
- **고민한 포인트**:
  - **성능**: INSERT/UPDATE/DELETE 시 참조 무결성 검증으로 인한 쓰기 성능 저하
  - **운영 유연성**: 스키마 변경, 데이터 마이그레이션 시 FK 순서 의존으로 작업 복잡도 증가
  - **확장성**: 향후 DB 분리(샤딩, MSA별 DB 분리) 시 물리 FK가 장벽이 됨
  - **Soft Delete 호환**: `deleted_at` 방식과 물리 FK 제약이 의미적으로 충돌 (삭제되지 않으므로 FK 검증이 무의미)
- **변경 내용**:
  - 모든 참조 컬럼에서 `FK` 표기 제거, `논리적 참조: 참조테이블명`으로 변경
  - 상단 설명에 "물리적 FK 제약조건은 사용하지 않으며, 참조 무결성은 애플리케이션 레벨(Service)에서 검증" 명시
- **결정**: 논리적 참조 관계만 유지하고 물리 FK 제약은 걸지 않음. 시퀀스 다이어그램에서 이미 Service 레이어에서 `존재 여부 확인 → 없으면 예외` 패턴으로 무결성을 보장하고 있음

### 36. Application Layer에 Service가 필요한가
- **문제**: 클래스 다이어그램에서 Application 레이어에 Facade만 존재하고 Service가 없음. Domain Service가 비즈니스 로직 + 여러 Repository 조합 오케스트레이션까지 담당하여 책임이 과한 것 아닌가
- **고민한 포인트**:
  - OrderService가 ProductRepository(재고검증·차감) + OrderRepository(주문저장) + CartRepository(장바구니삭제)를 한 곳에서 조합 → 유스케이스 오케스트레이션이지 순수 도메인 로직이 아님
  - ApplicationService를 도입하면 Domain Service는 자기 Repository만 의존하게 되어 단일 책임 원칙에 부합
- **결정**: **현행 유지**. 현재 규모에서는 Facade(인증+변환) + Domain Service(오케스트레이션+비즈니스 로직) 2단 구조로 충분. 레이어를 더 나누면 오히려 복잡도만 증가. 도메인이 커지거나 서비스 간 의존이 복잡해지면 그때 분리 검토

### 37. Domain 모델 간 ID 참조 누락 검토
- **문제**: ProductOption에 productId가 없어서 옵션을 독립적으로 조회할 때 소속 상품을 알 수 없음. OrderItem에도 orderId, productId가 없는 것이 문제인지 검토
- **검토 결과**:
  - **ProductOption에 productId 추가 — 적용**: `상품+옵션 ID로 조회` 유스케이스에서 옵션이 독립적으로 조회되므로 productId 필요
  - **OrderItem에 productId 추가 — 기각**: OrderItem은 스냅샷 테이블로 원본 상품과 독립적이어야 함 (설계 의사결정 17번). 원본 상품이 soft delete 되면 의미 없는 참조가 됨
  - **OrderItem에 orderId 추가 — 기각**: Order가 `List<OrderItem>`을 소유하는 Aggregate Root 패턴. OrderItem은 항상 Order를 통해 접근하므로 Domain 모델에 orderId 불필요. DB Entity 레이어에서는 JPA ManyToOne으로 order_id 컬럼이 존재하지만 이는 인프라 관심사
- **결정**: ProductOption에 productId만 추가. OrderItem은 기존 설계(스냅샷 독립성 + Aggregate Root 패턴) 유지

### 38. Domain 모델과 JPA Entity의 연관관계 맵핑 전략
- **문제**: Domain OrderItem에 orderId가 없는데, JPA가 Order-OrderItem 관계를 어떻게 맵핑하는가
- **정리**:
  - **Entity 레이어**: `OrderItemEntity`가 `@ManyToOne OrderEntity order`로 직접 참조. JPA가 `order_id` 컬럼을 생성하고 양방향 연관관계 관리
  - **Domain 레이어**: `OrderItem`에 orderId 없음. Order가 `List<OrderItem>`을 소유하는 단방향 구조
  - **변환 흐름**:
    - 저장 시: `Order(Domain) → OrderEntity` 변환 과정에서 OrderItemEntity에 OrderEntity 참조를 세팅
    - 조회 시: `OrderEntity → Order(Domain)` 변환 시 OrderItem에는 orderId 없이 순수 스냅샷 데이터만 담김
- **원칙**: `order_id` 맵핑은 Entity(인프라) 레이어의 책임. Domain 모델은 JPA에 의존하지 않는 순수 객체로 유지. ProductOption도 동일 원칙 — Entity에서는 `@ManyToOne ProductEntity product`, Domain에서는 `productId`만 보유

### 39. 목록 조회 빈 리스트 응답 메시지
- **문제**: 목록 조회 시 결과가 0건이면 빈 리스트만 반환하여 사용자에게 친절하지 않음
- **변경 내용**: 빈 리스트인 경우 `"조회된 내역이 없습니다."` 메시지를 반환하도록 시퀀스 다이어그램에 `alt` 분기 추가
- **적용 범위**: 고객 서비스 (2)상품 목록, (6)좋아요 상품 목록, (9)주문 목록 / 어드민 (1)브랜드 목록, (6)상품 목록 — 총 5개 목록 조회

### 40. 파라미터 형식 검증 위치 및 시퀀스 반영 여부
- **문제**: Path Variable 타입 검증, Body 필수 필드 누락, 숫자 범위 등 형식 검증이 시퀀스에 반영되어 있지 않음
- **논의**: Controller에서 `@Valid` + Bean Validation으로 처리하는 프레임워크 레벨 동작. 비즈니스 흐름이 아님
- **결정**: **시퀀스 다이어그램에 반영하지 않음**. (1) 설계 포인트의 "Spring 파라미터 바인딩에 위임" 수준으로 충분

### 41. Request DTO 클래스 다이어그램 반영 여부
- **문제**: `@Valid` 검증을 위해 Request DTO가 필요하지만, 클래스 다이어그램에 별도로 정의되어 있지 않음
- **논의**: Create/Update 분리는 오버엔지니어링. 별도 Request DTO 클래스를 다이어그램에 추가하는 것도 과함
- **결정**: **클래스 다이어그램에 반영하지 않음**. 구현 시 Interfaces 레이어에서 필요에 따라 정의

### 42. 상품 목록 조회 시 ProductSearchCondition 흐름 명시
- **문제**: 시퀀스 다이어그램에서 `ProductSearchCondition`이 어디서 생성되어 누가 넘기는지 보이지 않음
- **변경 내용**: 고객 서비스 (2) 상품 목록 조회 시퀀스에 `Note over Controller: query parameter → ProductSearchCondition 생성` 추가, 이후 Facade → Service → Repository로 전달 흐름 명시

### 43. Product 도메인에 status, displayYn 필드 추가
- **문제**: 어드민 상품 관리 시 상품의 상태(판매중/품절/판매중지)와 노출여부를 관리할 필드가 없음
- **변경 내용**:
  - Product 도메인 모델에 `ProductStatus status`, `String displayYn` 필드 추가
  - `ProductStatus` enum: `ON_SALE`, `SOLD_OUT`, `DISCONTINUED`
  - `displayYn`: `Y`(노출) / `N`(비노출) — status와 독립적인 별도 필드
  - ProductEntity에도 `status`, `displayYn` 필드 추가

### 44. 어드민 상품 검색 조건 설계
- **문제**: 어드민 상품 목록 조회가 brandId 필터링만 지원하여 관리 기능이 부족
- **검색 요구사항**: 상품 ID, 상품명(LIKE), 브랜드 ID, 상태값, 노출여부로 검색 가능해야 함
- **논의**: status, displayYn을 별도 파라미터로 분리할 필요 없이 searchType + searchValue 하나로 통일
- **변경 내용**:
  - `AdminProductSearchCondition` 추가: `searchType` + `searchValue`
  - `AdminProductSearchType` enum: `PRODUCT_ID`, `PRODUCT_NAME`, `BRAND_ID`, `STATUS`, `DISPLAY_YN`
  - 어드민 (6) 시퀀스에 Controller에서 `AdminProductSearchCondition` 생성 → 전달 흐름 반영
- **적용 범위**: 어드민 상품 목록 조회만 적용. 고객 서비스 상품 목록 조회는 기존 `ProductSearchCondition` 유지

### 45. Brand 상태값 추가
- **문제**: 브랜드의 운영 상태(대기/진행중/퇴점)를 관리할 필드가 없음
- **변경 내용**:
  - Brand 도메인 모델에 `BrandStatus status` 필드 추가
  - `BrandStatus` enum: `PENDING`(대기), `ACTIVE`(진행중), `WITHDRAWN`(퇴점)
  - BrandEntity, ERD에도 `status` 컬럼 반영
- **고객 서비스 영향**: 브랜드 조회 시 ACTIVE가 아니면 404 반환하여 비활성 브랜드 존재 여부 비노출

### 46. Product 마진유형 및 할인가 추가
- **문제**: 상품의 마진 산정 방식과 할인가를 관리할 필드가 없음
- **변경 내용**:
  - Product 도메인 모델에 `MarginType marginType`, `int discountPrice` 필드 추가
  - `MarginType` enum: `AMOUNT`(마진액), `RATE`(마진율)
  - 상품 등록 시 `supplyPrice`는 입력받지 않고, `price` + `marginType` + `marginValue`로 자동 계산 (Service 책임)
    - AMOUNT: `supplyPrice = price - marginValue`
    - RATE: `supplyPrice = price - (price × marginRate / 100)`
  - `discountPrice`: 할인가 단일 필드
  - ERD, ProductEntity, 어드민 (8) 상품 등록 시퀀스에 반영

### 47. PRODUCT_LIKE에서 삭제 관련 필드 제거
- **문제**: PRODUCT_LIKE는 LIKE_YN으로 상태를 관리하므로 soft delete가 불필요
- **변경 내용**:
  - ERD: `deleted_at`, `deleted_by` 컬럼 제거
  - 클래스 다이어그램: LikeEntity의 BaseEntity 상속 제거, `id`, `createdAt`, `updatedAt`만 직접 보유

### 48. Member 상태값 추가
- **문제**: 회원의 활동 상태(사용중/탈퇴)를 관리할 필드가 없음
- **변경 내용**: ERD MEMBER 테이블에 `status` 컬럼 추가 — `ACTIVE`(사용중), `WITHDRAWN`(탈퇴)
- **클래스 다이어그램**: Member는 week1 산출물 영역이므로 ERD에만 반영

### 49. ORDER_ITEM에 product_id 추가 (의사결정 37번 변경)
- **기존 결정(37번)**: OrderItem에 productId 추가 기각 — 스냅샷 독립성 유지
- **재검토**: 물리 FK를 사용하지 않기로 했으므로(35번), 참고용 product_id가 있어도 스냅샷 독립성에 영향 없음
- **변경 내용**: ORDER_ITEM에 `product_id` 컬럼 추가 (논리적 참조, 물리 FK 없음)
- **활용 목적**: 통계/분석, 어드민에서 상품별 주문 조회, 원본 상품 링크
- **원칙 유지**: 스냅샷 필드(product_name 등)는 그대로 유지. product_id는 참고용일 뿐, 조회 시 원본 상품을 join하지 않음

### 50. 인증 처리 위치 변경 — Facade → Interceptor/ArgumentResolver (의사결정 10번, 22번 변경)
- **기존 결정(10번, 22번)**: Facade에서 MemberService.authenticate() / LdapAuthService.authenticate() 호출
- **피드백**: 모든 Facade 메서드에 인증 코드가 중복되고, Facade가 '누가 요청했는가'라는 인프라적 관심사까지 관여
- **개선 방향**: 인증은 Interfaces 레이어의 Interceptor + ArgumentResolver에서 처리하여 Controller가 인증된 객체를 파라미터로 받는 구조
- **변경 내용**:
  - 고객 서비스: `MemberAuthInterceptor` + `@LoginMember Member` — `/api/v1/**` URL 패턴에 적용
  - 어드민 서비스: `AdminAuthInterceptor` + `@LoginAdmin Admin` — `/api-admin/v1/**` URL 패턴에 적용
  - Facade에서 MemberService/LdapAuthService 의존 제거
  - Facade 파라미터: `(String loginId, String password, ...)` → `(Member member, ...)`
  - 어드민 Facade 파라미터: `(String ldapHeader, ...)` → 인증 파라미터 제거 (비즈니스 파라미터만)
  - 인증 불필요한 API(브랜드 조회, 상품 목록 등)는 Interceptor 적용 제외
- **영향 범위**: 시퀀스 다이어그램 17개, 클래스 다이어그램 Facade 시그니처 전체
- **효과**: Facade는 순수하게 서비스 조율에만 집중, 인증 코드 중복 제거

---

