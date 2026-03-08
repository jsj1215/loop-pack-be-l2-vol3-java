## 도메인 & 객체 설계 전략
- 도메인 객체는 비즈니스 규칙을 캡슐화해야 합니다.
- 애플리케이션 서비스는 서로 다른 도메인을 조립해, 도메인 로직을 조정하여 기능을 제공해야 합니다.
- 규칙이 여러 서비스에 나타나면 도메인 객체에 속할 가능성이 높습니다.
- 각 기능에 대한 책임과 결합도에 대해 개발자의 의도를 확인하고 개발을 진행합니다.

## 아키텍처, 패키지 구성 전략
- 본 프로젝트는 레이어드 아키텍처를 따르며, DIP (의존성 역전 원칙) 을 반드시 준수합니다. (중요)
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
| 주문 요청 | `/api/v1/orders` | POST | O | 재고 검증→차감→쿠폰 검증/할인→포인트 차감→스냅샷 생성→장바구니 삭제, @Transactional 롤백 |
| 주문 목록 조회 | `/api/v1/orders?startAt=&endAt=` | GET | O | startAt/endAt 필수(없으면 400), 페이징 |
| 주문 상세 조회 | `/api/v1/orders/{orderId}` | GET | O | findById 후 `Order.validateOwner()` — 미존재 또는 본인 아니면 404 |
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
- `create()`, `calculateTotalAmount()`, `getPaymentAmount()`, `validateOwner(memberId)` — 본인 주문이 아니면 404 Not Found
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
| ProductService | ProductRepository |
| LikeService | LikeRepository, ProductRepository (상품 존재 검증) |
| CartService | CartRepository, ProductRepository (재고 검증) |
| OrderService | OrderRepository, ProductRepository (재고 검증/차감) |
| PointService | PointRepository, PointHistoryRepository |
| CouponService | CouponRepository, MemberCouponRepository |

**Facade 크로스 도메인 조율**

| Facade | 의존하는 Service |
|--------|-----------------|
| MemberFacade | MemberService, PointService (회원가입 시 초기 포인트 지급, MyInfo에 포인트 잔액 포함) |
| OrderFacade | OrderService, CartService, PointService, CouponService (주문 시 재고 검증/차감 + 쿠폰 검증/적용 + 포인트 사용 + 장바구니 삭제) |
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
  - 단건 리소스 접근(주문 상세): 도메인 객체의 `validateOwner(memberId)`로 명시적 인가 검증 (B방식, 404 Not Found — 리소스 존재 여부 비노출)

#### 5. ERD 테이블 및 인덱스

| 테이블 | 권장 인덱스 |
|--------|-----------|
| BRAND | `status` (어드민 상태별 조회) |
| PRODUCT | `brand_id`, `name`, `status`, `display_yn` |
| PRODUCT_LIKE | `member_id + product_id` (UNIQUE), `member_id + like_yn` |
| CART_ITEM | `member_id + product_option_id` (UNIQUE) |
| ORDERS | `member_id + created_at` (기간별 조회) |
| ORDER_ITEM | `order_id` (주문 상세 조회) |
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

- [ ]  좋아요는 유저와 상품 간의 관계로 별도 도메인으로 분리했다지
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

### 작업내역

#### Phase 0: Admin Auth 인프라

| 레이어 | 파일 | 설명 |
|--------|------|------|
| Domain | `domain/auth/Admin.java` | Admin record (`ldapId`) |
| Domain | `domain/auth/LdapAuthService.java` | LDAP 인증 인터페이스 |
| Infrastructure | `infrastructure/auth/FakeLdapAuthService.java` | `"loopers.admin"` 검증 Fake 구현 |
| Interfaces | `interfaces/api/auth/LoginAdmin.java` | `@Target(PARAMETER)` 어노테이션 |
| Interfaces | `interfaces/api/auth/AdminAuthInterceptor.java` | `X-Loopers-Ldap` 헤더 검증 |
| Interfaces | `interfaces/api/auth/LoginAdminArgumentResolver.java` | `@LoginAdmin Admin` 파라미터 리졸버 |
| Config | `config/WebMvcConfig.java` | Admin 인터셉터 등록 (`/api-admin/v1/**`) |
| Test | `interfaces/api/auth/AdminAuthInterceptorTest.java` | 인터셉터 단위 테스트 |
| Test | `infrastructure/auth/FakeLdapAuthServiceTest.java` | Fake 서비스 단위 테스트 |

#### Phase 1: Brand 도메인

| 레이어 | 파일 | 설명 |
|--------|------|------|
| Domain | `domain/brand/Brand.java` | 브랜드 도메인 모델 (status=PENDING 기본값) |
| Domain | `domain/brand/BrandStatus.java` | PENDING / ACTIVE / WITHDRAWN enum |
| Domain | `domain/brand/BrandRepository.java` | Repository 인터페이스 (DIP) |
| Domain | `domain/brand/BrandService.java` | register, findById, findActiveBrand, update, softDelete, findAll |
| Application | `application/brand/BrandInfo.java` | Application DTO (record) |
| Application | `application/brand/BrandFacade.java` | 대고객: findActiveBrand → BrandInfo |
| Application | `application/brand/AdminBrandFacade.java` | 어드민: CRUD + cascade delete |
| Infrastructure | `infrastructure/brand/BrandEntity.java` | JPA Entity (BaseEntity 상속) |
| Infrastructure | `infrastructure/brand/BrandJpaRepository.java` | Spring Data JPA |
| Infrastructure | `infrastructure/brand/BrandRepositoryImpl.java` | Repository 구현체 |
| Interfaces | `interfaces/api/brand/BrandV1Controller.java` | GET /api/v1/brands/{brandId} |
| Interfaces | `interfaces/api/brand/AdminBrandV1Controller.java` | GET/POST/PUT/DELETE /api-admin/v1/brands |
| Interfaces | `interfaces/api/brand/dto/BrandV1Dto.java` | 요청/응답 DTO |

#### Phase 2: Product/ProductOption 도메인

