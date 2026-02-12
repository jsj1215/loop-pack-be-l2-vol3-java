
## 요약
이커머스 서비스에서 고객이 상품과 브랜드를 조회하고 좋아요를 누르고 주문을 하며 (대고객 기능)
이에 대응하는 어드민 기능도 함께 구현한다. (어드민 기능)

모든 시퀀스 다이어그램, 클래스 다이어그램, ERD는 Checklist 가 꼭 통과되어야 한다.

---
#### 아래 상세 요구사항을 토대로 시퀀스 다이어그램, 클래스 다이어그램, ERD 다이어그램을 작성한다.
1) 시퀀스 다이어그램
- Mermaid 방식으로 작업 한다.
- 기능 하나당 하나의 시퀀스를 만들도록 한다.
- 흐름의 순서가 잘 보이도록 하며 요구사항에 맞게 조건 분기와 이벤트 발행을 표현한다.
- 예외 사항에 대한 분기도 잘 보여질 수 있도록 한다.
- 각 클래스가 최소한의 책임과 역할을 가지도록 설계한다.
- 해당 작업은 /docs/design/02-sequence-diagrams.md 파일에 작성 한다.
  ### 고객 서비스
  (1) 브랜드 정보 조회
      클라이언트 -> Controller (get) : 브랜드 ID가 있는지 검증. 없다면? 400 error
      Controller -> Facade
      Facade -> domain(Service)
      domain(Service) -> infra(Repository) : DB조회. 데이터가 없다면? 404 error. 브랜드 상태가 ACTIVE가 아닌 경우? 404 error
      조회한 데이터 VO 나 DTO에 담아 리턴 -> domain(Serive) -> Facade -> Controller
  (2) 상품 목록 조회
      클라이언트 -> Controller (get) : query parameter → ProductSearchCondition 생성
      Controller -> Facade
      Facade -> domain(Service)
      domain(Service) -> infra(Repository) : DB조회. 데이터가 하나도 없다면? 200 OK "조회된 내역이 없습니다."
      조회한 데이터 VO 나 DTO에 담아 리턴 -> domain(Serive) -> Facade -> Controller
  (3) 상품 정보 조회
      클라이언트 -> Controller (get)
      Controller -> Facade
      Facade -> domain(Service)
      domain(Service) -> infra(Repository) : DB조회. 데이터가 하나도 없다면? 404 error
      조회한 데이터 VO 나 DTO에 담아 리턴 -> domain(Serive) -> Facade -> Controller 
  (4) 좋아요
      클라이언트 -> Controller : MemberAuthInterceptor 인증 처리 → @LoginMember Member 주입. 인증 실패 시 401 Unauthorized
      Controller -> Facade
      Facade -> domain(Service)
      domain(Service) -> infra(Repository) : 해당 상품ID로 USER_ID 데이터가 있는지 확인. 없다면 Insert, 있다면 LIKE_YN이 'N' 일 경우만 'Y'로 Update
  (5) 좋아요 취소
      클라이언트 -> Controller : MemberAuthInterceptor 인증 처리 → @LoginMember Member 주입. 인증 실패 시 401 Unauthorized
      Controller -> Facade
      Facade -> domain(Service)
      domain(Service) -> infra(Repository) : 해당 상품ID로 USER_ID 데이터가 있는지 확인. 없다면 400 Bad Request, 있다면 LIKE_YN이 'Y' 일 경우만 'N'으로 Update
  (6) 좋아요한 상품 목록 조회
      클라이언트 -> Controller : MemberAuthInterceptor 인증 처리 → @LoginMember Member 주입. 인증 실패 시 401 Unauthorized
      Controller -> Facade
      Facade -> domain(Service)
      domain(Service) -> infra(Repository) : 해당 USER_ID로 좋아요 한 목록 조회.
      조회한 데이터 VO 나 DTO에 담아 리턴 -> domain(Serive) -> Facade -> Controller
  (7) 장바구니 담기
      클라이언트 -> Controller : MemberAuthInterceptor 인증 처리 → @LoginMember Member 주입. 인증 실패 시 401 Unauthorized
      Controller -> Facade
      Facade -> domain(Service)
      domain(Service) -> infra(Repository)
     : 해당 상품+옵션의 재고가 있는지 확인. 재고가 부족하면 400 Bad Request, 재고가 있다면 Cart 테이블에 데이터 추가
     : 기존에 동일한 상품+옵션이 장바구니에 있다면 수량만 증가시킨다. (합산 수량 > 재고 시 400 Bad Request)
  (8) 주문 요청
      클라이언트 -> Controller : MemberAuthInterceptor 인증 처리 → @LoginMember Member 주입. 인증 실패 시 401 Unauthorized
      Controller -> Facade
      Facade -> domain(Service)
      domain(Service) -> infra(Repository)
      : 주문 항목별 재고 검증 및 차감. 재고 부족 시 400 Bad Request
      : 주문 데이터 + 스냅샷(상품명, 옵션명, 브랜드명, 판매가, 공급가, 배송비, 수량) 생성
      : 장바구니에서 주문한 경우 해당 장바구니 항목 삭제
      : 실패 시 @Transactional 롤백으로 재고 자동 복원 (결제 기능 없음)
  (9) 주문 목록 조회
      클라이언트 -> Controller (get) : MemberAuthInterceptor 인증 처리 → @LoginMember Member 주입. 인증 실패 시 401 Unauthorized
      Controller -> Facade : startAt, endAt 필수 파라미터. 없으면 400 Bad Request
      Facade -> domain(Service)
      domain(Service) -> infra(Repository) : DB조회. 데이터가 하나도 없다면? 200 OK "조회된 내역이 없습니다."
      조회한 데이터 VO 나 DTO에 담아 리턴 -> domain(Serive) -> Facade -> Controller
  (10) 주문건에 대한 상세 정보 조회
      클라이언트 -> Controller (get) : MemberAuthInterceptor 인증 처리 → @LoginMember Member 주입. 인증 실패 시 401 Unauthorized
      Controller -> Facade
      Facade -> domain(Service)
      domain(Service) -> infra(Repository) : DB조회. 주문이 존재하지 않거나 본인 주문이 아닌 경우 404 Not Found
      조회한 데이터 VO 나 DTO에 담아 리턴 -> domain(Serive) -> Facade -> Controller

  ### 어드민
  아래 상세 요구사항 참고
    

