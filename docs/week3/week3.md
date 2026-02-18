## 도메인 & 객체 설계 전략
- 도메인 객체는 비즈니스 규칙을 캡슐화해야 합니다.
- 애플리케이션 서비스는 서로 다른 도메인을 조립해, 도메인 로직을 조정하여 기능을 제공해야 합니다.
- 규칙이 여러 서비스에 나타나면 도메인 객체에 속할 가능성이 높습니다.
- 각 기능에 대한 책임과 결합도에 대해 개발자의 의도를 확인하고 개발을 진행합니다.

## 아키텍처, 패키지 구성 전략
- 본 프로젝트는 레이어드 아키텍처를 따르며, DIP (의존성 역전 원칙) 을 반드시 준수합니다. (DIP에 위배될시 Fail 됩니다.)
- API request, response DTO와 응용 레이어의 DTO는 분리해 작성하도록 합니다.
- 패키징 전략은 4개 레이어 패키지를 두고, 하위에 도메인 별로 패키징하는 형태로 작성합니다.
    - 예시
      > /interfaces/api (presentation 레이어 - API)
      /application/.. (application 레이어 - 도메인 레이어를 조합해 사용 가능한 기능을 제공)
      /domain/.. (domain 레이어 - 도메인 객체 및 엔티티, Repository 인터페이스가 위치)
      /infrastructure/.. (infrastructure 레이어 - JPA, Redis 등을 활용해 Repository 구현체를 제공)
      > 
 
 
### 구현 과제

- 상품, 브랜드, 좋아요, 주문 기능의 **도메인 모델 및 도메인 서비스**를 구현합니다.
- 도메인 간 협력 흐름을 설계하고, 필요한 로직을 **도메인 서비스**로 분리합니다.
- Application Layer에서 도메인 객체를 조합하는 흐름을 구현합니다.
  (예: `ProductFacade.getProductDetail(productId)` → `Product + Brand + Like 조합`)
- Repository Interface 와 구현체는 분리하고, 테스트 가능성을 고려한 구조를 설계합니다.
- 모든 핵심 도메인 로직에 대해 단위 테스트를 작성하고, 예외/경계 케이스도 포함합니다.

---

### 설계 문서 요약 (구현 필수 사항)

#### 1. API 스펙 요약

**대고객 API (`/api/v1`)**
- 인증: `MemberAuthInterceptor` + `@LoginMember` ArgumentResolver (헤더: `X-Loopers-LoginId`, `X-Loopers-LoginPw`)

| 기능 | URI | Method | 인증 | 핵심 로직 |
|------|-----|--------|------|----------|
| 브랜드 정보 조회 | `/api/v1/brands/{brandId}` | GET | X | ACTIVE 상태만 조회, 비활성은 404 |
| 상품 목록 조회 | `/api/v1/products` | GET | X | 페이징 필수, 정렬(latest/price_asc/like_desc), 브랜드 필터, keyword 검색 |
| 상품 상세 조회 | `/api/v1/products/{productId}` | GET | X | 브랜드+옵션 포함 응답 |
| 좋아요 등록 | `/api/v1/products/{productId}/likes` | POST | O | 없으면 Insert, LIKE_YN='N'이면 'Y'로 Update, 이미 'Y'면 멱등 |
| 좋아요 취소 | `/api/v1/products/{productId}/likes` | PUT | O | 없으면 400, LIKE_YN='Y'면 'N'으로, 이미 'N'이면 멱등 |
| 좋아요 상품 목록 | `/api/v1/users/{userId}/likes` | GET | O | URI의 userId 무시, 인증된 memberId로 조회 |
| 장바구니 담기 | `/api/v1/carts` | POST | O | 재고 검증, 동일 옵션 있으면 수량 합산 (합산>재고 시 400) |
| 주문 요청 | `/api/v1/orders` | POST | O | 재고 검증→차감→쿠폰 검증/할인→스냅샷 생성→장바구니 삭제, @Transactional 롤백 |
| 주문 목록 조회 | `/api/v1/orders?startAt=&endAt=` | GET | O | startAt/endAt 필수(없으면 400), 페이징 |
| 주문 상세 조회 | `/api/v1/orders/{orderId}` | GET | O | findById 후 `Order.validateOwner()` — 미존재 404, 본인 아니면 403 |
| 쿠폰 목록 조회 | `/api/v1/coupons` | GET | X | 유효기간 내 + 수량 남은 쿠폰 목록 |
| 쿠폰 다운로드 | `/api/v1/coupons/{couponId}/download` | POST | O | 중복 방지(409), 수량 제한 |
| 내 쿠폰 목록 | `/api/v1/coupons/me` | GET | O | AVAILABLE 상태 쿠폰 |