| 레이어 | 파일 | 설명 |
|--------|------|------|
| Domain | `domain/product/Product.java` | 상품 도메인 (calculateSupplyPrice, likeCount 관리) |
| Domain | `domain/product/ProductOption.java` | 상품 옵션 (재고: deductStock/restoreStock) |
| Domain | `domain/product/MarginType.java` | AMOUNT / RATE enum |
| Domain | `domain/product/ProductStatus.java` | ON_SALE / SOLD_OUT / DISCONTINUED |
| Domain | `domain/product/ProductSortType.java` | latest / price_asc / likes_desc |
| Domain | `domain/product/ProductSearchCondition.java` | 대고객 검색 조건 (keyword, sort, brandId) |
| Domain | `domain/product/AdminProductSearchType.java` | 어드민 검색 타입 enum |
| Domain | `domain/product/AdminProductSearchCondition.java` | 어드민 검색 조건 |
| Domain | `domain/product/ProductRepository.java` | Repository 인터페이스 |
| Domain | `domain/product/ProductService.java` | register, findById, search, update, softDelete, deductStock, restoreStock |
| Application | `application/product/ProductInfo.java` | 목록용 DTO |
| Application | `application/product/ProductDetailInfo.java` | 상세용 DTO (옵션 포함) |
| Application | `application/product/ProductOptionInfo.java` | 옵션 DTO |
| Application | `application/product/ProductFacade.java` | 대고객: 검색, 상세, 좋아요 |
| Application | `application/product/AdminProductFacade.java` | 어드민: CRUD + cascade delete |
| Infrastructure | `infrastructure/product/ProductEntity.java` | JPA Entity (ManyToOne BrandEntity) |
| Infrastructure | `infrastructure/product/ProductOptionEntity.java` | JPA Entity |
| Infrastructure | `infrastructure/product/ProductJpaRepository.java` | Spring Data JPA |
| Infrastructure | `infrastructure/product/ProductOptionJpaRepository.java` | Spring Data JPA |
| Infrastructure | `infrastructure/product/ProductRepositoryImpl.java` | Repository 구현체 |
| Interfaces | `interfaces/api/product/ProductV1Controller.java` | GET products, GET detail, POST/PUT likes |
| Interfaces | `interfaces/api/product/AdminProductV1Controller.java` | GET/POST/PUT/DELETE /api-admin/v1/products |
| Interfaces | `interfaces/api/product/dto/ProductV1Dto.java` | 요청/응답 DTO |

#### Phase 3: Like 도메인

| 레이어 | 파일 | 설명 |
|--------|------|------|
| Domain | `domain/like/Like.java` | 좋아요 도메인 (likeYn Y/N, 멱등성) |
| Domain | `domain/like/LikeRepository.java` | Repository 인터페이스 |
| Domain | `domain/like/LikeService.java` | like, unlike, getLikedProducts |
| Infrastructure | `infrastructure/like/LikeEntity.java` | JPA Entity (BaseEntity 미상속) |
| Infrastructure | `infrastructure/like/LikeJpaRepository.java` | Spring Data JPA |
| Infrastructure | `infrastructure/like/LikeRepositoryImpl.java` | Repository 구현체 |

#### Phase 4: CartItem 도메인

| 레이어 | 파일 | 설명 |
|--------|------|------|
| Domain | `domain/cart/CartItem.java` | 장바구니 도메인 (addQuantity 수량 병합) |
| Domain | `domain/cart/CartRepository.java` | Repository 인터페이스 |
| Domain | `domain/cart/CartService.java` | addToCart (재고 검증, 기존 병합), deleteByProductOptionIds, deleteByBrandId |
| Application | `application/cart/CartFacade.java` | addToCart 위임 |
| Infrastructure | `infrastructure/cart/CartItemEntity.java` | JPA Entity |
| Infrastructure | `infrastructure/cart/CartJpaRepository.java` | Spring Data JPA (bulk soft-delete @Query) |
| Infrastructure | `infrastructure/cart/CartRepositoryImpl.java` | Repository 구현체 |
| Interfaces | `interfaces/api/cart/CartV1Controller.java` | POST /api/v1/cart (201 Created) |
| Interfaces | `interfaces/api/cart/dto/CartV1Dto.java` | 요청/응답 DTO |

#### Phase 5: Point/PointHistory 도메인

| 레이어 | 파일 | 설명 |
|--------|------|------|
| Domain | `domain/point/Point.java` | 포인트 도메인 (charge, use, 잔액 부족 예외) |
| Domain | `domain/point/PointHistory.java` | 포인트 이력 (CHARGE/USE) |
| Domain | `domain/point/PointType.java` | CHARGE / USE enum |
| Domain | `domain/point/PointRepository.java` | Repository 인터페이스 |
| Domain | `domain/point/PointHistoryRepository.java` | Repository 인터페이스 |
| Domain | `domain/point/PointService.java` | createPoint, chargePoint, usePoint, getBalance |
| Application | `application/point/AdminPointFacade.java` | 어드민: 포인트 충전 위임 |
| Infrastructure | `infrastructure/point/PointEntity.java`, `PointHistoryEntity.java` | JPA Entity |
| Infrastructure | `infrastructure/point/PointJpaRepository.java`, `PointHistoryJpaRepository.java` | Spring Data JPA |
| Infrastructure | `infrastructure/point/PointRepositoryImpl.java`, `PointHistoryRepositoryImpl.java` | Repository 구현체 |
| Interfaces | `interfaces/api/point/AdminPointV1Controller.java` | POST /api-admin/v1/points |
| Interfaces | `interfaces/api/point/dto/PointV1Dto.java` | 요청 DTO |

**MemberFacade 연동:** signup 시 `pointService.createPoint()`, getMyInfo에 `pointService.getBalance()` 추가

#### Phase 6: Coupon/MemberCoupon 도메인

| 레이어 | 파일 | 설명 |
|--------|------|------|
| Domain | `domain/coupon/Coupon.java` | 쿠폰 도메인 (isIssuable, issue, calculateDiscount) |
| Domain | `domain/coupon/MemberCoupon.java` | 회원 쿠폰 (AVAILABLE/USED, use) |
| Domain | `domain/coupon/CouponScope.java` | PRODUCT / BRAND / CART enum |
| Domain | `domain/coupon/DiscountType.java` | FIXED_AMOUNT / FIXED_RATE enum |
| Domain | `domain/coupon/MemberCouponStatus.java` | AVAILABLE / USED enum |
| Domain | `domain/coupon/CouponRepository.java`, `MemberCouponRepository.java` | Repository 인터페이스 |
| Domain | `domain/coupon/CouponService.java` | createCoupon, downloadCoupon, useCoupon, findAvailableCoupons, findMyCoupons |
| Application | `application/coupon/CouponInfo.java`, `CouponDetailInfo.java`, `MyCouponInfo.java` | Application DTO |
| Application | `application/coupon/CouponFacade.java` | 대고객: 쿠폰 조회/다운로드/내 쿠폰 |
| Application | `application/coupon/AdminCouponFacade.java` | 어드민: 쿠폰 생성/조회 |
| Infrastructure | `infrastructure/coupon/CouponEntity.java`, `MemberCouponEntity.java` | JPA Entity |
| Infrastructure | `infrastructure/coupon/CouponJpaRepository.java`, `MemberCouponJpaRepository.java` | Spring Data JPA |
| Infrastructure | `infrastructure/coupon/CouponRepositoryImpl.java`, `MemberCouponRepositoryImpl.java` | Repository 구현체 |
| Interfaces | `interfaces/api/coupon/CouponV1Controller.java` | GET coupons, POST download, GET me |
| Interfaces | `interfaces/api/coupon/AdminCouponV1Controller.java` | POST/GET /api-admin/v1/coupons |
| Interfaces | `interfaces/api/coupon/dto/CouponV1Dto.java` | 요청/응답 DTO |