2) 클래스 다이어그램
- 하나의 클래스는 하나의 역할만 하도록 설계한다.
- 도메인 책임 없이 Service에 모든 로직이 집중되지 않도록 한다.
- 도메인 서비스와 애플리케이션 서비스 중 비즈니스 로직을 최대한 도메인 서비스에 넣도록 한다.
- 해당 작업은 /docs/design/03-class-diagrams.md 파일에 한다.

3) ERD 다이어그램
- 해당 작업은 /docs/design/04-erd.md 파일에 한다.
 

---
## 상세 요구사항
### 1. 대고객 기능
1) /api/v1 prefix를 통해 제공
2) 유저 로그인이 필요한 기능은 아래 헤더를 통해 유저를 식별해 제공한다.
인증은 MemberAuthInterceptor에서 헤더를 검증하고, @LoginMember ArgumentResolver를 통해 Controller에 Member 객체를 주입한다.
Facade는 인증에 관여하지 않으며, 인증된 Member를 파라미터로 받아 서비스 조율에만 집중한다.
유저는 타 유저의 정보에 직접 접근할 수 없음.
* X-Loopers-LoginId : 로그인 ID
* X-Loopers-LoginPw : 비밀번호
3) 유저 시나리오
고객은 브랜드 정보 조회와 상품 조회를 통해 원하는 브랜드 정보와 상품들의 목록을 보고 
상품을 클릭하면 해당 상품들의 상세 정보를 조회 할 수 있다.

고객은 마음에 드는 상품에 좋아요를 할 수도 있고, 좋아요 취소를 할 수도 있다.
고객은 내가 좋아요 한 상품 목록을 조회할 수 있다.

고객은 마음에 드는 상품을 단건으로 주문할 수 있다.
고객은 마음에 드는 상품을 장바구니에 담을 수 있다.
고객은 장바구니에 담긴 여러 상품들중 선택하여 한 번에 주문할 수 있다.
(고객이 주문시점에 상품의 현재 재고 수량을 파악하여 재고가 있을 때만 주문이 가능하도록 하며,
주문 후 상품의 재고는 주문 수량만큼 차감되어야 하며, 주문한 정보는 스냅샷으로 저장해야 한다.)
주문이 완료된 후에 장바구니에서 해당 상품은 삭제된다.

고객은 자신이 주문한 주문 목록을 조회 할 수 있다.
고객은 주문목록 중 하나의 주문건을 선택하여 주문 상세내역을 조회할 수 있다.

4) api별 상세 스펙
### 상품
(1) 브랜드 정보 조회
- 설명 : 브랜드의 고유 id를 사용하여 해당 브랜드의 정보를 조회.
- URI : /api/v1/brands/{brandId}
- METHOD : GET
- 로그인 필수 아님.
- 파라미터 : 브랜드ID

