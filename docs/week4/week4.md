### 작업내용
1. 쿠폰 기능 수정 및 추가
- 주문 시에 쿠폰을 이용해 사용자가 소유한 쿠폰을 적용해 할인받을 수 있도록 합니다.
- 쿠폰은 **정액, 정률 쿠폰이 존재**하며 **재사용이 불가능**합니다.
- 존재하지 않거나 사용 불가능한 쿠폰으로 요청 시, 주문은 실패해야 합니다.
  1) 대고객 API
     (1) 쿠폰발급요청
        - URI : /api/v1/coupons/{couponId}/issue
        - METHOD : POST
     (2) 내 쿠폰 목록 조회
        - URI : /api/v1/users/me/coupons
        - METHOD : GET
        - 쿠폰 목록 조회 시 사용 가능한 쿠폰(AVAILABLE) / 사용 완료(USED) / 만료(EXPIRED) 상태를 함께 반환

  2) 어드민 API
     (1) 쿠폰 템플릿 목록 조회
        - URI : /api-admin/v1/coupons?page=0&size=20
        - METHOD : GET
     (2) 쿠폰 템플릿 상세 조회
        - URI : /api-admin/v1/coupons/{couponId}
        - METHOD : GET
     (3) 쿠폰 템플릿 등록 * 정액(FIXED) / 정률(RATE) 타입 지정
        - URI : /api-admin/v1/coupons
        - METHOD : POST
     (4) 쿠폰 템플릿 수정
        - URI : /api-admin/v1/coupons/{couponId}
        - METHOD : PUT
     (5) 쿠폰 템플릿 삭제
        - URI : /api-admin/v1/coupons/{couponId}
        - METHOD : DELETE
     (6) 특정 쿠폰의 발급 내역 조회
        - URI : /api-admin/v1/coupons/{couponId}/issues?page=0&size=20
        - METHOD : GET

2. 주문 API 변경
> **쿠폰 적용 규칙**
>
> - 쿠폰은 주문 1건당 1장만 적용 가능합니다.
> - 존재하지 않거나 이미 사용된 쿠폰, 만료된 쿠폰, 타 유저 소유 쿠폰으로 요청 시 주문은 실패합니다.
> - 주문 성공 시 해당 쿠폰은 즉시 `USED` 상태로 변경되며 재사용이 불가합니다.
> - 주문 정보 스냅샷에는 쿠폰 적용 전 금액, 할인 금액, 최종 결제 금액이 모두 포함되어야 합니다.


### 과제 정보

- 주문 API에 트랜잭션을 적용하고, 재고 / 쿠폰 / 주문 도메인의 정합성을 보장합니다.
- 동시성 이슈(Lost Update)가 발생하지 않도록 낙관적 락 또는 비관적 락을 적용합니다.
- 주요 구현 대상은 Application Layer (혹은 OrderFacade 등)에서의 트랜잭션 처리입니다.
- 동시성 이슈가 발생할 수 있는 기능에 대한 테스트가 모두 성공해야 합니다.

**예시 (주문 처리 흐름)**

```kotlin
1. 주문 요청
2. "주문을 위한 처리" ( 순서 무관 )
	- 쿠폰 유효성 검증 및 사용 처리 // 동시성 이슈 위험 구간
	- 상품 재고 확인 및 차감 // 동시성 이슈 위험 구간
5. 주문 엔티티 생성 및 저장
```

---
## Checklist

### ️Coupon 도메인

- [ ]  쿠폰은 사용자가 소유하고 있으며, 이미 사용된 쿠폰은 사용할 수 없어야 한다.
- [ ]  쿠폰 종류는 정액 / 정률로 구분되며, 각 적용 로직을 구현하였다.
- [ ]  각 발급된 쿠폰은 최대 한번만 사용될 수 있다.

###  **주문**

- [ ]  주문 전체 흐름에 대해 원자성이 보장되어야 한다.
- [ ]  사용 불가능하거나 존재하지 않는 쿠폰일 경우 주문은 실패해야 한다.
- [ ]  재고가 존재하지 않거나 부족할 경우 주문은 실패해야 한다.
- [ ]  쿠폰, 재고, 포인트 처리 등 하나라도 작업이 실패하면 모두 롤백처리되어야 한다.
- [ ]  주문 성공 시, 모든 처리는 정상 반영되어야 한다.

### 동시성 테스트

- [ ]  동일한 상품에 대해 여러명이 좋아요/싫어요를 요청해도, 상품의 좋아요 수가 정상 반영되어야 한다.
- [ ]  동일한 쿠폰으로 여러 기기에서 동시에 주문해도, 쿠폰은 단 한번만 사용되어야 한다.
- [ ]  동일한 상품에 대해 여러 주문이 동시에 요청되어도, 재고가 정상적으로 차감되어야 한다.

### 과제 집중할 점

> **모든 기능의 동작을 개발한 후에 동시성, 멱등성, 일관성, 느린 조회, 동시 주문 등 실제 서비스에서 발생하는 문제들을 해결하게 됩니다.**
>
>
> **낙관적 락(Optimistic Lock)** 또는 **비관적 락(Pessimistic Lock)** 중 각 도메인의 특성에 맞는 전략을 선택하여 적용하세요. Application Layer(혹은 OrderFacade)에서의 트랜잭션 경계 설계가 핵심입니다.
>


---

## 구현 결과

### 1. 기존 코드 수정

#### (1) 쿠폰 발급 URI 변경
- `POST /api/v1/coupons/{couponId}/download` → `POST /api/v1/coupons/{couponId}/issue`
- **변경 파일**: `CouponV1Controller`, `CouponV1ControllerTest`, `CouponV1ApiE2ETest`

#### (2) 내 쿠폰 목록 조회 URI 및 로직 변경
- `GET /api/v1/coupons/me` (AVAILABLE만) → `GET /api/v1/users/me/coupons` (AVAILABLE/USED/EXPIRED)
- **설계**: `CouponV1Controller`의 `@RequestMapping`을 `/api/v1`로 변경하여 `/coupons`와 `/users/me/coupons` 경로를 하나의 컨트롤러에서 처리
- **변경 파일**:
  - `CouponV1Controller` — `@RequestMapping("/api/v1")`, `getMyCoupons()` 메서드 추가
  - `MemberCouponStatus` — `EXPIRED` enum 추가 (응답 표현용, DB 저장 안 함)
  - `MemberCouponRepository` — `findByMemberId()` 추가
  - `MemberCouponJpaRepository` — `findByMemberIdAndDeletedAtIsNull()` 추가
  - `MemberCouponRepositoryImpl` — 구현 추가
  - `CouponService` — `findAllMyCoupons()` 추가
  - `MyCouponInfo` — `status` 필드 추가, `resolveDisplayStatus()` 로직
  - `CouponFacade` — `getMyCoupons()` 수정 (전체 상태 조회)
  - `CouponV1Dto.MyCouponResponse` — `status` 필드 추가

### 2. 신규 추가

#### (3) 어드민 쿠폰 수정 API — `PUT /api-admin/v1/coupons/{couponId}`
- **추가 파일**:
  - `Coupon` — `updateInfo()` 메서드 추가
  - `CouponService` — `updateCoupon()` 추가
  - `AdminCouponFacade` — `updateCoupon()` 추가
  - `AdminCouponV1Controller` — `@PutMapping("/{couponId}")` 추가
  - `CouponV1Dto` — `UpdateCouponRequest` DTO 추가

#### (4) 어드민 쿠폰 삭제 API — `DELETE /api-admin/v1/coupons/{couponId}` (soft delete)
- **추가 파일**:
  - `CouponRepository` — `softDelete()` 추가
  - `CouponRepositoryImpl` — `softDelete()` 구현 (`findById → Coupon::delete`)
  - `CouponService` — `softDelete()` 추가
  - `AdminCouponFacade` — `deleteCoupon()` 추가
  - `AdminCouponV1Controller` — `@DeleteMapping("/{couponId}")` 추가

#### (5) 어드민 쿠폰 발급 내역 조회 API — `GET /api-admin/v1/coupons/{couponId}/issues?page=0&size=20`
- **추가 파일**:
  - `MemberCouponRepository` — `findByCouponId(Long, Pageable)` 추가
  - `MemberCouponJpaRepository` — `findByCouponIdAndDeletedAtIsNull()` 추가
  - `MemberCouponRepositoryImpl` — 구현 추가
  - `CouponService` — `findCouponIssues()` 추가
  - `AdminCouponFacade` — `getCouponIssues()` 추가 (N+1 방지: Coupon 1회 조회 후 재사용)
  - `AdminCouponV1Controller` — `@GetMapping("/{couponId}/issues")` 추가
  - `CouponIssueInfo` — 신규 Info 레코드
  - `CouponV1Dto` — `CouponIssueResponse` DTO 추가

### 3. 이미 구현 완료 (변경 불필요)

#### 주문 시 쿠폰 검증 로직
| 검증 항목 | 현재 구현 위치 | 상태 |
|-----------|--------------|------|
| 존재하지 않는 쿠폰 | `CouponService.getMemberCoupon()` → NOT_FOUND | ✅ |
| 이미 사용된 쿠폰 | `CouponService.calculateCouponDiscount()` → isAvailable() | ✅ |
| 만료된 쿠폰 | `CouponService.calculateCouponDiscount()` → isValid() | ✅ |
| 타 유저 소유 쿠폰 | `CouponService.calculateCouponDiscount()` → memberId 비교 | ✅ |
| 주문 성공 시 USED 처리 | `OrderFacade.createOrder()` → useCoupon() | ✅ |
| 주문 1건당 1장 | `Order.memberCouponId` 단일 필드 | ✅ |

#### 주문 스냅샷 금액 정보
| 필드 | 현재 Order 엔티티 | 상태 |
|------|------------------|------|
| 쿠폰 적용 전 금액 | `totalAmount` | ✅ |
| 할인 금액 | `discountAmount` | ✅ |
| 최종 결제 금액 | `getPaymentAmount()` (totalAmount - discountAmount - usedPoints) | ✅ |

#### 어드민 쿠폰 기존 API
| API | 상태 |
|-----|------|
| 쿠폰 템플릿 목록 조회 (GET /api-admin/v1/coupons) | ✅ |
| 쿠폰 템플릿 상세 조회 (GET /api-admin/v1/coupons/{couponId}) | ✅ |
| 쿠폰 템플릿 등록 (POST /api-admin/v1/coupons) | ✅ |

---

## 테스트