#### Phase 7: Order/OrderItem 도메인

| 레이어 | 파일 | 설명 |
|--------|------|------|
| Domain | `domain/order/Order.java` | 주문 도메인 (calculateTotalAmount, getPaymentAmount, validateOwner) |
| Domain | `domain/order/OrderItem.java` | 주문 아이템 스냅샷 (createSnapshot, getSubtotal) |
| Domain | `domain/order/OrderRepository.java` | Repository 인터페이스 |
| Domain | `domain/order/OrderService.java` | prepareOrderItems(재고 검증/차감/스냅샷) + createOrder(저장) |
| Application | `application/order/OrderInfo.java`, `OrderDetailInfo.java`, `OrderItemInfo.java` | Application DTO |
| Application | `application/order/OrderFacade.java` | 크로스 도메인 조율 (재고차감→쿠폰할인→주문생성→쿠폰사용→포인트차감→장바구니삭제) |
| Infrastructure | `infrastructure/order/OrderEntity.java` | JPA Entity (@OneToMany OrderItemEntity) |
| Infrastructure | `infrastructure/order/OrderItemEntity.java` | JPA Entity (@ManyToOne OrderEntity) |
| Infrastructure | `infrastructure/order/OrderJpaRepository.java` | Spring Data JPA |
| Infrastructure | `infrastructure/order/OrderRepositoryImpl.java` | Repository 구현체 |
| Interfaces | `interfaces/api/order/OrderV1Controller.java` | POST orders, GET orders, GET orders/{id} |
| Interfaces | `interfaces/api/order/dto/OrderV1Dto.java` | 요청/응답 DTO |

#### Phase 8: 테스트 코드

**단위 테스트 (Unit Tests) - 모두 통과 (Docker 불필요)**

| 테스트 유형 | 테스트 파일 |
|-------------|-----------|
| Domain Model | BrandTest, ProductTest, ProductOptionTest, LikeTest, CartItemTest, PointTest, PointHistoryTest, CouponTest, MemberCouponTest, OrderTest, OrderItemTest |
| Domain Service (Mock) | BrandServiceTest, ProductServiceTest, LikeServiceTest, CartServiceTest, PointServiceTest, CouponServiceTest, OrderServiceTest |
| Application Facade (Mock) | BrandFacadeTest, AdminBrandFacadeTest, ProductFacadeTest, AdminProductFacadeTest, CartFacadeTest, CouponFacadeTest, AdminCouponFacadeTest, AdminPointFacadeTest, MemberFacadeTest, OrderFacadeTest |
| Controller (MockMvc) | BrandV1ControllerTest, AdminBrandV1ControllerTest, ProductV1ControllerTest, AdminProductV1ControllerTest, CartV1ControllerTest, CouponV1ControllerTest, AdminCouponV1ControllerTest, OrderV1ControllerTest, AdminPointV1ControllerTest, MemberV1ControllerTest |

**통합 테스트 (Integration Tests) - Docker/Testcontainers 필요**

| 테스트 파일 | 검증 내용 |
|-------------|----------|
| BrandServiceIntegrationTest | register/findById/findActiveBrand/update/softDelete + 중복 검증 |
| ProductServiceIntegrationTest | register(공급가 계산)/findById/softDelete/deductStock + 재고 부족 |
| LikeServiceIntegrationTest | like(likeCount 증가)/unlike(감소)/멱등성/NOT_FOUND |
| CartServiceIntegrationTest | addToCart(새 아이템/수량 병합/재고 부족/옵션 없음) |
| PointServiceIntegrationTest | createPoint/chargePoint/usePoint/getBalance + 잔액 부족 |
| CouponServiceIntegrationTest | createCoupon/downloadCoupon(중복 409)/findAvailable/findMy/useCoupon |
| OrderServiceIntegrationTest | prepareOrderItems(재고 차감)/createOrder/findOrders/findOrderDetail |

**E2E 테스트 - Docker/Testcontainers 필요**

| 테스트 파일 | API 엔드포인트 |
|-------------|---------------|
| BrandV1ApiE2ETest | GET /api/v1/brands/{id} |
| AdminBrandV1ApiE2ETest | GET/POST/PUT/DELETE /api-admin/v1/brands |
| ProductV1ApiE2ETest | GET /api/v1/products, GET /{id}, POST/PUT likes |
| AdminProductV1ApiE2ETest | GET/POST/PUT/DELETE /api-admin/v1/products |
| CartV1ApiE2ETest | POST /api/v1/cart |
| AdminPointV1ApiE2ETest | POST /api-admin/v1/points |
| CouponV1ApiE2ETest | GET /api/v1/coupons, POST download, GET me |
| AdminCouponV1ApiE2ETest | POST/GET /api-admin/v1/coupons |
| OrderV1ApiE2ETest | POST/GET /api/v1/orders |

#### 미완료 항목

- QueryDSL 기반 Product search/adminSearch 구현 (현재 TODO 플레이스홀더)
- Like findLikedProductsByMemberId QueryDSL 구현
- 통합/E2E 테스트는 Docker 환경에서 실행 검증 필요

---

### 고민하고 수정한 내용

#### Domain Service에서 @Transactional 제거

**문제 인식**
- 모든 Domain Service 메서드에 `@Transactional`이 붙어 있었음
- Domain 레이어는 순수 비즈니스 로직만 담당해야 하는데, `org.springframework.transaction.annotation.Transactional`이라는 Spring 프레임워크 의존이 존재

**수정 내용**
- 9개 Domain Service(BrandService, OrderService, CouponService, PointService, LikeService, CartService, ProductService, MemberService, ExampleService)에서 `@Transactional` 어노테이션 및 관련 import를 모두 제거
- 트랜잭션 경계는 Application Layer의 Facade에서만 관리하도록 변경