(2) 상품 목록 조회
- 설명 : 상품 목록을 조회. 
- URI : /api/v1/products
- METHOD : GET
- 로그인 필수 아님.
- 페이징 필수.
- 정렬 조건 : latest(최신순), price_asc(낮은가격순), like_desc(좋아요수)
- 브랜드 ID를 기준으로 특정 브랜드의 상품만 필터링이 가능해야함.
- 파라미터 : 검색어, 정렬조건, 브랜드 ID

(3) 상품 정보 조회
- 설명 : 상품 하나에 대한 상세 정보를 조회한다.
- URI : /api/v1/products/{productId} 
- METHOD : GET
- 로그인 필수 아님.
- 파라미터 : 상품ID

(4) 장바구니 담기
- 설명 : 상품을 장바구니에 담는다.
- URI : /api/v1/carts
- METHOD : POST
- 로그인 필수
- 파라미터 : 상품ID, 옵션ID, 수량
- 장바구니에 담기전 상품 옵션의 재고수량을 파악한다.
- 동일 상품+옵션이 이미 장바구니에 있다면 수량만 증가시킨다.


---
### 좋아요
(1) 상품 좋아요 등록
- 설명 : 특정 상품에 고객이 좋아요를 누른다.
- URI : /api/v1/products/{productId}/likes
- METHOD : POST
- 로그인 필수
- 파라미터 : 고객 ID. 상품 ID, 좋아요 or 좋아요 취소

(2) 상품 좋아요 취소
- 설명 : 특정 상품에 고객이 누른 좋아요를 취소한다.
- URI : /api/v1/products/{productId}/likes
- METHOD : PUT
- 로그인 필수
- 파라미터 : 고객 ID, 상품 ID

(3) 고객이 좋아요 한 상품 목록 조회
- 설명 : 고객이 좋아요를 한 상품들의 목록을 조회한다.
- URI : /api/v1/users/{userId}/likes
- METHOD : GET
- 로그인 필수
- 파라미터 : 고객 ID

---
### 주문
(1) 주문 요청 
- 설명 : 고객이 주문을 요청한다.
- URI : /api/v1/orders
- METHOD : POST
- 로그인 필수

(2) 유저의 주문 목록 조회
- 설명 : 고객이 주문한 주문목록을 조회한다.
- URI : /api/v1/orders?startAt=2026-01-31&endAt=2026-02-10
- METHOD : GET
- 로그인 필수

(3) 단일 주문 상세 조회
- 설명 : 고객의 주문번호 하나에 대한 상세 내역을 조회한다.
- URI : /api/v1/orders/{orderId}
- METHOD : GET
- 로그인 필수

---
### 2. 어드민 기능
1) /api-admin/v1 prefix 를 통해 제공
2) 아래 헤더를 통해 어드민을 식별해 제공
* X-Loopers-Ldap : loopers.admin
3) LDAP 사용해서 구현
인증은 AdminAuthInterceptor에서 `X-Loopers-Ldap` 헤더를 검증하고, @LoginAdmin ArgumentResolver를 통해 Controller에 Admin 객체를 주입한다.
LdapAuthService는 인터페이스로 정의하고, Fake 구현에서 헤더 값 검증만 수행한다.
Controller는 고객 서비스와 분리된 별도 어드민 컨트롤러를 사용한다.

4) 유저 시나리오
관리자는 등록된 브랜드들의 목록을 조회 할 수 있다. (목록은 페이징 처리 된다.)
관리자는 특정 브랜드의 상세 정보를 조회 할 수 있다.
관리자는 브랜드를 등록할 수 있다.
관리자는 브랜드의 상세 정보를 수정할 수 있다.
관리자는 브랜드를 삭제할 수 있으며,  브랜드 삭제시 해당 브랜드들의 상품들도 삭제된다.

관리자는 상품 목록을 조회 할 수 있다. (목록은 페이징 처리 된다.)
관리자는 특정 상품의 상세 정보를 조회할 수 있다.
관리자는 상품을 등록할 수 있다. (상품 등록시 브랜드가 이미 등록되어 있어야 한다.)
관리자는 상품의 상세 정보를 수정할 수 있다. (상품의 브랜드는 수정할 수 없다.)
관리자는 상품을 삭제할 수 있다.


5) api별 상세 스펙
(1) 등록된 브랜드 목록 조회
- 설명 : 
- URI : /api-admin/v1/brands?page=0&size=20
- METHOD : GET
- 로그인 필수

