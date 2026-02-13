# 클래스 다이어그램 - 고객 서비스 + 어드민 서비스

> 기존 Layered Architecture 패턴을 따르며, 도메인 모델과 JPA 엔티티를 분리한다.
> 각 클래스는 단일 책임을 가지며, 도메인 모델에 비즈니스 로직을 배치한다.

---

## 전체 구조 Overview

```mermaid
classDiagram
    direction TB

    namespace Interfaces {
        class BrandV1Controller
        class ProductV1Controller
        class CartV1Controller
        class OrderV1Controller
    }

    namespace Application {
        class BrandFacade
        class ProductFacade
        class CartFacade
        class OrderFacade
    }

    namespace Domain {
        class BrandService
        class ProductService
        class LikeService
        class CartService
        class OrderService
    }

    namespace AdminInterfaces {
        class AdminBrandV1Controller
        class AdminProductV1Controller
    }

    namespace AdminApplication {
        class AdminBrandFacade
        class AdminProductFacade
    }

    namespace Auth {
        class LdapAuthService
    }

    BrandV1Controller --> BrandFacade
    ProductV1Controller --> ProductFacade
    CartV1Controller --> CartFacade
    OrderV1Controller --> OrderFacade

    BrandFacade --> BrandService
    ProductFacade --> ProductService
    ProductFacade --> LikeService
    CartFacade --> CartService
    OrderFacade --> OrderService

    AdminBrandV1Controller --> AdminBrandFacade
    AdminProductV1Controller --> AdminProductFacade

    AdminBrandFacade --> BrandService
    AdminProductFacade --> ProductService
```

---

## Domain 모델

> 도메인 모델은 JPA에 의존하지 않는 순수 객체이며, 비즈니스 로직을 포함한다.

```mermaid
classDiagram
    direction TB

    class Brand {
        -Long id
        -String name
        -String description
        -BrandStatus status
        +withId(Long, String, String, BrandStatus) Brand$
        <<BrandStatus>> PENDING / ACTIVE / WITHDRAWN
    }

    class Product {
        -Long id
        -Brand brand
        -String name
        -int price
        -int supplyPrice
        -int discountPrice
        -int shippingFee
        -int likeCount
        -String description
        -MarginType marginType
        -ProductStatus status
        -String displayYn
        -List~ProductOption~ options
        +withId(...) Product$
        +calculateSupplyPrice(int price, MarginType marginType, int marginValue) int$
        +incrementLikeCount() void
        +decrementLikeCount() void
        <<MarginType>> AMOUNT / RATE
        <<ProductStatus>> ON_SALE / SOLD_OUT / DISCONTINUED
    }

    class ProductOption {
        -Long id
        -Long productId
        -String optionName
        -int stockQuantity
        +withId(...) ProductOption$
        +hasEnoughStock(int quantity) boolean
        +deductStock(int quantity) void
        +restoreStock(int quantity) void
    }

    class Like {
        -Long id
        -Long memberId
        -Long productId
        -String likeYn
        +create(Long memberId, Long productId) Like$
        +like() void
        +unlike() void
        +isLiked() boolean
    }

    class CartItem {
        -Long id
        -Long memberId
        -Long productOptionId
        -int quantity
        +create(Long memberId, Long productOptionId, int quantity) CartItem$
        +addQuantity(int quantity) void
    }

    class Order {
        -Long id
        -Long memberId
        -List~OrderItem~ orderItems
        -int totalAmount
        +create(Long memberId, List~OrderItem~ items) Order$
        +calculateTotalAmount() int
    }

    class OrderItem {
        -Long id
        -String productName
        -String optionName
        -String brandName
        -int price
        -int supplyPrice
        -int shippingFee
        -int quantity
        +createSnapshot(Product, ProductOption, int quantity) OrderItem$
        +getSubtotal() int
    }

    Product --> Brand : contains
    Product "1" --> "*" ProductOption : has
    Order "1" --> "*" OrderItem : has
```