**수정 이유**
- "트랜잭션을 언제 열고 닫을 것인가"는 유스케이스(Application Layer)의 책임이며, Domain Service가 결정할 사항이 아님
- Domain 레이어가 Spring 프레임워크에 의존하면 프레임워크 독립성이 떨어짐
- Facade에서 `@Transactional`을 선언하면, 하위 Service 호출은 기본 전파 속성(`REQUIRED`)에 의해 동일 트랜잭션에 참여하므로 동작상 차이 없음
- 트랜잭션 경계가 Facade 한 곳에서 명확하게 관리되어 가독성과 유지보수성 향상

**DIP 관점에서의 상세 설명**

프로젝트의 의존 방향: `Interfaces → Application → Domain ← Infrastructure`

Domain은 가장 안쪽 레이어로, 외부 프레임워크에 의존하면 안 된다.
`@Transactional`은 `org.springframework.transaction.annotation.Transactional` — Spring 프레임워크의 클래스이므로, Domain Service에 위치하면 고수준 모듈(Domain)이 저수준 모듈(Spring 프레임워크)에 의존하게 되어 DIP를 위반한다.

```java
// [과거] Domain Layer — BrandService.java
import org.springframework.transaction.annotation.Transactional; // ← Spring 의존 (DIP 위반)

@Service
public class BrandService {
    private final BrandRepository brandRepository; // ← Domain 인터페이스 (OK)

    @Transactional // ← Spring 프레임워크 의존 (DIP 위반)
    public Brand register(String name, String description) {
        validateDuplicateName(name);
        return brandRepository.save(new Brand(name, description));
    }
}

// [현재] Application Layer — AdminBrandFacade.java
@Transactional // ← Application 레이어에서 Spring 의존 (OK)
public BrandInfo register(String name, String description) {
    Brand brand = brandService.register(name, description);
    return BrandInfo.from(brand);
}

// [현재] Domain Layer — BrandService.java
public Brand register(String name, String description) { // @Transactional 없음
    validateDuplicateName(name);
    return brandRepository.save(new Brand(name, description));
}
```

**크로스 도메인에서 트랜잭션 경계가 Facade에 있어야 하는 이유 — OrderFacade 예시**

주문 생성은 여러 Domain Service를 거치는 유스케이스이다.
만약 각 Domain Service에 `@Transactional`이 개별로 걸려 있다면, 중간에 실패 시 이미 커밋된 작업은 롤백되지 않는다.

```java
// [문제] 각 Service가 독립 트랜잭션을 가진 경우
orderService.prepareOrderItems(items);   // TX1 커밋 ✅ (재고 차감됨)
couponService.calculateDiscount(...);    // TX2 커밋 ✅
orderService.createOrder(...);           // TX3 커밋 ✅
pointService.usePoint(...);              // TX4 실패 ❌ (잔액 부족!)
// → TX1~TX3은 이미 커밋됨. 재고는 차감됐는데 주문은 반쯤 생성된 상태!
```

Facade에서 하나의 `@Transactional`로 묶으면 전체 롤백이 보장된다:

```java
// [현재] Facade에서 하나의 트랜잭션으로 관리
@Transactional
public OrderDetailInfo createOrder(...) {
    orderService.prepareOrderItems(items);   // 같은 TX
    couponService.calculateDiscount(...);    // 같은 TX
    orderService.createOrder(...);           // 같은 TX
    pointService.usePoint(...);              // 같은 TX — 실패 시 전체 롤백
}
```

정리하면:

| 관점 | Domain Service에 @Transactional | Facade에 @Transactional |
|------|--------------------------------|------------------------|
| DIP | Domain → Spring 의존 (위반) | Application → Spring 의존 (허용) |
| 트랜잭션 경계 | 각 Service마다 개별 트랜잭션 | 유스케이스 단위로 하나의 트랜잭션 |
| 롤백 범위 | 부분 커밋 위험 | 전체 롤백 보장 |
| 책임 | "언제 트랜잭션을 열 것인가"는 비즈니스 규칙이 아님 | 유스케이스 조율자가 결정하는 것이 자연스러움 |

#### Domain Model과 JPA Entity 통합 리팩토링

**문제 인식**
- 기존 구조에서 Domain Model(순수 Java 클래스)과 JPA Entity(Infrastructure 레이어)가 분리되어 있었음
- 모든 도메인에 `from()`/`toXxx()` 변환 보일러플레이트가 존재
- 필드 하나 추가 시 Domain Model, Entity, 변환 메서드 3곳을 모두 수정해야 함
- Repository 구현체가 변환 로직으로 비대화

**수정 내용**

프로젝트에 이미 존재하는 `ExampleModel` 패턴(Domain = JPA Entity)을 따라, 12개 Infrastructure Entity를 삭제하고 Domain Model에 JPA 어노테이션을 통합했다.

**1) Domain Model에 JPA 어노테이션 추가 (13개 파일)**

| Domain Model | 주요 변경 |
|-------------|----------|
| `Brand.java` | `@Entity`, `@Table(name="brand")`, `BaseEntity` 상속, `@Enumerated(EnumType.STRING)` 추가, `withId()` 제거, `protected` 기본 생성자 추가 |
| `Member.java` | `@Entity`, `@Table(name="member")`, `BaseEntity` 상속, `@Column` 매핑, `protected` 기본 생성자 추가 |
| `Product.java` | `@Entity`, `@Table(name="product")`, `BaseEntity` 상속, `@ManyToOne(LAZY) Brand brand`, `options`는 `@Transient` (별도 쿼리 로딩 유지) |
| `ProductOption.java` | `@Entity`, `@Table(name="product_option")`, `BaseEntity` 상속 |
| `Like.java` | `@Entity`, `@Table(name="likes")`, BaseEntity **미상속** (soft delete 미사용), `@Id`/`@GeneratedValue`/`@PrePersist`/`@PreUpdate` 직접 관리 |
| `CartItem.java` | `@Entity`, `@Table(name="cart_item")`, `BaseEntity` 상속 |
| `Point.java` | `@Entity`, `@Table(name="point")`, `BaseEntity` 상속 |
| `PointHistory.java` | `@Entity`, `@Table(name="point_history")`, `BaseEntity` 상속, `@Enumerated(EnumType.STRING)` |
| `Coupon.java` | `@Entity`, `@Table(name="coupon")`, `BaseEntity` 상속, `@Enumerated(EnumType.STRING)` 3개 필드 |
| `MemberCoupon.java` | `@Entity`, `@Table(name="member_coupon")`, `BaseEntity` 상속, `@Enumerated(EnumType.STRING)` |
| `Order.java` | `@Entity`, `@Table(name="orders")`, `BaseEntity` 상속, `@OneToMany(mappedBy="order", cascade=ALL, orphanRemoval=true)` |
| `OrderItem.java` | `@Entity`, `@Table(name="order_item")`, `BaseEntity` 상속, `@ManyToOne(LAZY) Order order` |