(2) 브랜드 상세 조회
- 설명 :
- URI : /api-admin/v1/brands/{brandId}
- METHOD : GET
- 로그인 필수

(3) 브랜드 등록
- 설명 :
- URI : /api-admin/v1/brands
- METHOD : POST
- 로그인 필수

(4) 브랜드 정보 수정
- 설명 :
- URI : /api-admin/v1/brands/{brandId}
- METHOD : PUT
- 로그인 필수

(5) 브랜드 삭제
- 설명 :
- URI : /api-admin/v1/brands/{brandId}
- METHOD : DELETE
- 로그인 필수

(6) 등록된 상품 목록 조회
- 설명 : searchType과 searchValue를 통한 검색 조회
- URI : /api-admin/v1/products?searchType=&searchValue=&page=0&size=20
- METHOD : GET
- 로그인 필수
- searchType : PRODUCT_ID, PRODUCT_NAME(LIKE), BRAND_ID, STATUS(ON_SALE/SOLD_OUT/DISCONTINUED), DISPLAY_YN(Y/N)

(7) 상품 상세 조회
- 설명 :
- URI : /api-admin/v1/products/{productId}
- METHOD : GET
- 로그인 필수

(8) 상품 등록
- 설명 :
- URI : /api-admin/v1/products
- METHOD : POST
- 로그인 필수
- Body : brandId, name, price, marginType(AMOUNT/RATE), marginValue, discountPrice, shippingFee, description, options[]
- 공급가(supplyPrice)는 marginType에 따라 자동 계산

(9) 상품 정보 수정
- 설명 :
- URI : /api-admin/v1/products/{productId}
- METHOD : PUT
- 로그인 필수
- Body : name, price, supplyPrice, shippingFee, description, options[] (brandId 수정 불가)

(10) 상품 삭제
- 설명 :
- URI : /api-admin/v1/products/{productId}
- METHOD : DELETE
- 로그인 필수


---
### 3. 설계 반영 사항
> 시퀀스/클래스/ERD 설계 과정에서 결정된 사항

#### 상태 관리
- **Brand 상태**: PENDING(대기) / ACTIVE(진행중) / WITHDRAWN(퇴점). 고객 서비스에서는 ACTIVE 브랜드만 조회 가능
- **Product 상태**: ON_SALE(판매중) / SOLD_OUT(품절) / DISCONTINUED(판매중지)
- **Product 노출여부**: displayYn (Y/N). status와 독립적으로 관리
- **Member 상태**: ACTIVE(사용중) / WITHDRAWN(탈퇴)

#### 상품 가격 정책
- **마진유형(marginType)**: AMOUNT(마진액) / RATE(마진율)
- **공급가(supplyPrice)**: 상품 등록 시 marginType + marginValue로 자동 계산
  - AMOUNT: supplyPrice = price - marginValue
  - RATE: supplyPrice = price - (price × marginRate / 100)
- **할인가(discountPrice)**: 별도 필드로 관리

#### 인증 방식
- **고객 인증**: MemberAuthInterceptor + @LoginMember ArgumentResolver. URL 패턴 `/api/v1/**` 대상
- **어드민 인증**: AdminAuthInterceptor + @LoginAdmin ArgumentResolver. URL 패턴 `/api-admin/v1/**` 대상
- Facade는 인증에 관여하지 않음

#### 조회 정책
- 목록 조회 시 빈 리스트인 경우 200 OK + "조회된 내역이 없습니다." 메시지 반환 (404 아님)
- 어드민 상품 검색은 searchType + searchValue 패턴 (AdminProductSearchCondition)

#### 기타
- 물리적 FK 제약조건 미사용. 참조 무결성은 애플리케이션 레벨에서 검증
- PRODUCT_LIKE는 LIKE_YN으로 상태 관리 (soft delete 아님, BaseEntity 미상속)
- ORDER_ITEM에 product_id 포함 (참고용, 스냅샷 독립성 유지)
- 결제 기능 없음. 재고 검증 → 차감 → 주문 생성으로 완료

---

### 쵤종 체크 리스트
-  상품/브랜드/좋아요/주문 도메인이 모두 포함되어 있는가?
-  기능 요구사항이 유저 중심으로 정리되어 있는가?
-  시퀀스 다이어그램에서 책임 객체가 드러나는가?
-  클래스 구조가 도메인 설계를 잘 표현하고 있는가?
-  ERD 설계 시 데이터 정합성을 고려하여 구성하였는가?