**어드민 API (`/api-admin/v1`)**
- 인증: `AdminAuthInterceptor` + `@LoginAdmin` ArgumentResolver (헤더: `X-Loopers-Ldap: loopers.admin`)
- `LdapAuthService` 인터페이스 + `FakeLdapAuthService` 구현

| 기능 | URI | Method | 핵심 로직 |
|------|-----|--------|----------|
| 브랜드 목록 조회 | `/api-admin/v1/brands` | GET | 전체 브랜드 페이징 조회 |
| 브랜드 상세 조회 | `/api-admin/v1/brands/{brandId}` | GET | 상태 무관 전체 조회 |
| 브랜드 등록 | `/api-admin/v1/brands` | POST | 브랜드명 중복 시 409, 201 Created 반환 |
| 브랜드 수정 | `/api-admin/v1/brands/{brandId}` | PUT | 본인 제외 브랜드명 중복 검증 |
| 브랜드 삭제 | `/api-admin/v1/brands/{brandId}` | DELETE | 소속 상품+장바구니 삭제 전파 (브랜드/상품: soft, 장바구니: hard) |
| 상품 목록 조회 | `/api-admin/v1/products` | GET | searchType(PRODUCT_ID/PRODUCT_NAME/BRAND_ID/STATUS/DISPLAY_YN) + searchValue |
| 상품 상세 조회 | `/api-admin/v1/products/{productId}` | GET | 브랜드+옵션 포함 |
| 상품 등록 | `/api-admin/v1/products` | POST | 브랜드 존재 확인→상품명 중복 확인→공급가 자동 계산→저장, 201 Created |
| 상품 수정 | `/api-admin/v1/products/{productId}` | PUT | brandId 수정 불가, 옵션 일괄 교체 |
| 상품 삭제 | `/api-admin/v1/products/{productId}` | DELETE | 장바구니 hard delete, 상품+옵션 soft delete |
| 포인트 지급 | `/api-admin/v1/points` | POST | 회원에게 포인트 충전, 0 이하 금액 400, 회원 없음 404 |
| 쿠폰 생성 | `/api-admin/v1/coupons` | POST | scope/target 검증, 201 Created |
| 쿠폰 목록 조회 | `/api-admin/v1/coupons` | GET | 전체 쿠폰 페이징 |
| 쿠폰 상세 조회 | `/api-admin/v1/coupons/{couponId}` | GET | 발급 현황 포함 |

#### 2. 도메인 모델 핵심 필드 및 로직

**Brand**: id, name, description, status(PENDING/ACTIVE/WITHDRAWN)

**Product**: id, brand(ManyToOne), name, price, supplyPrice, discountPrice, shippingFee, likeCount(비정규화), description, marginType(AMOUNT/RATE), status(ON_SALE/SOLD_OUT/DISCONTINUED), displayYn(Y/N), options
- `calculateSupplyPrice()`: AMOUNT → price - marginValue, RATE → price - (price × marginRate / 100)
- `incrementLikeCount()`, `decrementLikeCount()`

**ProductOption**: id, productId, optionName, stockQuantity
- `hasEnoughStock(quantity)`, `deductStock(quantity)`, `restoreStock(quantity)` — 재고는 옵션 단위 관리

**Like**: id, memberId, productId, likeYn(Y/N) — BaseEntity 미상속, soft delete 아님
- `like()`, `unlike()`, `isLiked()`, `create()`

**CartItem**: id, memberId, productOptionId, quantity
- `addQuantity(quantity)` — 동일 옵션 병합