**2) Infrastructure Entity 삭제 (12개 파일)**

| 삭제된 파일 |
|------------|
| `infrastructure/brand/BrandEntity.java` |
| `infrastructure/member/MemberEntity.java` |
| `infrastructure/product/ProductEntity.java` |
| `infrastructure/product/ProductOptionEntity.java` |
| `infrastructure/like/LikeEntity.java` |
| `infrastructure/cart/CartItemEntity.java` |
| `infrastructure/point/PointEntity.java` |
| `infrastructure/point/PointHistoryEntity.java` |
| `infrastructure/coupon/CouponEntity.java` |
| `infrastructure/coupon/MemberCouponEntity.java` |
| `infrastructure/order/OrderEntity.java` |
| `infrastructure/order/OrderItemEntity.java` |

**3) JPA Repository 제네릭 타입 변경 (12개 파일)**

모든 `JpaRepository<XxxEntity, Long>` → `JpaRepository<Xxx, Long>` 으로 변경.
JPQL 쿼리도 `"FROM CouponEntity"` → `"FROM Coupon"`, `"UPDATE CartItemEntity"` → `"UPDATE CartItem"` 등으로 업데이트.

**4) Repository 구현체 간소화 (10개 파일)**

변환 로직(`from()`/`toXxx()`) 제거. 예시:

```java
// Before
public Brand save(Brand brand) {
    BrandEntity entity = BrandEntity.from(brand);
    BrandEntity saved = brandJpaRepository.save(entity);
    return saved.toBrand();
}

// After
public Brand save(Brand brand) {
    return brandJpaRepository.save(brand);
}
```

**5) QueryDSL Q-클래스 마이그레이션**

Entity가 `infrastructure` 패키지에서 `domain` 패키지로 이동하면서, Q-클래스도 자동으로 `domain` 패키지에 생성됨:
- `QBrandEntity` → `QBrand`, `QProductEntity` → `QProduct`, `QLikeEntity` → `QLike` 등
- `LikeRepositoryImpl`, `ProductRepositoryImpl` 등의 QueryDSL import 전부 업데이트

**6) BaseEntity 수정**

`modules/jpa/src/main/java/com/loopers/domain/BaseEntity.java`에서 `id` 필드의 `final` 제거:
```java
// Before
private final Long id = 0L;

// After
private Long id = 0L;
```
이유: 테스트에서 `ReflectionTestUtils.setField()`로 ID를 설정해야 하는데, Java 21에서 `final` 필드는 리플렉션으로 변경 불가.

**7) 테스트 코드 업데이트 (~50개 파일)**

- `withId()` 팩토리 메서드 제거에 따라, 테스트 헬퍼 메서드 + `ReflectionTestUtils.setField()` 패턴으로 전환:
  ```java
  // Before
  Brand brand = Brand.withId(1L, "나이키", "스포츠", BrandStatus.ACTIVE, null, null);

  // After
  Brand brand = new Brand("나이키", "스포츠");
  brand.changeStatus(BrandStatus.ACTIVE);
  ReflectionTestUtils.setField(brand, "id", 1L);
  ```
- E2E 테스트에서 `XxxEntity` 직접 참조 제거, Domain Model로 직접 DB 저장
- 통합 테스트에서 `.toXxx()` 변환 호출 제거

**수정 이유**
- 프로젝트 규모(커머스 백엔드) 대비 Domain/Entity 분리의 유지보수 비용이 큼
- 필드 추가 시 3곳 동시 수정 → 1곳만 수정으로 개선
- 변환 보일러플레이트 제거로 Repository 구현체 코드량 대폭 감소
- DIP 원칙은 그대로 유지 (Repository 인터페이스는 Domain, 구현체는 Infrastructure)
- 프로젝트에 이미 존재하는 `ExampleModel` 패턴과 일관성 확보

#### 쿠폰 할인 계산 로직을 OrderFacade에서 CouponService로 이동

**문제 인식**
- `OrderFacade.createOrder()` 내부에 쿠폰 검증 및 할인 계산 비즈니스 로직(`calculateCouponDiscount`, `calculateApplicableAmount`)이 private 메서드로 존재
- Facade는 도메인 서비스 간의 **조율(orchestration)** 만 담당해야 하는데, 쿠폰 소유 검증 / 사용 가능 여부 / 유효기간 / 최소 주문 금액 검증 / 할인 금액 계산이라는 **도메인 비즈니스 로직**이 Facade에 위치

**수정 내용**

1) `CouponService`에 `calculateCouponDiscount(Long memberId, Long memberCouponId, int applicableAmount)` 메서드 추가
   - 회원 쿠폰 소유 검증 (`memberId` 일치 여부)
   - 쿠폰 사용 가능 여부 검증 (`isAvailable()`)
   - 쿠폰 유효기간 검증 (`isValid()`)
   - 최소 주문 금액 조건 검증 (`minOrderAmount`)
   - 할인 금액 계산 위임 (`coupon.calculateDiscount(applicableAmount)`)

2) `OrderFacade`에서 기존 `calculateCouponDiscount` private 메서드 제거
   - `couponService.calculateCouponDiscount()`로 위임
   - `calculateApplicableAmount`는 scope별 적용 대상 금액 산출로, 주문 아이템(`OrderItem`) 정보가 필요하므로 Facade에 유지 (쿠폰 도메인이 주문 도메인에 의존하지 않도록)

3) 테스트 코드 수정
   - `CouponServiceTest`: `calculateCouponDiscount` 관련 단위 테스트 5개 추가 (정상 할인 계산, 소유자 불일치, 이미 사용, 유효기간 만료, 최소 주문 금액 미달)
   - `OrderFacadeTest`: 쿠폰 사용 테스트에서 `couponService.calculateCouponDiscount()` Mock 검증으로 변경

**수정 전 (OrderFacade)**
```java
// Facade에 도메인 로직이 존재
private int calculateCouponDiscount(Long memberId, Long memberCouponId,
                                    List<OrderItem> orderItems, int totalAmount) {
    MemberCoupon memberCoupon = couponService.getMemberCoupon(memberCouponId);
    if (!memberCoupon.getMemberId().equals(memberId)) { ... }
    if (!memberCoupon.isAvailable()) { ... }
    Coupon coupon = couponService.findById(memberCoupon.getCouponId());
    if (!coupon.isValid()) { ... }
    int applicableAmount = calculateApplicableAmount(coupon, orderItems, totalAmount);
    if (applicableAmount < coupon.getMinOrderAmount()) { ... }
    return coupon.calculateDiscount(applicableAmount);
}
```