### 테스트 구성
| 레이어 | 테스트 파일 | 검증 대상 |
|--------|-----------|----------|
| Domain (Unit) | `CouponTest` | 생성, isIssuable, isValid, issue, calculateDiscount |
| Domain (Unit) | `MemberCouponTest` | 생성, isAvailable |
| Domain (Unit) | `CouponServiceTest` | updateCoupon, softDelete, findAllMyCoupons, findCouponIssues |
| Application (Unit) | `AdminCouponFacadeTest` | 생성/목록/상세/수정/삭제/발급내역 |
| Application (Unit) | `CouponFacadeTest` | 조회/발급/내 쿠폰(AVAILABLE/USED/EXPIRED) |
| Interfaces (Unit) | `CouponV1ControllerTest` | 쿠폰 목록/발급/내 쿠폰 + 인증 검증 |
| Interfaces (Unit) | `AdminCouponV1ControllerTest` | 생성/목록/상세/수정/삭제/발급내역 + 인증 검증 |
| Domain (Integration) | `CouponServiceIntegrationTest` | updateCoupon, softDelete, findAllMyCoupons, findCouponIssues (DB) |
| E2E | `CouponV1ApiE2ETest` | 발급 URI 변경, 내 쿠폰 조회 URI 변경 |
| E2E | `AdminCouponV1ApiE2ETest` | 생성/목록/상세/수정/삭제/발급내역 |

### 동시성 테스트 구성

| 테스트 파일 | 시나리오 | 동시성 제어 방식 |
|------------|---------|:---------------:|
| `LikeConcurrencyTest` | 서로 다른 회원 동시 좋아요 → likeCount 정확히 반영 | Atomic UPDATE |
| `LikeConcurrencyTest` | 서로 다른 회원 동시 좋아요 취소 → likeCount 0 | Atomic UPDATE |
| `LikeConcurrencyTest` | 좋아요 + 취소 혼합 동시 요청 → likeCount 정확히 반영 | Atomic UPDATE |
| `CouponUsageConcurrencyTest` | 동일 쿠폰 동시 사용 → 1번만 성공 | 원자적 UPDATE (`WHERE status='AVAILABLE'`) |
| `CouponDownloadConcurrencyTest` | 서로 다른 회원 동시 다운로드 → 모두 성공 | 비관적 락 |
| `CouponDownloadConcurrencyTest` | 같은 회원 동시 다운로드 → 1번만 성공 | 비관적 락 + 중복 검사 |
| `StockDeductConcurrencyTest` | 동시 재고 차감 → 재고만큼만 성공 | 비관적 락 (SELECT FOR UPDATE) |
| `PointConcurrencyTest` | 동시 포인트 사용 → 잔액만큼만 성공 | 비관적 락 (SELECT FOR UPDATE) |
| `PointConcurrencyTest` | 충전/사용 동시 발생 → 최종 잔액 정확 | 비관적 락 (SELECT FOR UPDATE) |
| `CartConcurrencyTest` | 같은 회원 동시 장바구니 추가 → 수량 정확히 합산 | 원자적 UPSERT |
| `CartConcurrencyTest` | 서로 다른 회원 동시 장바구니 추가 → 모두 성공 | 원자적 UPSERT |

---

## DB 테이블 변경: 없음
- 현재 COUPON, MEMBER_COUPON, ORDERS 테이블이 week4 요구사항을 이미 충족
- `EXPIRED` 상태는 DB에 저장하지 않고 조회 시 계산 (coupon.validTo < 현재시간 && member_coupon.status == AVAILABLE → EXPIRED)

## 설계 결정 사항
- **EXPIRED 판별 방식**: 조회 시 계산 (배치 상태 변경 X)
  - 이유: 별도 배치 작업 불필요, 실시간 정확성 보장, DB 스키마 변경 없음
- **컨트롤러 구조**: `CouponV1Controller`에 `@RequestMapping("/api/v1")`을 적용하여 `/coupons`와 `/users/me/coupons` 경로를 하나의 컨트롤러에서 처리
  - 이유: 동일 `CouponFacade`를 사용하는 같은 도메인 엔드포인트를 별도 컨트롤러로 분리할 필요 없음
- **N+1 방지**: `AdminCouponFacade.getCouponIssues()`에서 Coupon을 1회 조회 후 MemberCoupon 매핑 시 재사용

---

## 추가 변경 이력

### `GET /api/v1/coupons` (사용 가능한 쿠폰 목록 조회) 엔드포인트 제거
- **이유**: 대고객 쿠폰 목록 조회 기능이 불필요하여 제거
- **제거 대상**:
  - `CouponV1Controller` — `getAvailableCoupons()` 메서드 제거
  - `CouponFacade` — `getAvailableCoupons()` 메서드 제거
  - `CouponService` — `findAvailableCoupons()` 메서드 제거
  - `CouponRepository` — `findAllValid()` 메서드 제거
  - `CouponRepositoryImpl` — `findAllValid()` 구현 제거
  - `CouponJpaRepository` — `findAllValid()` JPQL 쿼리 제거
  - `WebMvcConfig` — 인터셉터 exclude 패턴에서 `/api/v1/coupons` 제거
  - 관련 테스트 (`CouponV1ControllerTest`, `CouponFacadeTest`, `CouponServiceTest`, `CouponServiceIntegrationTest`, `CouponV1ApiE2ETest`) 에서 해당 테스트 케이스 제거
  - `coupon-v1.http` — 해당 요청 제거

### `CouponFacade.getMyCoupons()` N+1 → IN절 조회 최적화
- **이유**: 기존 방식은 `MemberCoupon`마다 개별적으로 `couponService.findById()`를 호출하여 N+1 쿼리 발생 가능
- **변경 내용**:
  - `CouponFacade.getMyCoupons()` — `MemberCoupon`에서 couponId 목록을 수집 후 `couponService.findByIds()`로 한 번에 조회, Map으로 변환하여 매핑
  - `CouponService` — `findByIds(List<Long>)` 메서드 추가
  - `CouponRepository` — `findByIds(List<Long>)` 메서드 추가
  - `CouponRepositoryImpl` — `findByIds()` 구현
  - `CouponJpaRepository` — `findAllByIdInAndDeletedAtIsNull(List<Long>)` 추가
  - `CouponServiceTest` — `FindByIds` 테스트 추가
  - `CouponFacadeTest` — mock 설정을 `findById` → `findByIds`로 변경

### `MemberCouponDetail` 도입 및 쿠폰 조합 로직 Service 이관
- **이유 1 (불필요한 DB 쿼리 제거)**: `CouponFacade.downloadCoupon()`에서 쿠폰 다운로드 후 응답 DTO 생성을 위해 `couponService.findById()`를 한 번 더 호출. `findById`가 JPQL 파생 쿼리(`findByIdAndDeletedAtIsNull`)를 사용하기 때문에 JPA 1차 캐시를 타지 않아 불필요한 DB 쿼리가 발생
- **이유 2 (책임 이관)**: `CouponFacade.getMyCoupons()`에서 MemberCoupon + Coupon 조합 로직이 Facade에 위치. 같은 `coupon` 도메인 내 Repository 2개를 조합하는 것이므로 Service 책임으로 이관
- **변경 내용**:
  - `MemberCouponDetail` — `MemberCoupon`과 `Coupon`을 함께 담는 domain record 신규 생성
  - `CouponService.downloadCoupon()` — 반환 타입 `MemberCoupon` → `MemberCouponDetail` (이미 조회한 Coupon을 함께 반환)
  - `CouponService.getMyCouponDetails()` — 새 메서드 추가. Facade의 MemberCoupon + Coupon IN절 조합 로직을 Service로 이관
  - `CouponFacade.downloadCoupon()` — `couponService.findById()` 호출 제거, `MemberCouponDetail`에서 바로 `CouponInfo` 생성
  - `CouponFacade.getMyCoupons()` — 조합 로직 제거, `couponService.getMyCouponDetails()` 호출 후 `MyCouponInfo` 변환만 수행
  - `CouponServiceTest` — `downloadCoupon` 반환타입 변경 + `GetMyCouponDetails` nested class 추가
  - `CouponServiceIntegrationTest` — `downloadCoupon` 반환타입 변경 + `GetMyCouponDetails` nested class 추가
  - `CouponFacadeTest` — mock 설정을 `MemberCouponDetail` 기반으로 변경, `getMyCouponDetails()` mock 사용

### `CouponService.getMyCouponDetails()` 삭제된 쿠폰 NPE 방지
- **이유**: `couponMap.get(mc.getCouponId())`에서 쿠폰이 soft delete된 경우 null이 반환되어 `MemberCouponDetail` 생성 시 NPE 발생 가능
- **해결**: `couponMap.containsKey()`로 필터링하여 삭제된 쿠폰에 대한 memberCoupon 항목을 결과에서 제외
- **변경 파일**:
  - `CouponService` — `getMyCouponDetails()`에 `filter(mc -> couponMap.containsKey(mc.getCouponId()))` 추가
  - `CouponServiceTest` — `excludesDeletedCoupons()` 테스트 추가

### 비관적 락(Pessimistic Lock) 적용 — 쿠폰 수정/삭제 동시성 제어
- **이유**: 고객 발급 중 어드민이 쿠폰을 삭제/수정하면 삭제된 쿠폰이 발급되거나 변경 사항이 무시될 수 있음
- **해결**: `SELECT ... FOR UPDATE` 비관적 락을 적용하여 어드민 수정/삭제 시 동시 발급과의 충돌 방지
- **메서드 네이밍 (`WithLock`) 설계**:
  - `WithLock`은 Spring Data JPA 키워드가 아니라 개발자가 정한 네이밍 컨벤션
  - 기존 `findByIdAndDeletedAtIsNull()`에 `@Lock`을 직접 붙이면 모든 호출자가 락을 잡게 되므로, 락 없는 일반 조회와 락 있는 조회를 분리하기 위해 별도 메서드로 생성
  - 같은 조건(id + deletedAt IS NULL)으로 파생 쿼리 메서드명이 겹치기 때문에, `@Query`로 JPQL을 직접 작성하고 이름에 `WithLock`을 넣어 구분
  ```java
  // 일반 조회 — 락 없음
  Optional<Coupon> findByIdAndDeletedAtIsNull(Long id);

  // 락 조회 — SELECT ... FOR UPDATE
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT c FROM Coupon c WHERE c.id = :id AND c.deletedAt IS NULL")
  Optional<Coupon> findByIdWithLockAndDeletedAtIsNull(@Param("id") Long id);
  ```