**Order**: id, memberId, orderItems, totalAmount, discountAmount, memberCouponId, usedPoints
- `create()`, `calculateTotalAmount()`, `getPaymentAmount()`, `validateOwner(memberId)` — 본인 주문이 아니면 403 Forbidden
- 실결제금액 = totalAmount - discountAmount - usedPoints

**OrderItem**: id, brandId, productName, optionName, brandName, price, supplyPrice, shippingFee, quantity — 스냅샷 테이블
- `createSnapshot(Product, ProductOption, quantity)`, `getSubtotal()` — brandId 포함 스냅샷

**Point**: id, memberId, balance
- `create(memberId, initialBalance)`, `charge(amount)`, `use(amount)` — 잔액 부족 시 예외

**PointHistory**: id, memberId, type(CHARGE/USE), amount, balanceAfter, description, orderId
- `createCharge()`, `createUse()` — 정적 팩토리로 충전/사용 이력 구분

**Coupon**: id, name, couponScope(PRODUCT/BRAND/CART), targetId, discountType(FIXED_AMOUNT/FIXED_RATE), discountValue, minOrderAmount, maxDiscountAmount, totalQuantity, issuedQuantity, validFrom, validTo
- `create()`, `withId()` — 정적 팩토리 메서드
- `isIssuable()` — 수량 + 유효기간 확인
- `isValid()` — 유효기간 확인
- `issue()` — issuedQuantity 증가, 불가 시 예외
- `calculateDiscount(applicableAmount)` — 할인 금액 계산 (FIXED_RATE 시 maxDiscountAmount 상한 적용)

**MemberCoupon**: id, memberId, couponId, status(AVAILABLE/USED), orderId, usedAt
- `create(memberId, couponId)` — status=AVAILABLE로 생성
- `use(orderId)` — AVAILABLE → USED 전환, 이미 사용 시 예외
- `isAvailable()` — 사용 가능 여부 확인

#### 3. 서비스 의존 관계

| Service | 의존하는 Repository |
|---------|-------------------|
| BrandService | BrandRepository |
| ProductService | ProductRepository (+ BrandRepository: 상품 등록 시) |
| LikeService | LikeRepository, ProductRepository (상품 존재 검증) |
| CartService | CartRepository, ProductRepository (재고 검증) |
| OrderService | OrderRepository, ProductRepository (재고 검증/차감), CartRepository (장바구니 삭제) |
| PointService | PointRepository, PointHistoryRepository |
| CouponService | CouponRepository, MemberCouponRepository |

**Facade 크로스 도메인 조율**

| Facade | 의존하는 Service |
|--------|-----------------|
| MemberFacade | MemberService, PointService (회원가입 시 초기 포인트 지급, MyInfo에 포인트 잔액 포함) |
| OrderFacade | OrderService, CartService, PointService, CouponService (주문 시 쿠폰 검증/적용 + 포인트 사용) |
| CouponFacade | CouponService (대고객 쿠폰 조회/다운로드) |
| AdminCouponFacade | CouponService (어드민 쿠폰 생성/조회) |
| AdminPointFacade | PointService (어드민 포인트 지급) |

#### 4. 공통 정책

- **빈 목록 조회**: 200 OK + "조회된 내역이 없습니다." (404 아님)
- **물리 FK 미사용**: 참조 무결성은 애플리케이션 레벨에서 검증
- **Soft Delete**: BaseEntity의 `deletedAt` 활용 (PRODUCT_LIKE 제외)
- **BaseEntity 공통 필드**: id, created_at, created_by, updated_at, updated_by, deleted_at, deleted_by
- **어드민/고객 Service 공유**: BrandService, ProductService는 공유, Facade/Controller만 분리
- **Facade 역할**: 인증 무관, 서비스 조율 + Domain→Info 변환만 담당
- **인가 전략 (A+B 조합)**:
  - 목록 조회(주문 목록, 좋아요 목록, 장바구니): 쿼리에서 memberId 필터링 (A방식)
  - 단건 리소스 접근(주문 상세): 도메인 객체의 `validateOwner(memberId)`로 명시적 인가 검증 (B방식, 403 Forbidden)