**설계 포인트**
- **Product → Brand 직접 참조**: 상품 상세 조회 시 별도 BrandService 호출 없이 한 번에 조회
- **ProductOption에 재고 로직 배치**: `hasEnoughStock()`, `deductStock()`, `restoreStock()`으로 재고 관련 비즈니스 로직을 도메인 모델이 책임
- **Like.likeYn**: LIKE_YN 컬럼 기반 soft delete. `like()`, `unlike()`으로 상태 전환
- **OrderItem.createSnapshot()**: Product + ProductOption 정보를 스냅샷으로 복사하는 팩토리 메서드
- **CartItem.addQuantity()**: 동일 옵션 장바구니 병합 시 수량 증가

---

## Domain 검색 조건

```mermaid
classDiagram
    class ProductSearchCondition {
        -String keyword
        -ProductSortType sort
        -Long brandId
        +of(String, ProductSortType, Long) ProductSearchCondition$
    }

    class ProductSortType {
        <<enumeration>>
        LATEST
        PRICE_ASC
        LIKE_DESC
    }

    class AdminProductSearchCondition {
        -AdminProductSearchType searchType
        -String searchValue
        +of(AdminProductSearchType, String) AdminProductSearchCondition$
    }

    class AdminProductSearchType {
        <<enumeration>>
        PRODUCT_ID
        PRODUCT_NAME
        BRAND_ID
        STATUS
        DISPLAY_YN
    }

    ProductSearchCondition --> ProductSortType
    AdminProductSearchCondition --> AdminProductSearchType
```

---

## Domain Service

```mermaid
classDiagram
    direction TB

    class BrandService {
        -BrandRepository brandRepository
        +getBrand(Long brandId) Brand
    }

    class ProductService {
        -ProductRepository productRepository
        +getProducts(ProductSearchCondition, Pageable) Page~Product~
        +getProduct(Long productId) Product
    }

    class LikeService {
        -LikeRepository likeRepository
        -ProductRepository productRepository
        +like(Long memberId, Long productId) void
        +unlike(Long memberId, Long productId) void
        +getLikedProducts(Long memberId, Pageable) Page~Product~
    }

    class CartService {
        -CartRepository cartRepository
        -ProductRepository productRepository
        +addToCart(Long memberId, Long productId, Long optionId, int quantity) void
    }

    class OrderService {
        -OrderRepository orderRepository
        -ProductRepository productRepository
        -CartRepository cartRepository
        +createOrder(Member member, List orderItems) Order
        +getOrders(Long memberId, LocalDate startAt, LocalDate endAt, Pageable) Page~Order~
        +getOrderDetail(Long memberId, Long orderId) Order
    }

    class BrandRepository {
        <<interface>>
        +findById(Long id) Optional~Brand~
    }

    class ProductRepository {
        <<interface>>
        +findAll(ProductSearchCondition, Pageable) Page~Product~
        +findById(Long id) Optional~Product~
        +findOptionById(Long productId, Long optionId) Optional~ProductOption~
    }

    class LikeRepository {
        <<interface>>
        +findByMemberIdAndProductId(Long, Long) Optional~Like~
        +findLikedProductsByMemberId(Long, Pageable) Page~Product~
        +save(Like) Like
    }

    class CartRepository {
        <<interface>>
        +findByMemberIdAndOptionId(Long, Long) Optional~CartItem~
        +save(CartItem) CartItem
        +deleteByIds(List~Long~) void
    }

    class OrderRepository {
        <<interface>>
        +save(Order) Order
        +findByMemberIdAndDateRange(Long, LocalDate, LocalDate, Pageable) Page~Order~
        +findByIdAndMemberId(Long, Long) Optional~Order~
    }

    BrandService --> BrandRepository
    ProductService --> ProductRepository
    LikeService --> LikeRepository
    LikeService --> ProductRepository
    CartService --> CartRepository
    CartService --> ProductRepository
    OrderService --> OrderRepository
    OrderService --> ProductRepository
    OrderService --> CartRepository
```

**설계 포인트**
- **Repository는 도메인 레이어에 인터페이스로 정의**: DIP 준수. 구현체는 Infrastructure
- **LikeService → ProductRepository 의존**: 좋아요 등록 시 상품 존재 검증
- **CartService → ProductRepository 의존**: 장바구니 담기 시 재고 검증
- **OrderService 의존 범위가 가장 넓음**: ProductRepository(재고), OrderRepository(주문), CartRepository(장바구니 삭제)

---

## Application Layer (Facade + Info)