- **변경 내용**:
  - `CouponJpaRepository` — `@Lock(PESSIMISTIC_WRITE)` + `@Query` JPQL로 `findByIdWithLockAndDeletedAtIsNull()` 메서드 추가
  - `CouponRepository` — `findByIdWithLock(Long id)` 메서드 선언
  - `CouponRepositoryImpl` — `findByIdWithLock()` 구현
  - `CouponService` — `updateCoupon()`, `softDelete()` 2개 메서드에서 `findById` → `findByIdWithLock` 변경 (트랜잭션은 Facade 레이어에서 `@Transactional`로 관리)
  - `CouponService.softDelete()` — 기존 `findById` + `couponRepository.softDelete()` 방식에서 `findByIdWithLock` 후 `coupon.delete()` + `save()` 방식으로 변경 (락 걸린 엔티티에서 직접 삭제 처리)
  - `CouponRepository` — `softDelete(Long id)` 메서드 제거 (데드 코드 정리: Service에서 `findByIdWithLock` + `delete()` + `save()` 방식으로 변경되면서 미사용)
  - `CouponRepositoryImpl` — `softDelete()` 구현 제거
  - `CouponServiceTest` — `updateCoupon`, `softDelete` 테스트의 Mock 대상 메서드명을 `findById` → `findByIdWithLock`으로 변경

---


---

### 시나리오 1: 상품 좋아요 수 동시성

**문제 위치**: `LikeService.like()` / `unlike()`, `Product.incrementLikeCount()` / `decrementLikeCount()`

**현재 문제**: Read-Decide-Write 패턴에 락이 없음

```
Thread A: Product.likeCount = 5 읽음
Thread B: Product.likeCount = 5 읽음
Thread A: 6으로 증가 후 저장
Thread B: 6으로 증가 후 저장  ← Lost Update! 실제로는 7이어야 함
```

**원인 분석**:
- `productRepository.findById()`에 어떤 락도 적용되어 있지 않음
- `Product.likeCount++` / `likeCount--`는 비원자적(non-atomic) 연산
- 여러 사용자가 동시에 좋아요/취소를 요청하면 일부 카운트가 유실됨

**관련 코드**:
- `LikeService.like()` — `product.incrementLikeCount()` 호출 전 락 없는 조회
- `LikeService.unlike()` — `product.decrementLikeCount()` 호출 전 락 없는 조회
- `Product.incrementLikeCount()` / `decrementLikeCount()` — 단순 `this.likeCount++/--`

**해결: Atomic UPDATE 적용**
- 엔티티 메모리 조작(`product.incrementLikeCount()` + `productRepository.save()`) → DB 레벨 원자적 UPDATE(`@Modifying @Query`)로 변경
- `UPDATE product SET like_count = like_count + 1 WHERE id = ?` — DB가 원자적으로 처리하므로 Lost Update 불가
- `decrementLikeCount`에 `AND like_count > 0` 조건 추가 — 음수 방지를 DB 레벨에서 보장
- `Product.incrementLikeCount()`, `decrementLikeCount()` 메서드 제거 — Read-Modify-Write 패턴 제거
- 락 전략 비교: 비관적/낙관적 락은 행 잠금이 필요하지만, 단순 카운터 증감은 Atomic UPDATE로 충분 (락 오버헤드 없음)
- 동시성 테스트(`LikeConcurrencyTest`): 10개 스레드가 동시에 같은 상품에 좋아요 → `likeCount == 10` 검증 통과

---

### 시나리오 2: 쿠폰 중복 사용 (동시 주문)

**문제 위치**: `OrderFacade.createOrder()`, `CouponService.calculateCouponDiscount()`, `CouponService.useCoupon()`

**현재 문제**: TOCTOU (Time-of-Check-Time-of-Use) 취약점

```
Thread A: MemberCoupon.status = AVAILABLE 확인 (calculateCouponDiscount) → OK
Thread B: MemberCoupon.status = AVAILABLE 확인 (calculateCouponDiscount) → OK
Thread A: Order 생성 → useCoupon()으로 USED 처리 → 성공
Thread B: Order 생성 → useCoupon()으로 USED 처리 시도 → 예외 발생
          그러나 Order는 이미 생성되었음!
```

**원인 분석**:
- `memberCouponRepository.findById()`에 락이 없음
- `calculateCouponDiscount()`에서 `isAvailable()` 검증 → `useCoupon()`에서 상태 변경까지 시간 간격 존재
- 두 스레드가 동시에 AVAILABLE 상태를 읽고, 각각 주문을 생성한 후 쿠폰 사용을 시도
- **참고**: `Coupon` 엔티티(발급 수량 issuedQuantity)에는 비관적 락이 적용되어 있지만, `MemberCoupon`(사용 상태 전환)에는 미적용

**관련 코드**:
- `CouponService.calculateCouponDiscount()` — `memberCoupon.isAvailable()` 체크 (락 없음)
- `CouponService.useCoupon()` — `memberCoupon.use(orderId)` 상태 변경 (락 없음)
- `OrderFacade.createOrder()` — 쿠폰 검증 → 주문 생성 → 쿠폰 사용의 순서로 실행

**해결: 원자적 업데이트(Atomic UPDATE) 적용**
- **선택 전략**: 원자적 UPDATE (`UPDATE ... WHERE status = 'AVAILABLE'`)
- **선택 이유**:
  - 쿠폰 사용은 `AVAILABLE → USED` 단방향 1회성 상태 전환으로, 조건부 UPDATE 한 번으로 완결되는 단순한 로직
  - 경합 빈도가 극히 낮아 `@Version` + 재시도(`@Retryable`) 인프라는 과도
  - `affected rows = 0`으로 즉시 충돌 감지 → "이미 사용된 쿠폰입니다" 같은 구체적 비즈니스 메시지 반환 가능
  - `save()` vs `saveAndFlush()` 고민, 커밋 시점 예외 문제가 근본적으로 사라짐
  - 기존 낙관적 락 방식은 `save()`를 사용할 경우 충돌 감지가 커밋 시점으로 지연되어 상세한 실패 원인을 전달할 수 없었고, `saveAndFlush()`를 사용하면 영속성 컨텍스트 전체 flush로 인한 부작용이 존재
- **변경 파일**:
  - `MemberCoupon` — `@Version` 필드 제거, `use()` 메서드 삭제 (상태 전환이 DB 쿼리에서 처리되므로 불필요)
  - `MemberCouponJpaRepository` — `@Modifying(flushAutomatically = true, clearAutomatically = true)` + `@Query` JPQL로 `updateStatusToUsed()` 메서드 추가
  - `MemberCouponRepository` — `updateStatusToUsed(Long id, Long orderId, ZonedDateTime usedAt)` 메서드 선언
  - `MemberCouponRepositoryImpl` — `updateStatusToUsed()` 위임 구현
  - `CouponService.useCoupon()` — 반환타입 `MemberCoupon` → `void`, `memberCoupon.use()` + `save()` → `updateStatusToUsed()` + `affected rows` 검증으로 변경, `affected rows = 0`이면 `CONFLICT` 예외
- **`@Modifying` 옵션 설계**:
  - `flushAutomatically = true`: UPDATE 실행 전에 영속성 컨텍스트의 pending 변경사항(재고 차감 등)을 먼저 flush하여 데이터 유실 방지
  - `clearAutomatically = true`: UPDATE 실행 후 1차 캐시를 클리어하여, 이후 조회 시 DB의 최신 값을 읽도록 보장
- 동시성 테스트(`CouponUsageConcurrencyTest`): `TransactionTemplate`으로 트랜잭션 감싸서 5개 스레드가 동시에 같은 MemberCoupon 사용 시도 → 1개 성공(`affected rows = 1`), 4개 `CONFLICT` 예외, 최종 상태 USED 검증 통과

---

### 시나리오 3: 재고 차감 동시성 (동시 주문)

**문제 위치**: `ProductService.deductStock()`, `ProductOption.deductStock()`, `OrderService.prepareOrderItems()`

**현재 문제**: 재고 확인과 차감이 비원자적으로 수행

```
Thread A: stockQuantity = 5 읽음, 3개 주문 → hasEnoughStock(3) ✓
Thread B: stockQuantity = 5 읽음, 3개 주문 → hasEnoughStock(3) ✓
Thread A: 5 - 3 = 2로 저장
Thread B: 5 - 3 = 2로 저장  ← Lost Update! 총 6개 판매됐지만 재고는 3만 차감, 초과 판매 발생
```

**원인 분석**:
- `productOptionJpaRepository.findByIdAndDeletedAtIsNull()`에 락이 없음
- `hasEnoughStock()` 체크와 `this.stockQuantity -= quantity` 연산이 분리되어 있음
- `@Version` 필드도 없어서 낙관적 락도 사용 불가
- 동시 주문 시 같은 재고 수량을 읽고 각각 차감하여 초과 판매 발생

**관련 코드**:
- `ProductService.deductStock()` — `findOptionById()` 락 없는 조회 후 `option.deductStock(quantity)` 호출
- `ProductOption.deductStock()` — `hasEnoughStock()` 체크 → `this.stockQuantity -= quantity` (비원자적)
- `OrderService.prepareOrderItems()` — 반복문 내에서 `productService.deductStock()` 호출

**해결: 비관적 락(SELECT FOR UPDATE) 적용**
- **선택 전략**: 비관적 락 (`PESSIMISTIC_WRITE`)
- **선택 이유**:
  - 재고 차감은 인기 상품의 경우 경합 빈도가 매우 높음 (동시 주문)
  - 초과 판매는 치명적이므로 확실한 직렬화가 필요
  - 도메인 검증(`hasEnoughStock`)을 보존하면서 락으로 보호
  - 비관적 락 실패 시 재시도가 불필요 (순차 대기 후 처리)
- **변경 파일**:
  - `ProductOptionJpaRepository` — `@Lock(PESSIMISTIC_WRITE)` + `@Query` JPQL로 `findByIdWithLockAndDeletedAtIsNull()` 메서드 추가
  - `ProductRepository` — `findOptionByIdWithLock(Long optionId)` 메서드 선언
  - `ProductRepositoryImpl` — `findOptionByIdWithLock()` 구현
  - `ProductService.deductStock()` — `findOptionById()` → `findOptionByIdWithLock()` 변경, Javadoc으로 트랜잭션 필수 조건 명시
- 동시성 테스트(`StockDeductConcurrencyTest`): `TransactionTemplate`으로 트랜잭션 감싸서 재고 5개 상품에 10개 스레드가 동시에 1개씩 차감 → 5개 성공, 5개 실패, 최종 재고 0 검증 통과

---

### 시나리오 4: 포인트 잔액 동시성 (동시 주문/충전)

**문제 위치**: `PointService.chargePoint()` / `usePoint()`, `Point.charge()` / `use()`

**현재 문제**: Read-Decide-Write 패턴에 락이 없음

```
회원 A의 포인트 잔액: 1000원

Thread 1: balance = 1000 읽음 → use(800) → 잔액 체크 OK → balance = 200 저장
Thread 2: balance = 1000 읽음 → use(800) → 잔액 체크 OK → balance = 200 저장

실제 차감: 800원 (200만 남음)
정상 차감: 1600원 (잔액 부족으로 하나는 실패해야 함)
→ 1400원이 공짜로 사용됨
```