**수정 후 (OrderFacade → CouponService 위임)**
```java
// OrderFacade: 조율만 담당
if (memberCouponId != null) {
    int applicableAmount = calculateApplicableAmount(memberCouponId, orderItems, totalAmount);
    discountAmount = couponService.calculateCouponDiscount(memberId, memberCouponId, applicableAmount);
}

// CouponService: 비즈니스 로직 담당
public int calculateCouponDiscount(Long memberId, Long memberCouponId, int applicableAmount) {
    MemberCoupon memberCoupon = getMemberCoupon(memberCouponId);
    // 소유 검증, 사용 가능 여부, 유효기간, 최소 주문 금액 검증
    return coupon.calculateDiscount(applicableAmount);
}
```

**수정 이유**
- Facade의 역할은 "무엇을 할지 조율"이지, "어떻게 계산할지" 판단하는 것이 아님
- 쿠폰 검증/할인 계산은 쿠폰 도메인의 비즈니스 규칙이므로 `CouponService`가 담당하는 것이 자연스러움
- `CouponService`에 로직이 위치하면 다른 유스케이스(예: 할인 미리보기)에서도 재사용 가능
- 테스트 책임도 명확해짐: Facade 테스트는 조율 흐름만 검증, Service 테스트는 비즈니스 로직 검증

#### ProductOption N+1 쿼리 → IN절 배치 로딩으로 개선

**문제 인식**
- `ProductRepositoryImpl.search()`, `adminSearch()`, `LikeRepositoryImpl.findLikedProductsByMemberId()`에서 상품 목록 조회 후 **상품마다 개별 쿼리**로 옵션을 로딩하는 N+1 패턴이 존재
- `softDeleteByBrandId()`, `findOptionIdsByBrandId()`에서 `findAll().stream().filter()` 패턴으로 **전체 상품 테이블을 메모리에 로딩** 후 Java에서 필터링

**수정 내용**

1) `ProductRepositoryImpl`에 `assembleWithOptions()` 헬퍼 메서드 추가
   - 기존에 존재하던 `findAllByProductIdInAndDeletedAtIsNull(List<Long>)` IN절 배치 쿼리 활용
   - `groupingBy(ProductOption::getProductId)`로 Map 변환 후 Application 단에서 조립
   - `search()`, `adminSearch()` 두 메서드에서 공통 사용

```java
// Before: 상품 N개 → 옵션 쿼리 N번 (N+1)
products.stream()
    .map(p -> {
        List<ProductOption> options = productOptionJpaRepository
            .findAllByProductIdAndDeletedAtIsNull(p.getId()); // 매번 쿼리
        return p.withOptions(options);
    })

// After: IN절 1번 → Map 조립
private List<Product> assembleWithOptions(List<Product> products) {
    List<Long> productIds = products.stream().map(Product::getId).toList();
    Map<Long, List<ProductOption>> optionsByProductId =
        productOptionJpaRepository.findAllByProductIdInAndDeletedAtIsNull(productIds)
            .stream().collect(groupingBy(ProductOption::getProductId));
    return products.stream()
        .map(p -> p.withOptions(optionsByProductId.getOrDefault(p.getId(), List.of())))
        .toList();
}
```

2) `softDeleteByBrandId()` — `findAll().stream().filter()` → QueryDSL WHERE절
   - 전체 테이블 스캔 + Java 필터 제거
   - QueryDSL `product.brand.id.eq(brandId)` 조건으로 해당 브랜드 상품만 조회
   - 옵션 삭제도 IN절 배치로 처리

3) `findOptionIdsByBrandId()` — `findAll().stream().filter()` → QueryDSL `select(product.id)`
   - 전체 상품 로딩 대신 해당 브랜드의 상품 ID만 QueryDSL로 조회

4) `LikeRepositoryImpl.findLikedProductsByMemberId()` — 동일한 IN절 배치 패턴 적용

5) `ProductJpaRepository` — Spring Data 쿼리 메서드 수정
   - `existsByBrandIdAnd...` → `existsByBrand_IdAnd...`로 변경
   - Product의 `brand` 필드가 `@ManyToOne` 관계이므로 `brandId`(단일 필드)가 아닌 `brand.id`(연관 엔티티 경로)로 탐색해야 함
   - Spring Data JPA에서 `Brand_Id`는 `brand` → `id` 경로 탐색을 의미

**수정 이유**
- fetch join은 컬렉션 조인 시 페이징 문제(`MultipleBagFetchException`)나 cartesian product가 발생할 수 있음
- IN절 배치 + Application 단 조립은 쿼리가 단순하고 가독성이 좋으며, `default_batch_fetch_size: 100` 설정과도 일관된 방향
- `findAll()` 전체 로딩은 데이터 증가 시 OOM 위험이 있으므로 WHERE 조건으로 필요한 데이터만 조회

#### Facade 레이어 @Transactional 누락 보완

**문제 인식**
- Domain Service에서 `@Transactional`을 제거하고 Facade에서 관리하기로 했으나, 대부분의 Facade 메서드에 `@Transactional`이 누락된 상태였음
- `OrderFacade.createOrder()`, `MemberFacade.signup()`, `AdminBrandFacade.deleteBrand()`, `AdminProductFacade.deleteProduct()` 4곳에만 존재
- 나머지 Facade의 쓰기 작업과 조회 작업에 트랜잭션이 없어서, JPA 변경 감지(dirty checking)가 동작하지 않고 `TransactionRequiredException` 발생

**수정 내용**

10개 Facade에 `@Transactional` 추가:

| Facade | 클래스 레벨 | 메서드 레벨 (@Transactional) |
|--------|-----------|----------------------------|
| BrandFacade | `@Transactional(readOnly = true)` | — |
| AdminBrandFacade | `@Transactional(readOnly = true)` | `createBrand()`, `updateBrand()` |
| ProductFacade | `@Transactional(readOnly = true)` | `like()`, `unlike()` |
| AdminProductFacade | `@Transactional(readOnly = true)` | `createProduct()`, `updateProduct()` |
| CartFacade | — | `addToCart()` |
| CouponFacade | `@Transactional(readOnly = true)` | `downloadCoupon()` |
| AdminCouponFacade | `@Transactional(readOnly = true)` | `createCoupon()` |
| AdminPointFacade | — | `chargePoint()` |
| MemberFacade | `@Transactional(readOnly = true)` | `changePassword()` |
| ExampleFacade | `@Transactional(readOnly = true)` | — |

**적용 전략**
- 클래스 레벨에 `@Transactional(readOnly = true)` 기본 설정 → 조회 메서드는 읽기 전용 트랜잭션
- 쓰기 메서드에만 `@Transactional` 오버라이드 → 읽기/쓰기 트랜잭션 구분