```mermaid
classDiagram
    direction TB

    class BrandFacade {
        -BrandService brandService
        +getBrand(Long brandId) BrandInfo
    }

    class ProductFacade {
        -ProductService productService
        -LikeService likeService
        +getProducts(ProductSearchCondition, Pageable) Page~ProductInfo~
        +getProduct(Long productId) ProductDetailInfo
        +like(Member member, Long productId) void
        +unlike(Member member, Long productId) void
        +getLikedProducts(Member member, Pageable) Page~LikedProductInfo~
    }

    class CartFacade {
        -CartService cartService
        +addToCart(Member member, Long productId, Long optionId, int quantity) void
    }

    class OrderFacade {
        -OrderService orderService
        +createOrder(Member member, OrderRequest) OrderInfo
        +getOrders(Member member, LocalDate, LocalDate, Pageable) Page~OrderInfo~
        +getOrderDetail(Member member, Long orderId) OrderDetailInfo
    }

    class BrandInfo {
        <<record>>
        +Long id
        +String name
        +String description
        +from(Brand) BrandInfo$
    }

    class ProductInfo {
        <<record>>
        +Long id
        +String name
        +int price
        +int likeCount
        +String brandName
        +from(Product) ProductInfo$
    }

    class ProductDetailInfo {
        <<record>>
        +Long id
        +String name
        +int price
        +int supplyPrice
        +int shippingFee
        +int likeCount
        +String description
        +BrandInfo brand
        +List~OptionInfo~ options
        +from(Product) ProductDetailInfo$
    }

    class OrderInfo {
        <<record>>
        +Long id
        +int totalAmount
        +ZonedDateTime createdAt
        +from(Order) OrderInfo$
    }

    class OrderDetailInfo {
        <<record>>
        +Long id
        +int totalAmount
        +List~OrderItemInfo~ items
        +ZonedDateTime createdAt
        +from(Order) OrderDetailInfo$
    }

    BrandFacade --> BrandInfo
    ProductFacade --> ProductInfo
    ProductFacade --> ProductDetailInfo
    OrderFacade --> OrderInfo
    OrderFacade --> OrderDetailInfo
```

**설계 포인트**
- **Facade는 서비스 조율 + 변환 담당**: 인증은 Interceptor/ArgumentResolver에서 처리. Facade는 인증된 Member/Admin을 파라미터로 받음
- **Info 객체는 record**: 불변. 도메인 모델 → 응답용 데이터 변환을 `from()` 팩토리로 수행
- **ProductInfo vs ProductDetailInfo 분리**: 목록용(간략)과 상세용(브랜드+옵션 포함) 구분

---

## Infrastructure Layer

```mermaid
classDiagram
    direction TB

    class BaseEntity {
        <<abstract>>
        #Long id
        #ZonedDateTime createdAt
        #ZonedDateTime updatedAt
        #ZonedDateTime deletedAt
        #prePersist() void
        #preUpdate() void
        +delete() void
        +restore() void
    }

    class BrandEntity {
        -String name
        -String description
        -String status
        +from(Brand) BrandEntity$
        +toBrand() Brand
    }

    class ProductEntity {
        -BrandEntity brand
        -String name
        -int price
        -int supplyPrice
        -int discountPrice
        -int shippingFee
        -int likeCount
        -String description
        -String marginType
        -String status
        -String displayYn
        +from(Product) ProductEntity$
        +toProduct() Product
    }

    class ProductOptionEntity {
        -Long productId
        -String optionName
        -int stockQuantity
        +from(ProductOption) ProductOptionEntity$
        +toProductOption() ProductOption
    }

    class LikeEntity {
        -Long id
        -Long memberId
        -Long productId
        -String likeYn
        -ZonedDateTime createdAt
        -ZonedDateTime updatedAt
        +from(Like) LikeEntity$
        +toLike() Like
    }

    class CartItemEntity {
        -Long memberId
        -Long productOptionId
        -int quantity
        +from(CartItem) CartItemEntity$
        +toCartItem() CartItem
    }

    class OrderEntity {
        -Long memberId
        -int totalAmount
        +from(Order) OrderEntity$
        +toOrder() Order
    }

    class OrderItemEntity {
        -OrderEntity order
        -Long productId
        -String productName
        -String optionName
        -String brandName
        -int price
        -int supplyPrice
        -int shippingFee
        -int quantity
        +from(OrderItem) OrderItemEntity$
        +toOrderItem() OrderItem
    }

    BaseEntity <|-- BrandEntity
    BaseEntity <|-- ProductEntity
    BaseEntity <|-- ProductOptionEntity
    BaseEntity <|-- CartItemEntity
    BaseEntity <|-- OrderEntity
    BaseEntity <|-- OrderItemEntity

    ProductEntity --> BrandEntity : ManyToOne
    ProductOptionEntity --> ProductEntity : ManyToOne
    OrderItemEntity --> OrderEntity : ManyToOne
```