#### 5. ERD 테이블 및 인덱스

| 테이블 | 권장 인덱스 |
|--------|-----------|
| PRODUCT_LIKE | `member_id + product_id` (UNIQUE), `member_id + like_yn` |
| CART_ITEM | `member_id + product_option_id` (UNIQUE) |
| ORDERS | `member_id + created_at` (기간별 조회) |
| PRODUCT | `brand_id`, `name`, `status`, `display_yn` |
| POINT | `member_id` (UNIQUE) |
| POINT_HISTORY | `member_id + created_at` |
| COUPON | `coupon_scope + target_id`, `valid_from + valid_to` |
| MEMBER_COUPON | `member_id + coupon_id` (UNIQUE), `member_id + status` |



## Checklist

### Product / Brand 도메인

- [ ]  상품 정보 객체는 브랜드 정보, 좋아요 수를 포함한다.
- [ ]  상품의 정렬 조건(`latest`, `price_asc`, `likes_desc`) 을 고려한 조회 기능을 설계했다
- [ ]  상품은 재고를 가지고 있고, 주문 시 차감할 수 있어야 한다
- [ ]  재고의 음수 방지 처리는 도메인 레벨에서 처리된다

### Like 도메인

- [ ]  좋아요는 유저와 상품 간의 관계로 별도 도메인으로 분리했다
- [ ]  상품의 좋아요 수는 상품 상세/목록 조회에서 함께 제공된다
- [ ]  단위 테스트에서 좋아요 등록/취소 흐름을 검증했다

### Order 도메인

- [ ]  주문은 여러 상품을 포함할 수 있으며, 각 상품의 수량을 명시한다
- [ ]  주문 시 상품의 재고 차감, 유저 포인트 차감 등을 수행한다
- [ ]  재고 부족, 포인트 부족 등 예외 흐름을 고려해 설계되었다
- [ ]  단위 테스트에서 정상 주문 / 예외 주문 흐름을 모두 검증했다

### Coupon 도메인

- [ ]  쿠폰 적용 범위(PRODUCT/BRAND/CART)에 따른 할인 금액 계산 로직이 구현되었다
- [ ]  정액(FIXED_AMOUNT)/정률(FIXED_RATE) 할인이 올바르게 계산된다
- [ ]  정률 쿠폰의 최대 할인 금액 제한이 적용된다
- [ ]  최소 주문 금액 조건 검증이 구현되었다
- [ ]  쿠폰 다운로드 시 수량 제한 및 중복 방지가 구현되었다
- [ ]  쿠폰 유효기간 검증이 구현되었다
- [ ]  주문당 쿠폰 1장만 사용 가능하다
- [ ]  실결제금액 = totalAmount - discountAmount - usedPoints 공식이 적용된다
- [ ]  단위 테스트에서 쿠폰 할인 계산 / 다운로드 / 사용 흐름을 검증했다

### 도메인 서비스

- [ ]  도메인 내부 규칙은 Domain Service에 위치시켰다
- [ ]  상품 상세 조회 시 Product + Brand 정보 조합은 Application Layer 에서 처리했다
- [ ]  복합 유스케이스는 Application Layer에 존재하고, 도메인 로직은 위임되었다
- [ ]  도메인 서비스는 상태 없이, 동일한 도메인 경계 내의 도메인 객체의 협력 중심으로 설계되었다

### **소프트웨어 아키텍처 & 설계**

- [ ]  전체 프로젝트의 구성은 아래 아키텍처를 기반으로 구성되었다
    - Application → **Domain** ← Infrastructure
- [ ]  Application Layer는 도메인 객체를 조합해 흐름을 orchestration 했다
- [ ]  핵심 비즈니스 로직은 Entity, VO, Domain Service 에 위치한다
- [ ]  Repository Interface는 Domain Layer 에 정의되고, 구현체는 Infra에 위치한다
- [ ]  패키지는 계층 + 도메인 기준으로 구성되었다 (`/domain/order`, `/application/like` 등)
- [ ]  테스트는 외부 의존성을 분리하고, Fake/Stub 등을 사용해 단위 테스트가 가능하게 구성되었다