#### 통합 테스트에 @Transactional 추가

**문제 인식**
- 통합 테스트는 Facade를 거치지 않고 Domain Service를 직접 호출하는데, Domain Service에서 `@Transactional`을 제거했으므로 트랜잭션이 존재하지 않음
- JPA 변경 감지, lazy loading 등이 트랜잭션 내에서만 동작하므로 `TransactionRequiredException` 발생

**수정 내용**

4개 통합 테스트 클래스에 `@Transactional` 추가:
- `BrandServiceIntegrationTest`
- `ProductServiceIntegrationTest`
- `LikeServiceIntegrationTest`
- `OrderServiceIntegrationTest`

**수정 이유**
- 통합 테스트는 Service → Repository → Database 구간만 검증하는 것이 목적이므로, Facade 없이 Service를 직접 호출
- 이 경우 테스트 메서드 자체가 트랜잭션 경계가 되어야 JPA가 정상 동작
- `@Transactional` 추가 시 테스트 종료 후 자동 롤백되므로 `@AfterEach`의 `truncateAllTables()`와 함께 데이터 격리도 보장

#### E2E 테스트 버그 수정

**1) MemberV1ApiE2ETest — JPA merge()에 의한 ID 불일치 (2개 테스트)**

**문제 원인**
- `BaseEntity`에서 `private Long id = 0L;`로 초기화
- `memberJpaRepository.save(member)` 호출 시, JPA의 `SimpleJpaRepository.save()`가 `isNew()` 판단
- `Long id = 0L` → 0은 null이 아니므로 **기존 엔티티**로 판단 → `merge()` 호출
- `merge()`는 반환값(managed entity)에만 DB에서 생성된 ID가 설정되고, 원본 `member` 객체는 `id = 0L`인 채로 유지
- `pointService.createPoint(member.getId())` → `createPoint(0L)` → Point(memberId=0) 저장
- HTTP 요청에서 인증 후 member(id=1)의 포인트를 조회하면 `findByMemberId(1L)` → 없음 → 404 NOT_FOUND

```java
// Before — save() 반환값 미사용, member.getId() = 0L
Member member = createMember(loginId, encodedPassword, name, email, birthDate);
memberJpaRepository.save(member);
pointService.createPoint(member.getId()); // ← 0L!

// After — save() 반환값 사용, savedMember.getId() = 실제 DB ID
Member member = createMember(loginId, encodedPassword, name, email, birthDate);
Member savedMember = memberJpaRepository.save(member);
pointService.createPoint(savedMember.getId()); // ← 1L (정상)
```

**2) OrderV1ApiE2ETest — URLEncoder 이중 인코딩 (1개 테스트)**

**문제 원인**
- `URLEncoder.encode()`로 `ZonedDateTime` 문자열을 인코딩 → `+09:00`이 `%2B09%3A00`으로 변환
- 이 문자열을 `testRestTemplate.exchange(String url, ...)`에 전달
- `RestTemplate`이 String URL을 URI 템플릿으로 처리하면서 `%`를 다시 인코딩 → `%252B09%253A00`
- 서버에서 `@DateTimeFormat(iso = ISO.DATE_TIME)` 파싱 실패 → 400 BAD_REQUEST

```java
// Before — URLEncoder + String URL = 이중 인코딩
String startAt = URLEncoder.encode(
    ZonedDateTime.now().minusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    StandardCharsets.UTF_8);
testRestTemplate.exchange(
    ENDPOINT_ORDERS + "?startAt=" + startAt + "&endAt=" + endAt + "&page=0&size=10",
    ...);

// After — URI 템플릿 변수로 RestTemplate이 인코딩 담당
String startAt = ZonedDateTime.now().minusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
testRestTemplate.exchange(
    ENDPOINT_ORDERS + "?startAt={startAt}&endAt={endAt}&page=0&size=10",
    ..., startAt, endAt);
```

#### WebMvcConfig 인터셉터 제외 경로 추가

**문제 인식**
- `/api/v1/examples/**` 경로가 `MemberAuthInterceptor`의 제외 대상에 포함되어 있지 않아, ExampleV1ApiE2ETest에서 인증 없이 요청 시 401 UNAUTHORIZED 발생

**수정 내용**
- `WebMvcConfig`의 `memberAuthInterceptor` 설정에 `"/api/v1/examples/**"` 경로를 `excludePathPatterns`에 추가

#### 고객 API 응답에서 supplyPrice(공급가) 제거

**문제 인식**
- `OrderV1Dto.OrderItemResponse`에 `supplyPrice` 필드가 포함되어 고객 대면 API(POST /api/v1/orders, GET /api/v1/orders/{orderId})에서 공급가가 노출
- 공급가는 마진 정보를 역산할 수 있는 민감한 비즈니스 데이터이므로 고객 API 응답에 포함되면 안 됨

**수정 내용**
- `OrderV1Dto.OrderItemResponse`에서 `supplyPrice` 필드 및 `from()` 매핑 제거
- `OrderItemInfo`(Application Layer)에는 `supplyPrice` 유지 — 향후 어드민 주문 상세 API에서 활용 가능

**수정 이유**
- Interfaces Layer의 응답 DTO는 클라이언트에게 노출해도 되는 정보만 포함해야 함
- Application Layer의 Info 객체는 내부 데이터이므로 유지해도 무방
- 어드민에서 공급가가 필요하다면 별도 어드민 DTO를 작성하여 대응

#### CartService.addToCart() 수량 검증 추가

**문제 인식**
- `CartService.addToCart()`에서 수량이 0이나 음수인 경우에 대한 검증이 없었음
- 0개나 음수 수량의 장바구니가 저장되면 주문 금액 계산과 재고 정책에서 장애 유발 가능

**수정 내용**
- `CartService.addToCart()` 메서드 진입 시 `quantity <= 0` 검증 추가
- 재고 조회보다 앞서 수행하여, 불필요한 DB 호출 방지
- `CartServiceTest`에 수량 0, 음수 케이스 테스트 2개 추가

```java
public CartItem addToCart(Long memberId, Long productOptionId, int quantity) {
    if (quantity <= 0) {
        throw new CoreException(ErrorType.BAD_REQUEST, "수량은 1개 이상이어야 합니다.");
    }
    // ... 이후 재고 검증 로직
}
```

**수정 이유**
- `PointService.chargePoint()`에서 `amount <= 0` 검증하는 기존 패턴과 일관성 유지
- 시스템 경계(사용자 입력)에서의 방어적 검증은 도메인 레벨에서 처리하는 것이 안전

#### Point 도메인 모델 불변식 검증 추가