**설계 포인트**
- **모든 Entity는 BaseEntity 상속**: id, createdAt, updatedAt, deletedAt 공통 관리. 단, **LikeEntity는 예외** — LIKE_YN으로 상태 관리하므로 soft delete 불필요, BaseEntity 상속 없이 독립 관리
- **Entity ↔ Domain 변환**: `from()` / `toXxx()` 메서드로 양방향 변환
- **JPA 연관관계**: ProductEntity → BrandEntity (ManyToOne), ProductOptionEntity → ProductEntity (ManyToOne), OrderItemEntity → OrderEntity (ManyToOne)
- **LikeEntity, CartItemEntity**: memberId를 Long으로 보유 (Member 엔티티와 직접 연관관계 대신 ID 참조)

---

## 어드민 전용 레이어 (Interfaces + Application)

> 어드민은 별도 Controller와 Facade를 가지며, Service/Repository는 고객 서비스와 공유한다.
> 인증은 LdapAuthService 인터페이스를 통해 처리한다.

```mermaid
classDiagram
    direction TB

    class LdapAuthService {
        <<interface>>
        +authenticate(String ldapHeader) Admin
    }

    class FakeLdapAuthService {
        +authenticate(String ldapHeader) Admin
    }

    class Admin {
        <<record>>
        +String ldapId
    }

    class AdminBrandV1Controller {
        +getBrands(@LoginAdmin Admin, Pageable) ApiResponse
        +getBrand(@LoginAdmin Admin, Long brandId) ApiResponse
        +createBrand(@LoginAdmin Admin, Request) ApiResponse
        +updateBrand(@LoginAdmin Admin, Long brandId, Request) ApiResponse
        +deleteBrand(@LoginAdmin Admin, Long brandId) ApiResponse
    }

    class AdminProductV1Controller {
        +getProducts(@LoginAdmin Admin, AdminProductSearchCondition, Pageable) ApiResponse
        +getProduct(@LoginAdmin Admin, Long productId) ApiResponse
        +createProduct(@LoginAdmin Admin, Request) ApiResponse
        +updateProduct(@LoginAdmin Admin, Long productId, Request) ApiResponse
        +deleteProduct(@LoginAdmin Admin, Long productId) ApiResponse
    }

    class AdminBrandFacade {
        -BrandService brandService
        +getBrands(Pageable) Page~BrandInfo~
        +getBrand(Long brandId) BrandInfo
        +createBrand(String name, String description) BrandInfo
        +updateBrand(Long brandId, String name, String description) BrandInfo
        +deleteBrand(Long brandId) void
    }

    class AdminProductFacade {
        -ProductService productService
        +getProducts(AdminProductSearchCondition, Pageable) Page~ProductInfo~
        +getProduct(Long productId) ProductDetailInfo
        +createProduct(Request) ProductDetailInfo
        +updateProduct(Long productId, Request) ProductDetailInfo
        +deleteProduct(Long productId) void
    }

    LdapAuthService <|.. FakeLdapAuthService : implements
    LdapAuthService --> Admin

    AdminBrandV1Controller --> AdminBrandFacade
    AdminProductV1Controller --> AdminProductFacade

    AdminBrandFacade --> BrandService
    AdminProductFacade --> ProductService
```

**설계 포인트**
- **LdapAuthService 인터페이스**: DIP 준수. Fake 구현에서 헤더 값(`loopers.admin`) 검증만 수행. AdminAuthInterceptor에서 호출
- **Service/Repository 공유**: 어드민과 고객 서비스가 동일한 BrandService, ProductService 사용. 비즈니스 로직 중복 없음
- **AdminFacade는 인증 무관**: Interceptor에서 인증 완료 후 Controller가 @LoginAdmin Admin을 받고, Facade에는 비즈니스 파라미터만 전달
