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
        - METHOD : POST
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

---

## 현재 코드 대비 변경 분석

### 1. 수정 필요 (기존 코드 변경)

#### (1) 쿠폰 발급 URI 변경
- **현재**: `POST /api/v1/coupons/{couponId}/download`
- **변경**: `POST /api/v1/coupons/{couponId}/issue`
- **대상 파일**: `CouponV1Controller`
- **변경 내용**: `@PostMapping("/{couponId}/download")` → `@PostMapping("/{couponId}/issue")`

#### (2) 내 쿠폰 목록 조회 URI 및 로직 변경
- **현재**: `GET /api/v1/coupons/me` (AVAILABLE만 조회)
- **변경**: `GET /api/v1/users/me/coupons` (AVAILABLE / USED / EXPIRED 상태 함께 반환)
- **대상 파일 및 변경 내용**:
  - `CouponV1Controller` → 해당 엔드포인트를 새 컨트롤러로 이동하거나 URI 변경
  - `MemberCouponStatus` → `EXPIRED` enum 추가 (DB 저장용이 아닌 응답 표현용)
  - `CouponService.findMyCoupons()` → 전체 상태 조회로 변경 (status 필터 제거)
  - `MemberCouponRepository` → `findByMemberId(Long memberId)` 메서드 추가
  - `MyCouponInfo` → `status` 필드 추가, EXPIRED 판별 로직 (coupon.validTo < now && status == AVAILABLE → EXPIRED)
  - `CouponV1Dto.MyCouponResponse` → `status` 필드 추가

### 2. 신규 추가

#### (3) 어드민 쿠폰 템플릿 수정 API
- **URI**: `PUT /api-admin/v1/coupons/{couponId}`
- **추가 대상**:
  - `AdminCouponV1Controller` → PUT 엔드포인트 추가
  - `AdminCouponFacade` → `updateCoupon()` 메서드 추가
  - `CouponService` → 수정 로직 추가
  - `Coupon` → `updateInfo()` 메서드 추가
  - `CouponV1Dto` → `UpdateCouponRequest` DTO 추가

#### (4) 어드민 쿠폰 템플릿 삭제 API
- **URI**: `POST /api-admin/v1/coupons/{couponId}` (soft delete)
- **추가 대상**:
  - `AdminCouponV1Controller` → DELETE 엔드포인트 추가
  - `AdminCouponFacade` → `deleteCoupon()` 메서드 추가
  - `CouponService` → soft delete 로직 추가
  - `CouponRepository` → `softDelete()` 메서드 추가 (or BaseEntity.delete() 활용)

#### (5) 어드민 특정 쿠폰 발급 내역 조회 API
- **URI**: `GET /api-admin/v1/coupons/{couponId}/issues?page=0&size=20`
- **추가 대상**:
  - `AdminCouponV1Controller` → GET 엔드포인트 추가
  - `AdminCouponFacade` → `getCouponIssues()` 메서드 추가
  - `CouponService` → 발급 내역 조회 로직 추가
  - `MemberCouponRepository` → `findByCouponId(Long couponId, Pageable pageable)` 메서드 추가
  - `CouponV1Dto` → `CouponIssueResponse` DTO 추가 (발급 회원ID, 상태, 사용 시간 등)
  - `CouponIssueInfo` → 신규 Info 레코드 추가

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

## DB 테이블 변경: 없음
- 현재 COUPON, MEMBER_COUPON, ORDERS 테이블이 week4 요구사항을 이미 충족
- `EXPIRED` 상태는 DB에 저장하지 않고 조회 시 계산 (coupon.validTo < 현재시간 && member_coupon.status == AVAILABLE → EXPIRED)

## 설계 결정 사항
- **EXPIRED 판별 방식**: 조회 시 계산 (배치 상태 변경 X)
  - 이유: 별도 배치 작업 불필요, 실시간 정확성 보장, DB 스키마 변경 없음