**원인 분석**:
- `pointRepository.findByMemberId()`에 어떤 락도 적용되어 있지 않음
- `Point.use()`의 잔액 체크(`this.balance < amount`)와 차감(`this.balance -= amount`)이 비원자적
- 동시 주문 시 같은 잔액을 읽고 각각 차감하여 초과 사용 발생
- 포인트는 금전적 가치를 가지므로 데이터 정합성이 필수

**검토한 락 전략**:

| 전략 | 장점 | 단점 |
|------|------|------|
| **낙관적 락** | DB 락 미점유, 경합 시 빠른 실패 | 주문 트랜잭션 전체가 롤백됨 (재고 차감 포함). 재시도 시 재고 소진/쿠폰 상태 변경 가능. 포인트가 충분해도 "동시 요청" 오류 발생 |
| **비관적 락 (채택)** | 순차 처리로 잔액 충분하면 둘 다 성공 | 행 잠금 대기 |

**선택: 비관적 락 (PESSIMISTIC_WRITE)**
- **핵심 근거**: 포인트는 회원당 1개 row (`member_id` unique)이므로, 비관적 락이 걸려도 **해당 회원의 row만 잠기고 다른 회원은 영향 없음** → 비관적 락의 단점(대기 오버헤드)이 사실상 발생하지 않음
- 낙관적 락은 충돌 시 주문 전체 롤백 후 재시도가 필요한데, `OrderFacade.createOrder()` 트랜잭션 안에서 재고 차감/쿠폰 사용 이후에 포인트가 실행되므로 포인트만 재시도할 수 없음
- 비관적 락은 순차 대기 후 처리하므로, 잔액만 충분하면 두 주문 모두 성공 가능

**변경 파일**:
  - `PointJpaRepository` — `@Lock(PESSIMISTIC_WRITE)` + `@Query` JPQL로 `findByMemberIdWithLockAndDeletedAtIsNull()` 메서드 추가
  - `PointRepository` — `findByMemberIdWithLock(Long memberId)` 메서드 선언
  - `PointRepositoryImpl` — `findByMemberIdWithLock()` 구현
  - `PointService.chargePoint()`, `usePoint()` — `findByMemberId()` → `findByMemberIdWithLock()` 변경, Javadoc으로 트랜잭션 필수 조건 명시

---

## 주문 흐름 동시성 제어 적용

### 혼합 동시성 제어 전략 (비관적 락 + 원자적 UPDATE)

`OrderFacade.createOrder()`의 단일 `@Transactional` 내에서 여러 동시성 제어 전략을 혼합 사용:

| 대상 | 전략 | 이유 |
|------|------|------|
| `ProductOption` (재고) | 비관적 락 | 경합 빈도 높음, 초과 판매 방지 필수, 순차 대기 허용 |
| `MemberCoupon` (쿠폰 사용) | 원자적 UPDATE | 단순 상태 전환(`AVAILABLE→USED`), 경합 극히 낮음, 즉시 충돌 감지 + 구체적 에러 메시지 |
| `Point` (포인트 잔액) | 비관적 락 | 회원당 1 row라 다른 회원에 영향 없음, 잔액 충분 시 순차 성공 허용 |

### 트랜잭션 롤백 보장

쿠폰 원자적 UPDATE 실패(`CoreException CONFLICT`) 시:
1. Spring이 `@Transactional`에 의해 트랜잭션 전체를 롤백
2. 이미 차감된 재고(`ProductOption.stockQuantity`)도 롤백되어 원복
3. 생성된 주문(`Order`)도 롤백되어 삭제

→ 비관적 락(재고)과 원자적 UPDATE(쿠폰)를 한 트랜잭션에서 혼합해도 원자성이 보장됨

---

## 쿠폰 사용 동시성 제어 — 낙관적 락 → 원자적 업데이트 전환

### 배경
- 기존에 `MemberCoupon`에 `@Version`을 적용하여 낙관적 락으로 동시 쿠폰 사용을 방지
- `save()`를 사용하면 충돌 감지가 커밋 시점으로 지연되어 구체적 에러 메시지 전달 불가
- `saveAndFlush()`를 사용하면 영속성 컨텍스트 전체 flush 부작용 발생
- 멘토 피드백: 쿠폰 사용은 단순 상태 전환이므로 낙관적 락보다 원자적 업데이트가 적합. 낙관적 락을 쓸 거면 `saveAndFlush()` + `@Retryable` 조합이 실무 패턴인데, 이 시나리오에는 과도함

### 검토한 전략

| 전략 | 장점 | 단점 |
|------|------|------|
| **낙관적 락 + `save()`** | flush 부작용 없음 | 충돌 감지가 커밋 시점, 구체적 에러 메시지 불가 |
| **낙관적 락 + `saveAndFlush()`** | 즉시 충돌 감지 | 영속성 컨텍스트 전체 flush, 락 보유 시간 증가, `@Retryable` 인프라 필요 |
| **비관적 락** | 충돌 차단, 순차 처리 | 경합 극히 낮은 시나리오에 매번 행 잠금은 과도 |
| **원자적 UPDATE (채택)** | 가장 단순, 즉시 충돌 감지, 구체적 메시지 | 영속성 컨텍스트를 우회하므로 `@Modifying` 옵션 필요 |

### 결정: 원자적 업데이트

**선택 근거**:
- `AVAILABLE → USED`는 조건부 단일 UPDATE로 완결되는 단순 로직
- `affected rows = 0`이면 즉시 "이미 사용된 쿠폰입니다" 구체적 메시지 반환 가능
- `@Version` 컬럼, 재시도 인프라(`@Retryable`) 불필요
- `save()` vs `saveAndFlush()` 고민이 근본적으로 사라짐

**구현**:
```java
// MemberCouponJpaRepository
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("UPDATE MemberCoupon mc SET mc.status = 'USED', mc.orderId = :orderId, mc.usedAt = :usedAt " +
        "WHERE mc.id = :id AND mc.status = 'AVAILABLE'")
int updateStatusToUsed(@Param("id") Long id, @Param("orderId") Long orderId, @Param("usedAt") ZonedDateTime usedAt);

// CouponService
int updatedCount = memberCouponRepository.updateStatusToUsed(memberCouponId, orderId, ZonedDateTime.now());
if (updatedCount == 0) {
    throw new CoreException(ErrorType.CONFLICT, "이미 사용된 쿠폰입니다.");
}
```

**`@Modifying` 옵션 설계**:
- `flushAutomatically = true`: UPDATE 실행 전에 영속성 컨텍스트의 pending 변경사항(재고 차감 등)을 먼저 flush하여 데이터 유실 방지
- `clearAutomatically = true`: UPDATE 실행 후 1차 캐시를 클리어하여, 이후 조회 시 DB의 최신 값을 읽도록 보장
- 두 옵션 없이 사용할 경우, `OrderFacade.createOrder()` 트랜잭션 내에서 앞서 dirty checking으로 관리되던 재고 차감 변경이 flush되지 않고 사라지는 문제가 발생

**변경 파일**:
- `MemberCoupon` — `@Version` 필드 제거, `use()` 메서드 삭제
- `MemberCouponJpaRepository` — `updateStatusToUsed()` JPQL 추가
- `MemberCouponRepository` — `updateStatusToUsed()` 선언
- `MemberCouponRepositoryImpl` — `updateStatusToUsed()` 위임 구현
- `CouponService.useCoupon()` — 반환타입 `void`, 원자적 UPDATE + `affected rows` 검증
- `MemberCouponTest` — `Use` 테스트 클래스 삭제
- `CouponServiceTest` — `UseCoupon` 테스트를 원자적 업데이트 검증으로 변경, "이미 사용된 쿠폰" CONFLICT 테스트 추가
- `CouponServiceIntegrationTest` — `useCoupon()` void 반환에 맞게 수정
- `CouponUsageConcurrencyTest` — 주석/설명을 원자적 업데이트로 변경

### Domain Service @Transactional(REQUIRED) 제거

**배경**: `CouponService.useCoupon()`과 `ProductService.deductStock()`에 `@Transactional`(기본 REQUIRED)이 있었으나, 둘 다 `OrderFacade.createOrder()`의 트랜잭션 안에서만 호출됨.

**결정**: 두 메서드에서 `@Transactional` 제거
- 현재 호출 경로가 모두 Facade(트랜잭션 있음)를 경유하므로 동작 변경 없음
- 트랜잭션 경계가 Facade에만 존재한다는 것을 명확히 하기 위함
- 향후 Facade 없이 직접 호출하는 경우를 대비해 Javadoc으로 트랜잭션 필수 조건 명시

**변경 파일**:
- `CouponService.useCoupon()` — `@Transactional` 제거, Javadoc 추가
- `ProductService.deductStock()` — `@Transactional` 제거, Javadoc 추가

---

## 주문 API 명시적 로깅 설계

### 배경
- 기존에는 주문 성공/실패에 대한 명시적 로깅이 없었음
- 글로벌 예외 핸들러(`ApiControllerAdvice`)에서 범용 예외 로그만 남기고 있어 "주문"이라는 비즈니스 맥락이 부재
- 주문은 재고 차감, 쿠폰 사용, 포인트 차감 등 여러 도메인을 조율하는 핵심 로직이므로, 성공/실패를 추적할 수 있는 명시적 로깅이 필요

### 검토한 방안

#### 방안 1: Facade 레이어에 직접 로깅
- `OrderFacade.createOrder()`에 `@Slf4j`를 붙이고 단계별 try-catch + 로깅
- **장점**: 각 단계(재고/쿠폰/포인트)의 실패 맥락을 남길 수 있음
- **단점**: `@Transactional`이 Facade에 걸려 있어, try-catch로 예외를 잡으면 트랜잭션 롤백이 의도대로 동작하지 않을 위험. 반드시 re-throw해야 하므로 실수 여지 있음

#### 방안 2: AOP(Aspect)로 횡단 관심사 분리
- `@Around` 어드바이스로 Facade 메서드 실행 전후에 로깅
- **장점**: 비즈니스 코드 오염 없음, 일관된 로깅 패턴 확장 가능
- **단점**: 어떤 단계에서 실패했는지 세부 맥락 파악 불가 (메서드 진입/종료만 알 수 있음), 현재 프로젝트 규모에서 오버엔지니어링