**문제 인식**
- `Point.create()`, `charge()`, `use()`에서 입력값 불변식 검증이 없어 음수/0 입력으로 잔액이 왜곡될 수 있었음
- 포인트 정합성 붕괴는 정산 이슈와 CS 폭증으로 직결되므로, 도메인 모델 내부에서 불변식을 강제해야 함
- 기존에는 `PointService.chargePoint()`에만 `amount <= 0` 검증이 있었으나, 도메인 모델이 아닌 서비스에 위치하여 다른 경로로 호출 시 우회 가능

**수정 내용**
- `Point.create()`: `initialBalance < 0` 검증 추가
- `Point.charge()`: `amount <= 0` 검증 추가
- `Point.use()`: `amount <= 0` 검증 추가 (기존 잔액 부족 검증 앞에 배치)
- `PointService.chargePoint()`의 중복 `amount <= 0` 검증 제거 — 도메인 모델이 불변식을 강제하므로 Service의 중복 검증 불필요
- `PointTest`에 테스트 5개 추가: 초기 잔액 음수, 충전 0, 충전 음수, 사용 0, 사용 음수

**수정 이유**
- 불변식은 도메인 모델 내부에서 강제하는 것이 원칙 — 어떤 경로로 호출하든 깨지지 않음
- Service에 검증이 있으면 도메인 모델을 직접 사용하는 테스트 등에서 우회 가능

#### Point 생성자 불변식 강화 — 검증 위치를 private 생성자로 이동

**문제 인식**
- `Point.create()`에서 `initialBalance < 0` 검증은 추가했으나, private 생성자에서 `memberId` null 검증과 `balance` 음수 검증이 누락
- private 생성자가 불변식의 최종 방어선이므로, 어떤 경로로든 객체가 생성될 때 반드시 검증이 수행되어야 함

**수정 내용**
- `initialBalance < 0` 검증을 `create()`에서 private 생성자로 이동
- `memberId == null` 검증을 private 생성자에 추가
- `PointTest`에 memberId null 케이스 테스트 추가

#### ProductOption 재고 변경 시 수량 경계값 검증 추가

**문제 인식**
- `ProductOption.deductStock()`, `restoreStock()`에서 수량 경계값 검증이 없어 `deductStock(-1)`로 재고가 증가하거나 `restoreStock(-1)`로 재고가 감소하는 역방향 변경 가능

**수정 내용**
- `deductStock()`: `quantity <= 0` 검증 추가
- `restoreStock()`: `quantity <= 0` 검증 추가
- `ProductOptionTest`에 차감/복원 0, 음수 케이스 테스트 4개 추가

#### Order 조회 시 N+1 쿼리 해결 (@EntityGraph 적용)

**문제 인식**
- `OrderJpaRepository.findByMemberIdAndCreatedAtBetweenAndDeletedAtIsNull()`이 반환한 각 Order에서 `orderItems`에 접근할 때 N+1 쿼리 발생
- `OrderFacade.findOrders()` → `OrderInfo.from()` → `order.getOrderItems().size()` 호출로 페이지당 주문 수만큼 추가 쿼리 실행

**수정 내용**
- `findByIdAndDeletedAtIsNull`에 `@EntityGraph(attributePaths = "orderItems")` 추가
- `findByMemberIdAndCreatedAtBetweenAndDeletedAtIsNull`에 `@EntityGraph(attributePaths = "orderItems")` 추가
- `default_batch_fetch_size: 100` 설정이 있지만, `@EntityGraph`로 명시적으로 1번의 JOIN 쿼리로 해결

**수정 이유**
- `@EntityGraph`는 `@OneToMany`가 1개일 때 가장 간단하고 확실한 N+1 해결 방법
- `JOIN FETCH` + `countQuery` 분리 방식보다 코드 변경이 적고, 기존 메서드명 쿼리를 그대로 유지 가능

#### ArgumentResolver 인증 속성 방어 로직 추가

**문제 인식**
- `LoginMemberArgumentResolver`, `LoginAdminArgumentResolver`에서 인터셉터가 설정한 로그인 속성을 검증 없이 반환
- 인터셉터 누락/오동작 시 null이 컨트롤러로 전달되어 401 대신 500(NPE) 발생 위험
- 속성 타입 불일치 시에도 ClassCastException → 500 발생 위험

**수정 내용**
- `webRequest.getNativeRequest(HttpServletRequest.class)`로 타입 안전 획득
- request null 검증 → `CoreException(UNAUTHORIZED)`
- attribute null 또는 `instanceof` 타입 불일치 → `CoreException(UNAUTHORIZED)`
- `LoginMemberArgumentResolverTest`, `LoginAdminArgumentResolverTest` 단위 테스트 각 4개(정상, null, 타입 불일치, request null) 추가

**수정 이유**
- 방어적 프로그래밍: 인터셉터와 Resolver 사이의 암묵적 계약에 의존하지 않고 Resolver에서 명시적으로 검증
- 500 Internal Server Error 대신 401 Unauthorized로 적절한 HTTP 상태 코드 반환

#### 회원가입/비밀번호 변경 응답에서 평문 비밀번호 헤더 제거

**문제 인식**
- `MemberV1Controller`의 `signup()`과 `changePassword()`에서 `X-Loopers-LoginPw` 응답 헤더로 평문 비밀번호를 노출
- LB/프록시/접근 로그에 평문 비밀번호가 저장되어 계정 탈취 및 컴플라이언스 위반으로 직결

**수정 내용**
- `MemberV1Controller`: `HEADER_LOGIN_PW` 상수 제거, `signup()`에서 평문 비밀번호 보관 및 헤더 응답 제거, `changePassword()`에서 `HttpServletResponse` 파라미터 및 비밀번호 헤더 응답 제거
- `MemberV1ControllerTest`: signup/changePassword 응답에 `X-Loopers-LoginPw` 헤더가 **존재하지 않음**을 검증하도록 변경 (`header().doesNotExist()`)
- `MemberV1ApiE2ETest`: signup/changePassword 응답에 `X-Loopers-LoginPw` 헤더가 **null**임을 검증하도록 변경 (`.isNull()`)

**수정 이유**
- 응답 헤더에 비밀번호를 포함시키면 네트워크 경로상 모든 중간 장비(LB, WAF, 프록시)의 접근 로그에 평문 비밀번호가 기록됨
- 요청 헤더로 비밀번호를 보내는 인증 메커니즘(`MemberAuthInterceptor`)은 현재 인증 구조상 유지하되, 응답에 되돌려주는 것만 제거

#### 테스트 결과

모든 수정 완료 후 전체 531개 테스트 통과 (BUILD SUCCESSFUL)