#### 방안 3: 트랜잭션 분리 (Facade → 내부 서비스로 @Transactional 이동)
- `@Transactional`을 별도 서비스로 내리고, Facade에서 try-catch + 로깅
- **장점**: 트랜잭션과 로깅이 완전 분리, 롤백 영향 없음
- **단점 (채택하지 않은 핵심 이유)**:
  - `OrderFacade.createOrder()`는 4개 도메인 서비스(Order, Coupon, Point, Cart)를 하나의 트랜잭션으로 묶고 있음
  - DDD 관점에서 여러 애그리거트를 조율하는 유스케이스의 트랜잭션은 Application Layer(Facade)에 두는 것이 정석
  - 트랜잭션을 단일 도메인 서비스로 내리면 원자성이 깨지거나(각 서비스별 개별 트랜잭션), 도메인 경계가 위반됨(하나의 도메인 서비스가 다른 도메인 서비스를 직접 의존)

#### 방안 4: Controller 레이어에서 로깅 (채택)
- `OrderV1Controller`에서 Facade 호출을 try-catch로 감싸고 성공/실패 로깅, 실패 시 re-throw
- **장점**: 트랜잭션 바깥이라 롤백에 영향 없음, Facade/Service 구조 변경 없음, 구현이 단순
- **단점**: 실패 단계(재고/쿠폰/포인트)의 세부 맥락은 알기 어려움 (예외 메시지로 추론)

### 결정: Controller 레이어에서 로깅 (방안 4)

**선택 근거**:
- Facade의 `@Transactional` 범위(4개 도메인 서비스 조율)를 변경하지 않아 DDD 원칙을 유지
- 트랜잭션 바깥에서 로깅하므로 롤백 동작에 영향 없음
- 실패 원인은 예외 메시지에 담겨 있고, `ApiControllerAdvice`에서도 로깅하므로 충분히 추적 가능

**로깅 내용**:

| 시점 | 레벨 | 로그 항목 |
|------|------|----------|
| 주문 성공 | `INFO` | memberId, orderId, totalAmount, discountAmount, usedPoints, paymentAmount |
| 주문 실패 | `WARN` | memberId, itemCount + 예외 스택트레이스 |

**구현 위치**: `OrderV1Controller.createOrder()`

---

## 락 예외처리 및 에러 리턴 보완

### 배경
- 기존 동시성 제어(비관적 락, 낙관적 락, Atomic UPDATE)가 적용되어 있었으나, 락 충돌/타임아웃 시 예외가 글로벌 `Throwable` 핸들러로 빠져 클라이언트에게 500 INTERNAL_SERVER_ERROR가 반환되는 문제
- `decrementLikeCount`의 `WHERE likeCount > 0` 가드가 0 rows 반환 시 사일런트 무시되는 문제

### 변경 내용

#### (1) `ObjectOptimisticLockingFailureException` 글로벌 핸들러 추가
- **대상**: `MemberCoupon`의 `@Version` 낙관적 락 충돌 시
- **변경**: `ApiControllerAdvice`에 `@ExceptionHandler(ObjectOptimisticLockingFailureException.class)` 추가
- **응답**: 409 CONFLICT + `"동시 요청으로 처리에 실패했습니다. 다시 시도해주세요."`
- **기존**: `Throwable` 핸들러에서 500 + `"일시적인 오류가 발생했습니다."` 반환

#### (2) `PessimisticLockingFailureException` 글로벌 핸들러 추가
- **대상**: `Coupon`, `ProductOption`의 비관적 락 타임아웃/데드락 시
- **변경**: `ApiControllerAdvice`에 `@ExceptionHandler(PessimisticLockingFailureException.class)` 추가
- **응답**: 409 CONFLICT + `"동시 요청으로 처리에 실패했습니다. 다시 시도해주세요."`
- **기존**: `Throwable` 핸들러에서 500 + `"일시적인 오류가 발생했습니다."` 반환

#### (3) `decrementLikeCount` 사일런트 실패 감지 — 반환값 `int` 변경 + 로깅
- **문제**: `decrementLikeCount`가 `void` 반환이라 `WHERE likeCount > 0` 조건에 의해 0 rows 업데이트되어도 무시됨. Like 상태는 N으로 바뀌지만 Product.likeCount는 변경 안 되는 사일런트 불일치 발생 가능
- **변경**:
  - `ProductJpaRepository.decrementLikeCount()` — 반환값 `void` → `int`
  - `ProductRepository.decrementLikeCount()` — 반환값 `void` → `int`
  - `ProductRepositoryImpl.decrementLikeCount()` — 반환값 `void` → `int`
  - `LikeService` — `@Slf4j` 추가, `unlike()`에서 반환값이 0이면 WARN 로그 출력: `"좋아요 수 감소 실패: productId={}, likeCount가 이미 0입니다."`

#### (4) `LikeService`의 `@Transactional` 미적용 — 현재 구조 유지
- **확인 결과**: `ProductFacade.like()`/`unlike()`에서 이미 `@Transactional`이 선언되어 있어 `LikeService`의 두 쓰기 작업(Like 저장 + likeCount UPDATE)은 Facade 트랜잭션 내에서 원자적으로 실행됨
- **결론**: 프로젝트의 "트랜잭션 경계는 Facade에서 관리" 패턴에 부합하므로 변경 불필요

### 변경 파일
- `ApiControllerAdvice` — `ObjectOptimisticLockingFailureException`, `PessimisticLockingFailureException` 핸들러 추가
- `ProductJpaRepository` — `decrementLikeCount()` 반환값 `int`
- `ProductRepository` — `decrementLikeCount()` 반환값 `int`
- `ProductRepositoryImpl` — `decrementLikeCount()` 반환값 `int`
- `LikeService` — `@Slf4j` 추가, `unlike()` 0 rows 시 WARN 로깅

---

## 장바구니 동시성 제어 — Hard Delete + 원자적 UPSERT 적용

### 배경
- 기존 `CartService.addToCart()`는 Read-then-Write 패턴 (find → addQuantity → save)
- 동일 사용자의 동시 요청 시 Lost Update 또는 중복 INSERT 가능성 존재

### 검토 과정

#### 1차 시도: 원자적 UPDATE + 조건부 INSERT 분리
- `@Modifying @Query`로 `UPDATE cart_item SET quantity = quantity + :qty` 쿼리 작성
- 영향받은 row가 0이면 (기존 아이템 없으면) INSERT 수행
- **문제점**:
  - `@Modifying` 쿼리는 트랜잭션 필수 → Facade에서 트랜잭션 관리하는 구조와 충돌 (통합 테스트에서 `TransactionRequiredException`)
  - `clearAutomatically = true` 필요 → 영속성 컨텍스트 수동 관리
  - UPDATE 후 응답을 위해 SELECT가 추가로 필요 → 쿼리 증가
  - INSERT와 UPDATE가 분리되어 있어 JPA 표준 패턴이 아닌 비직관적 코드

#### 2차 시도: INSERT ... ON DUPLICATE KEY UPDATE (UPSERT)
- INSERT와 UPDATE를 단일 쿼리로 합치면 위 문제 해결
- **선행 조건**: `(member_id, product_option_id)` 유니크 제약조건 필요
- **Soft Delete와의 충돌**: 기존에 `deleted_at IS NULL` 조건으로 soft delete를 사용 → MySQL은 partial unique index 미지원 → 삭제된 row와 유니크 충돌 발생

#### 최종 결정: Hard Delete로 변경 + UPSERT
- 장바구니는 삭제 이력을 보존할 비즈니스 이유가 없음 (주문 완료 후 삭제되는 임시 데이터)
- Hard Delete로 변경하면 `(member_id, product_option_id)` 유니크 제약 적용 가능
- `INSERT ... ON DUPLICATE KEY UPDATE quantity = quantity + :qty`로 단일 원자적 쿼리

### 변경 내용

#### Hard Delete 전환
- 기존: `UPDATE cart_item SET deleted_at = CURRENT_TIMESTAMP WHERE ...` (soft delete)
- 변경: `DELETE FROM CartItem WHERE ...` (hard delete)
- `CartItem`은 `BaseEntity`를 상속하지만 `deleted_at` 컬럼을 사용하지 않음

#### 원자적 UPSERT
```sql
INSERT INTO cart_item (member_id, product_option_id, quantity, created_at, updated_at)
VALUES (:memberId, :productOptionId, :quantity, NOW(), NOW())
ON DUPLICATE KEY UPDATE quantity = quantity + :quantity, updated_at = NOW()
```
- 신규 아이템: INSERT
- 기존 아이템: 수량 원자적 증가
- Read-then-Write 패턴 완전 제거

#### 재고 검증 단순화
- 기존: 장바구니 누적 수량 기준으로 재고 검증 (`totalQuantity = 기존 수량 + 추가 수량`)
- 변경: 이번에 담으려는 수량만 재고 검증 (`option.hasEnoughStock(quantity)`)
- 이유: 장바구니에 담는 시점의 누적 수량 기반 검증은 시간이 지나면 무의미. 최종 재고 검증은 주문 시점에 비관적 락으로 보호

#### 반환값 변경
- 기존: `CartItem` 반환 (응답에 id, quantity, createdAt 포함)
- 변경: `void` 반환 (UPSERT 후 별도 SELECT 불필요)
- Controller 응답: `ApiResponse<CartItemResponse>` → `ApiResponse<Void>`

### 변경 파일
- `CartItem` — `@Table(uniqueConstraints)` 추가 `(member_id, product_option_id)`
- `CartJpaRepository` — `upsert()` native query 추가, 삭제 쿼리 soft → hard delete 변경, `deleted_at IS NULL` 조건 제거
- `CartRepository` — `addQuantity()` 제거, `upsert()` 추가
- `CartRepositoryImpl` — `upsert()` 구현
- `CartService.addToCart()` — 반환값 `CartItem` → `void`, UPSERT 방식으로 전면 변경
- `CartFacade.addToCart()` — 반환값 `CartItem` → `void`
- `CartV1Controller` — 응답 `ApiResponse<CartItemResponse>` → `ApiResponse<Void>`
- `CartV1Dto` — `CartItemResponse` 제거
- `CartServiceTest` — UPSERT 기반 mock 설정으로 전면 변경
- `CartServiceIntegrationTest` — `@Transactional` 추가, UPSERT 기반 검증으로 변경
- `CartFacadeTest` — void 반환 기반으로 변경
- `CartV1ControllerTest` — `doNothing()`/`doThrow()` 방식으로 변경, `CartItemResponse` 검증 제거
- `CartV1ApiE2ETest` — 응답 타입 `ApiResponse<Void>`, 존재하지 않는 옵션 예외 400 BAD_REQUEST로 변경

### 설계 문서 반영
- `01-requirements.md` — 장바구니 담기 설명 업데이트 (UPSERT, Hard Delete)
- `02-sequence-diagrams.md` — 장바구니 담기 시퀀스 UPSERT 단일 쿼리로 변경
- `03-class-diagram.md` — CartItem(Hard Delete, UNIQUE), CartService/CartRepository/CartFacade 시그니처 변경
- `04-erd.md` — CART_ITEM 테이블에서 deleted_at 제거, Hard Delete 설명 추가

---

## `OrderFacade.createOrder()` totalAmount 중복 계산 제거

### 배경
- `OrderFacade.createOrder()`에서 `Order.calculateTotalAmount(orderItems)`로 `totalAmount`를 미리 계산하여 `calculateApplicableAmount()`에 넘기고 있었음
- 그러나 `Order.create()` 내부에서도 동일하게 `calculateTotalAmount(items)`를 호출하여 **같은 계산이 두 번** 수행됨
- Facade에 `totalAmount` 변수가 존재하면 `Order.create()` 내부 계산과 혼동될 수 있음

### 변경 내용
- `OrderFacade.createOrder()` — `totalAmount` 변수 제거
- `calculateApplicableAmount()` — `totalAmount` 파라미터 제거, `CART` scope일 때 `orderItems`에서 직접 합산
- `Order.create()` 내부의 `calculateTotalAmount()` 호출이 유일한 총액 계산 지점이 됨

### 변경 파일
- `OrderFacade` — `createOrder()`, `calculateApplicableAmount()` 수정

---

## 주문 트랜잭션 순서 최적화 — 쿠폰 검증을 재고 차감 전으로 이동

### 배경
- 기존 `OrderFacade.createOrder()`의 실행 순서: 재고 차감(비관적 락) → 쿠폰 검증 → 주문 생성 → 쿠폰 사용 → 포인트 사용 → 장바구니 삭제
- 재고 차감 시 `SELECT ... FOR UPDATE`로 `ProductOption` 행 락을 획득한 후, 쿠폰 검증(DB 조회 3회) 동안 불필요하게 락을 보유
- 인기 상품에 동시 주문이 몰릴 때, 쿠폰 검증 시간만큼 다른 주문들의 대기 시간이 증가
- 쿠폰 검증 실패 시에도 이미 재고 락을 잡고 있어 불필요한 자원 점유 발생

### 변경 내용
- **실행 순서 변경**: 쿠폰 검증/할인 계산 → 재고 차감(비관적 락) → 주문 생성 → 쿠폰 사용 → 포인트 사용 → 장바구니 삭제
- **`calculateApplicableAmount()` 변경**: 기존에는 `OrderItem`(스냅샷) 기반으로 적용 대상 금액을 계산했으나, 재고 차감 전에 호출되므로 `OrderItem`이 아직 없음. `ProductService.findById()`로 상품 정보를 직접 조회하여 가격 × 수량으로 계산하도록 변경
- **`OrderFacade`에 `ProductService` 의존 추가**: 쿠폰 적용 대상 금액 산정을 위해 상품 가격 조회 필요

### 효과
- 쿠폰 검증 실패 시 재고 락을 잡지 않아 불필요한 대기 방지
- 재고 비관적 락 보유 시간 단축 (쿠폰 검증에 소요되는 DB 조회 시간만큼)
- 트랜잭션의 원자성과 롤백 보장은 동일하게 유지

### 변경 파일
- `OrderFacade` — `createOrder()` 순서 변경, `calculateApplicableAmount()` 파라미터 `List<OrderItem>` → `List<OrderItemRequest>` 변경, `ProductService` 의존 추가
- `OrderFacadeTest` — `ProductService` mock 추가, 쿠폰 테스트에서 `productService.findById()` mock 설정, 쿠폰 에러 테스트에서 `orderService.prepareOrderItems()` 미호출 검증 추가

### 설계 문서 반영
- `01-requirements.md` — 주문 요청 Facade 처리 순서 업데이트
- `02-sequence-diagrams.md` — 주문 요청 시퀀스 다이어그램에서 쿠폰 검증을 재고 차감 전으로 이동
- `03-class-diagram.md` — OrderFacade에 ProductService 의존 추가

---

## 동시성 제어 보완 — 4개 항목 수정

### 배경
기존 동시성 제어가 적용되어 있었으나, 분석 결과 아래 4개 항목에서 잠재적 문제를 발견하여 보완 조치 수행.

### (1) 쿠폰 다운로드 `DataIntegrityViolationException` → 409 응답 변환

**문제**: `CouponService.downloadCoupon()`에서 같은 회원의 동시 다운로드 시, 애플리케이션 레벨 중복 검사를 둘 다 통과한 후 `member_coupon` 테이블의 `(member_id, coupon_id)` unique constraint 위반으로 `DataIntegrityViolationException`이 발생. 이 예외가 비즈니스 예외로 변환되지 않아 `Throwable` 핸들러에서 500 INTERNAL_SERVER_ERROR로 응답됨.

**해결**: `ApiControllerAdvice`에 `DataIntegrityViolationException` 핸들러 추가 → 409 CONFLICT + "데이터 충돌이 발생했습니다. 다시 시도해주세요." 응답.

**변경 파일**:
- `ApiControllerAdvice` — `DataIntegrityViolationException` 핸들러 추가

### (2) 좋아요 중복 생성 방지 — Like 엔티티 UniqueConstraint 추가

**문제**: `Like` 엔티티에 `(member_id, product_id)` unique constraint가 없어, 같은 회원이 동시에 좋아요 요청 시 중복 레코드 생성 + `likeCount` 이중 증가 가능.

**해결**: `Like` 엔티티에 `@UniqueConstraint(columnNames = {"member_id", "product_id"})` 추가. 중복 INSERT 시 `DataIntegrityViolationException` 발생 → Fix (1)의 글로벌 핸들러에서 409 CONFLICT로 처리.

**변경 파일**:
- `Like` — `@Table(uniqueConstraints)` 추가

### (3) 포인트 동시성 테스트 추가

**문제**: 포인트에 비관적 락(`PESSIMISTIC_WRITE`)이 적용되어 있었으나 동시성 통합 테스트가 부재.

**해결**: `PointConcurrencyTest` 신규 작성.
- **동시 사용 테스트**: 잔액 5000원에 10개 스레드가 1000원씩 동시 사용 → 5개 성공, 5개 BAD_REQUEST 실패, 최종 잔액 0 검증
- **충전/사용 동시 테스트**: 잔액 10000원에 5개 충전(1000원) + 5개 사용(1000원) 동시 수행 → 10개 모두 성공, 최종 잔액 10000원 검증

**기존 단위/통합 테스트 수정**:
- `PointServiceTest` — chargePoint/usePoint mock 대상을 `findByMemberId` → `findByMemberIdWithLock`으로 변경 (비관적 락 적용에 따른 mock 불일치 해소)
- `PointServiceIntegrationTest` — `@Transactional` 추가 (비관적 락 쿼리에 트랜잭션 컨텍스트 필요)

**변경 파일**:
- `PointConcurrencyTest` — 신규 생성
- `PointServiceTest` — mock 메서드명 변경 (5개소)
- `PointServiceIntegrationTest` — `@Transactional` 추가

### (4) 주문 생성 데드락 방지 — 락 획득 순서 일관화

**문제**: `OrderService.prepareOrderItems()`에서 여러 옵션의 재고를 비관적 락(`SELECT ... FOR UPDATE`)으로 차감할 때, 옵션 ID 순서가 보장되지 않으면 데드락 발생 가능.
```
Thread A: Lock optionId=2 → Lock optionId=1 (대기)
Thread B: Lock optionId=1 → Lock optionId=2 (대기)
→ 데드락
```

**해결**: `prepareOrderItems()` 진입 시 `itemRequests`를 `productOptionId` 오름차순으로 정렬하여 락 획득 순서를 일관되게 유지.

**변경 파일**:
- `OrderService` — `prepareOrderItems()`에 정렬 로직 추가

### 설계 문서 반영
- `03-class-diagram.md` — Like 엔티티에 UniqueConstraint 추가, PointRepository에 `findByMemberIdWithLock` 반영
- `04-erd.md` — PRODUCT_LIKE 테이블에 `(member_id, product_id)` UNIQUE 제약 추가, MEMBER_COUPON에서 version 컬럼 제거 (원자적 UPDATE 전환)

---

## 동시성 제어 현황 (최종)

### 주문 생성 — `OrderFacade.createOrder()` (`@Transactional`)

| 단계 | 대상 엔티티 | 보호 대상 | 락 전략 | 비고 |
|:----:|------------|----------|---------|------|
| 1 | `Coupon`, `MemberCoupon` | 쿠폰 유효성 검증 | 락 없음 (읽기 전용) | 할인 금액 계산만 수행. TOCTOU 갭은 4단계 원자적 UPDATE에서 방어 |
| 2 | `ProductOption` | `stockQuantity` (재고) | **비관적 락** (`SELECT FOR UPDATE`) | `findProductOnly()`로 Product만 조회하여 1차 캐시 오염 방지. 옵션 ID 오름차순 정렬로 데드락 방지 |
| 3 | `Order` | 주문 생성 | 락 없음 | 신규 INSERT |
| 4 | `MemberCoupon` | `status` (AVAILABLE → USED) | **원자적 UPDATE** (`@Modifying @Query`) | `UPDATE ... WHERE status='AVAILABLE'` — `affected rows = 0`이면 CONFLICT 예외 → 트랜잭션 전체 롤백 |
| 5 | `Point` | `balance` (포인트 잔액) | **비관적 락** (`SELECT FOR UPDATE`) | 회원당 1 row라 다른 회원 영향 없음. 잔액 충분 시 순차 성공 |
| 6 | `CartItem` | 장바구니 삭제 | 락 없음 | `@Modifying` JPQL DELETE — 이미 삭제된 경우 0 rows (멱등) |

### 좋아요 — `ProductFacade.like()` / `unlike()` (`@Transactional`)

| 대상 엔티티 | 보호 대상 | 락 전략 | 비고 |
|------------|----------|---------|------|
| `Product` | `likeCount` (좋아요 수) | **Atomic UPDATE** (`@Modifying @Query`) | `UPDATE product SET like_count = like_count + 1` — DB 레벨 원자적 처리로 Lost Update 방지 |
| `Like` | 중복 생성 방지 | `(member_id, product_id)` **UNIQUE** | 동시 좋아요 시 `DataIntegrityViolationException` → 409 |

### 장바구니 담기 — `CartFacade.addToCart()` (`@Transactional`)

| 대상 엔티티 | 보호 대상 | 락 전략 | 비고 |
|------------|----------|---------|------|
| `CartItem` | `quantity` (수량) | **Atomic UPSERT** | `INSERT ... ON DUPLICATE KEY UPDATE quantity = quantity + :qty` — Hard Delete + `(member_id, product_option_id)` UNIQUE로 단일 쿼리 처리 |

### 쿠폰 다운로드 — `CouponFacade.downloadCoupon()` (`@Transactional`)

| 대상 엔티티 | 보호 대상 | 락 전략 | 비고 |
|------------|----------|---------|------|
| `MemberCoupon` | 중복 다운로드 방지 | `(member_id, coupon_id)` **UNIQUE** | 애플리케이션 레벨 중복 검사 + DB UNIQUE 제약 이중 방어. 동시 다운로드 시 `DataIntegrityViolationException` → 409 |

### 어드민 쿠폰 수정/삭제 — `AdminCouponFacade.updateCoupon()` / `deleteCoupon()` (`@Transactional`)

| 대상 엔티티 | 보호 대상 | 락 전략 | 비고 |
|------------|----------|---------|------|
| `Coupon` | 수정/삭제 일관성 | **비관적 락** (`SELECT FOR UPDATE`) | `findByIdWithLock()` — 어드민 수정/삭제와 고객 발급 간 충돌 방지 |

### 포인트 충전 — `PointService.chargePoint()` (Facade `@Transactional` 내에서 호출)

| 대상 엔티티 | 보호 대상 | 락 전략 | 비고 |
|------------|----------|---------|------|
| `Point` | `balance` (포인트 잔액) | **비관적 락** (`SELECT FOR UPDATE`) | 주문 포인트 사용과 동일 락. 충전/사용 동시 발생 시에도 순차 처리 |

---

## 코드 품질 개선 — 도메인 설계 위반 및 동시성 보완

전체 코드 리뷰를 통해 도메인 설계 위반과 동시성 위험 포인트를 식별하고 일괄 수정.

### (1) [P0] `LikeService.like()` TOCTOU + `DataIntegrityViolationException` 처리

**문제**: `findByMemberIdAndProductId` → `save` 사이에 TOCTOU 갭 존재. 동시 요청 시 UniqueConstraint 위반으로 `DataIntegrityViolationException` 발생.

**1차 해결 (try-catch)**: `DataIntegrityViolationException`을 catch하여 멱등하게 처리 — 동시 요청으로 이미 생성된 경우 기존 Like를 재조회하여 반환.

**1차 해결의 문제점**: `ProductFacade.like()`의 `@Transactional` 내부에서 `DataIntegrityViolationException`이 발생하면 트랜잭션이 rollback-only로 마킹됨. catch 블록에서 정상 반환하더라도 Facade의 `@Transactional` 종료 시 `UnexpectedRollbackException`이 발생하여 결국 500 에러가 반환됨.

**최종 해결 (전역 핸들러 위임)**: `CouponService.downloadCoupon()`과 동일한 패턴으로 try-catch를 제거하고, `DataIntegrityViolationException`은 `ApiControllerAdvice`의 글로벌 핸들러에서 409 CONFLICT로 처리. DIP 원칙도 준수 (도메인 레이어에서 Spring 인프라 예외를 알 필요 없음).

**변경 파일**:
- `LikeService` — `like()` 메서드에서 try-catch 제거, `DataIntegrityViolationException` import 제거

### (2) [P0] `restoreStock()` 비관적 락 미적용

**문제**: `deductStock()`은 `findOptionByIdWithLock()`으로 비관적 락을 획득하지만, `restoreStock()`은 일반 `findOptionById()`로 조회. 동시에 재고 복원과 차감이 발생하면 Lost Update 가능.

**해결**: `restoreStock()`도 `findOptionByIdWithLock()`으로 비관적 락 적용.

**변경 파일**:
- `ProductService` — `restoreStock()`에서 `findOptionById()` → `findOptionByIdWithLock()` 변경
- `ProductServiceTest` — `RestoreStock` 테스트의 mock 대상 메서드명 변경

### (3) [P1] `useCoupon()` 소유권 미검증

**문제**: `useCoupon(memberCouponId, orderId)` 시그니처에 `memberId`가 없어 소유권 검증 불가. `calculateCouponDiscount()`에서 검증한다는 전제에 의존하여, `useCoupon()`이 단독 호출되면 타인의 쿠폰 사용 가능.

**해결**: `useCoupon()` 시그니처에 `memberId` 파라미터 추가, 소유권 검증 로직 추가.

**변경 파일**:
- `CouponService` — `useCoupon(Long memberCouponId, Long orderId)` → `useCoupon(Long memberId, Long memberCouponId, Long orderId)` 시그니처 변경 + `memberId` 검증 추가
- `OrderFacade` — `useCoupon()` 호출 시 `memberId` 전달
- `CouponServiceTest` — `UseCoupon` 테스트 시그니처 변경 + 소유권 검증 실패 테스트 추가
- `CouponServiceIntegrationTest` — `useCoupon()` 호출 시그니처 변경
- `CouponUsageConcurrencyTest` — `useCoupon()` 호출 시그니처 변경
- `OrderFacadeTest` — `verify(couponService).useCoupon()` 시그니처 변경

### (4) [P1] `CouponService`에서 `DataIntegrityViolationException` 직접 catch — DIP 위반

**문제**: `CouponService.downloadCoupon()`에서 `org.springframework.dao.DataIntegrityViolationException`(Spring 인프라 예외)을 도메인 레이어에서 직접 catch. DIP 원칙에 따라 도메인 레이어는 인프라 예외를 알면 안 됨.

**해결**: `DataIntegrityViolationException` catch 제거. 동시 요청에 의한 UniqueConstraint 위반은 `ApiControllerAdvice`의 글로벌 핸들러에서 409 CONFLICT로 처리.

**변경 파일**:
- `CouponService` — `downloadCoupon()`에서 try-catch 제거, `DataIntegrityViolationException` import 제거

### (5) [P2] `Coupon`/`Brand` 엔티티 — 생성자에 비즈니스 불변식 검증 추가

**문제**: `Coupon` 생성자/`updateInfo()`에 `discountValue > 0`, `validFrom < validTo` 등 검증이 없음. `Brand` 생성자에 `name` 필수 검증 없음. `Point`, `ProductOption` 등 다른 엔티티는 불변식을 잘 보호하는 반면, 이 두 엔티티는 빈약한 도메인 모델(Anemic Domain Model) 상태.

**해결**:
- `Coupon` — `validateCoupon()` private 메서드 추가: 이름 필수, 할인 값 양수, 정률 할인 100% 이하, 유효기간 필수 및 시작일 < 종료일 검증. 생성자와 `updateInfo()` 양쪽에 적용.
- `Brand` — `validateName()` private 메서드 추가: 이름 필수. 생성자와 `updateInfo()` 양쪽에 적용. `updateInfo()`에 status null 검증 추가.

**변경 파일**:
- `Coupon` — `validateCoupon()` 추가, 생성자/`updateInfo()`에 적용
- `Brand` — `validateName()` 추가, 생성자/`updateInfo()`에 적용, status null 검증 추가

### (6) [P3] `CartItem.addQuantity()` Dead Code 제거

**문제**: UPSERT 리팩토링 이후 `addQuantity()` 메서드가 어디에서도 호출되지 않는 데드 코드.

**해결**: `CartItem.addQuantity()` 메서드 및 관련 테스트(`CartItemTest.AddQuantity`) 삭제.

**변경 파일**:
- `CartItem` — `addQuantity()` 메서드 삭제
- `CartItemTest` — `AddQuantity` 테스트 클래스 삭제

### (7) [P3] `CartItem` UniqueConstraint에 이름 추가

**문제**: `CartItem`의 `@UniqueConstraint`에 이름이 없어 `Like`의 `uk_likes_member_product`와 불일치.

**해결**: `@UniqueConstraint(name = "uk_cart_item_member_option", ...)` 이름 추가.

**변경 파일**:
- `CartItem` — `@UniqueConstraint`에 `name` 추가

### (8) [P3] `incrementLikeCount` 반환 타입 `int`로 통일

**문제**: `incrementLikeCount`는 `void`, `decrementLikeCount`는 `int` 반환으로 비대칭. `incrementLikeCount` 실패 여부 확인 불가.

**해결**: `incrementLikeCount` 반환 타입을 `int`로 변경하여 영향받은 행 수를 반환.

**변경 파일**:
- `ProductJpaRepository` — `incrementLikeCount()` 반환값 `void` → `int`
- `ProductRepository` — `incrementLikeCount()` 반환값 `void` → `int`
- `ProductRepositoryImpl` — `incrementLikeCount()` 반환값 `void` → `int`

### 글로벌 예외 처리 현황

| 예외 | HTTP 상태 | 메시지 | 발생 시나리오 |
|------|----------|--------|-------------|
| `ObjectOptimisticLockingFailureException` | 409 CONFLICT | "동시 요청으로 처리에 실패했습니다. 다시 시도해주세요." | `@Version` 충돌 (현재 해당 엔티티 없음, 범용 핸들러로 유지) |
| `PessimisticLockingFailureException` | 409 CONFLICT | "동시 요청으로 처리에 실패했습니다. 다시 시도해주세요." | 비관적 락 타임아웃/데드락 |
| `DataIntegrityViolationException` | 409 CONFLICT | "데이터 충돌이 발생했습니다. 다시 시도해주세요." | `Like` / `MemberCoupon` UNIQUE 제약 위반 (동시 좋아요/쿠폰 다운로드) |

---

## 비관적 락 1차 캐시 Stale Read 버그 수정

### 배경
`OrderConcurrencyTest`의 6개 테스트가 실패. 재고 5개인 상품에 10명이 동시 주문 시 5명만 성공해야 하는데, 10명 전원이 성공하는 Lost Update 발생.

### 원인 분석

`OrderFacade.createOrder()` → `OrderService.prepareOrderItems()` 내부에서 다음 순서로 실행:

```java
Product product = productService.findById(request.productId());       // ① 일반 조회
ProductOption option = productService.deductStock(optionId, quantity); // ② 비관적 락 조회
```

①에서 `ProductRepositoryImpl.findById()`가 `productOptionJpaRepository.findAllByProductIdAndDeletedAtIsNull()`을 호출하여 **ProductOption이 영속성 컨텍스트(1차 캐시)에 로드**됨.

②에서 `deductStock()` → `findOptionByIdWithLock()` → `entityManager.find(ProductOption.class, optionId, PESSIMISTIC_WRITE)` 호출 시:
- **DB 레벨**: `SELECT ... FOR UPDATE` 실행 → 행 락 획득 ✅
- **엔티티 레벨**: 이미 1차 캐시에 존재하므로 **DB에서 읽은 최신 값으로 갱신하지 않음** ❌

JPA 스펙상 `entityManager.find(class, id, lockMode)`는 엔티티가 이미 1차 캐시에 있을 때 **락은 걸지만 값을 refresh하지 않는다**.

### 실패 흐름 (수정 전)

```
Thread A: findById() → ProductOption(stock=5) 1차 캐시에 로드
Thread B: findById() → ProductOption(stock=5) 1차 캐시에 로드 (Thread B의 별도 PC)

Thread A: entityManager.find(PESSIMISTIC_WRITE) → 락 획득, 1차 캐시의 stock=5 그대로 사용
Thread A: deductStock(1) → stock=4, save(), commit → DB stock=4, 락 해제

Thread B: entityManager.find(PESSIMISTIC_WRITE) → 락 획득, 1차 캐시의 stock=5 그대로 사용 ← stale!
Thread B: deductStock(1) → stock=4, save(), commit → DB stock=4 (Thread A의 차감 덮어씀)

→ 2번 차감했지만 DB stock=4 (Lost Update)
```

### 1차 해결 (refresh 방식) — 이후 구조 변경으로 대체

최초에는 `entityManager.find()` + `entityManager.refresh(option, PESSIMISTIC_WRITE)` 조합으로 수정하여 1차 캐시에 이미 로드된 엔티티도 DB에서 최신 값으로 갱신하도록 했다.

`refresh(entity, lockMode)`는 **항상 DB에서 최신 값을 읽어 1차 캐시를 갱신**하면서 행 락을 건다.

이 방식의 문제점:
- `refresh()`는 해당 엔티티뿐 아니라, 같은 엔티티에 flush 안 된 변경(dirty state)이 있으면 그 변경이 날아감
- 현재 흐름에서는 안전하지만, 향후 코드 변경 시 같은 엔티티를 수정한 뒤 `refresh()`를 호출하면 수정이 소실되는 위험 존재
- 근본 원인(PC에 옵션이 미리 로드되는 것)을 해결하지 않고 우회하는 방식

### 최종 해결 (구조 변경) — ProductOption이 1차 캐시에 로드되지 않도록 분리

**핵심 원칙**: `deductStock()` 호출 전에 ProductOption이 1차 캐시에 로드되지 않도록 구조 변경

`Product.options`는 `@Transient` 필드로, `ProductRepositoryImpl.findById()`가 `productOptionJpaRepository.findAllByProductIdAndDeletedAtIsNull()`을 명시적으로 호출하여 옵션을 로드한다. 이 호출이 ProductOption을 1차 캐시에 올리는 원인이다.

그런데 `OrderItem.createSnapshot(product, option, quantity)`이 Product에서 사용하는 필드는 `id`, `name`, `price`, `supplyPrice`, `shippingFee`, `brand.id`, `brand.name`뿐이고, **options는 전혀 사용하지 않는다.** option 정보는 `deductStock()`이 반환하는 ProductOption에서 가져온다.

따라서 주문 흐름에서는 **Product만 조회하고 옵션은 로드하지 않는** `findProductOnly()` 메서드를 도입하여 1차 캐시 오염을 원천 차단한다.

```java
// 수정 전 — findById()가 ProductOption을 PC에 로드 → deductStock()에서 stale read
Product product = productService.findById(request.productId());       // ProductOption이 PC에 로드됨!
ProductOption option = productService.deductStock(optionId, quantity); // PC의 stale 값 사용

// 수정 후 — findProductOnly()는 옵션을 로드하지 않음 → deductStock()에서 DB 직접 조회
Product product = productService.findProductOnly(request.productId()); // Product만 로드, 옵션 X
ProductOption option = productService.deductStock(optionId, quantity);  // PC에 없으므로 DB에서 최신 값 + 락
```

`findOptionByIdWithLock()`은 원래 방식(`entityManager.find(class, id, PESSIMISTIC_WRITE)`)으로 복원. PC에 ProductOption이 없는 상태에서 호출되므로 DB에서 직접 로드 + 락 획득이 정상 동작한다.

### 수정 후 흐름

```
Thread A: findProductOnly() → Product만 로드 (ProductOption은 PC에 없음)
Thread B: findProductOnly() → Product만 로드 (ProductOption은 PC에 없음)

Thread A: entityManager.find(PESSIMISTIC_WRITE) → PC에 없으므로 DB에서 stock=5 로드 + 락 획득
Thread A: deductStock(1) → stock=4, save(), commit → DB stock=4, 락 해제

Thread B: entityManager.find(PESSIMISTIC_WRITE) → 락 대기 → 획득, DB에서 stock=4 로드 ✅
Thread B: deductStock(1) → stock=3, save(), commit → DB stock=3

→ 2번 차감, DB stock=3 (정확)
```

### 수정된 테스트 (6개)

| 테스트 | 시나리오 | 수정 전 | 수정 후 |
|--------|---------|--------|--------|
| **A-1** | 재고 5개, 10명 동시 주문 | 10명 전원 성공 (Lost Update) | 5명 성공, 5명 실패, 재고 0 |
| **A-2** | 재고 5개, 10명 쿠폰+포인트 동시 주문 | 10명 전원 성공, 실패 건 쿠폰/포인트 미롤백 | 5명 성공, 실패 건 쿠폰 AVAILABLE·포인트 잔액 유지 |
| **D-1** | 교차 상품 주문 (상품1+2 vs 상품2+1) | 각 상품 재고 1만 차감 (stale read) | 각 상품 재고 2 차감 (정확) |
| **D-2** | 10명이 3개 상품 조합 동시 주문 | 각 상품 재고 1만 차감 | 각 상품 재고 10 차감 (50→40) |
| **E-3** | 상품1(재고10)+상품2(재고1) 2명 동시 주문 | 2명 모두 성공 (상품2 stale read) | 1명 성공, 상품1 재고 9·상품2 재고 0 |
| **F-2** | 장바구니에서 같은 상품 2건 동시 주문 | 재고 1만 차감 (stale read) | 재고 2 차감 (10→8), 장바구니 삭제 |

### 변경 파일
- `ProductRepository` — `findProductOnly(Long id)` 메서드 선언 추가
- `ProductRepositoryImpl` — `findProductOnly()` 구현 (Product만 조회, 옵션 로드 X), `findOptionByIdWithLock()` 복원 (`refresh()` 제거, 원래 `entityManager.find(class, id, PESSIMISTIC_WRITE)` 방식)
- `ProductService` — `findProductOnly(Long id)` 메서드 추가
- `OrderService.prepareOrderItems()` — `productService.findById()` → `productService.findProductOnly()` 변경
- `OrderFacade.calculateApplicableAmount()` — `productService.findById()` → `productService.findProductOnly()` 변경
- `OrderServiceTest` — mock 대상 메서드 `findById` → `findProductOnly` 변경
- `OrderFacadeTest` — mock 대상 메서드 `findById` → `findProductOnly` 변경

---

### 코드래빗 리뷰 수정 항목

#### 1. MemberCoupon soft delete와 유니크 제약 충돌 해결

**리뷰 내용**
- `MemberCoupon`이 `BaseEntity`를 상속하여 soft delete(`deletedAt`) 기능을 가지고 있으나, `UNIQUE(member_id, coupon_id)` 제약은 soft-deleted 행을 포함한 모든 행에 적용됨
- soft-delete 후 동일 쿠폰 재발급 시도 시 DB 유니크 제약 위반(`DataIntegrityViolationException`) 발생
- 리포지토리의 `DeletedAtIsNull` 필터가 soft-deleted 행을 건너뛰어 애플리케이션 레벨에서는 중복이 없다고 판단하지만, DB 레벨에서 충돌

**검토한 대안**
- A: `deleted_at IS NULL` 조건부 유니크 인덱스 → MySQL이 partial unique index 미지원으로 불가
- B: `is_deleted` 컬럼 추가 후 유니크 키에 포함 → 삭제된 행이 여러 개일 때 다시 충돌, `deletedAt`과 역할 중복
- C: `MemberCouponStatus`에 `DELETED` 상태 추가 → 행을 재사용하여 유니크 제약 유지 가능

**적용한 해결 방안: C (상태 기반 soft delete)**

`MemberCouponStatus`에 `DELETED` 상태를 추가하고, 삭제 시 행을 물리적으로 제거하지 않고 상태만 전환한다. 재발급 시 DELETED 행을 찾아 AVAILABLE로 복원하여 유니크 제약 충돌 없이 재사용한다.

**변경 파일**
- `MemberCouponStatus` — `DELETED` 상태 추가
- `MemberCoupon` — `markDeleted()` (상태 DELETED + deletedAt 설정), `reissue()` (DELETED → AVAILABLE 복원 + 사용 이력 초기화) 메서드 추가
- `MemberCouponRepository` — `findByMemberIdAndCouponIdIncludingDeleted()` 메서드 추가 (삭제된 행 포함 조회)
- `MemberCouponJpaRepository` — `findByMemberIdAndCouponId()` 메서드 추가 (deletedAt 필터 없음)
- `MemberCouponRepositoryImpl` — `findByMemberIdAndCouponIdIncludingDeleted()` 구현
- `CouponService.downloadCoupon()` — 삭제된 행 포함 조회 후 DELETED면 reissue(), 활성 상태면 CONFLICT 예외, 미존재 시 신규 생성
- `MemberCouponTest` — `markDeleted()`, `reissue()` 단위 테스트 추가
- `CouponServiceTest` — 삭제된 쿠폰 재발급 테스트 추가, 기존 다운로드 테스트 mock 메서드 변경

#### 2. CouponService.useCoupon() 만료 기간 검증 누락

**리뷰 내용**
- `useCoupon()` 메서드에서 쿠폰 소유권만 검증하고 유효기간을 검증하지 않음
- `calculateCouponDiscount()`에서는 `coupon.isValid()` 검증이 있어 검증 로직이 불일치
- 유효기간이 지난 AVAILABLE 상태의 쿠폰이 USED로 전환될 수 있는 위험

**분석**
- 현재 주문 흐름에서는 `calculateCouponDiscount()`(1단계) → `useCoupon()`(4단계) 순서로 같은 트랜잭션 내에서 호출되므로 실제 주문 시 만료 쿠폰이 사용되는 일은 없음
- 다만 `useCoupon()`이 독립적으로 호출될 경우 유효기간 검증이 누락되어 방어가 없음

**적용한 수정**
- `useCoupon()`에서 `Coupon` 조회 후 `coupon.isValid()` 검증 추가
- 이후 원자적 UPDATE 전환 시 `MemberCoupon.use()` 메서드 자체가 삭제됨 (상태 전환이 DB 쿼리에서 처리)

**변경 파일**
- `CouponService.useCoupon()` — Coupon 조회 및 `coupon.isValid()` 유효기간 검증 추가
- `CouponServiceTest` — 만료 쿠폰 사용 시도 테스트 추가, 성공 테스트에 couponRepository mock 추가