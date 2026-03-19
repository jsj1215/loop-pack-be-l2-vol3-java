package com.loopers.interfaces.api;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.member.BirthDate;
import com.loopers.domain.member.Email;
import com.loopers.domain.member.LoginId;
import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberName;
import com.loopers.domain.point.Point;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductStatus;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.member.MemberJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.ProductOptionJpaRepository;
import com.loopers.interfaces.api.order.dto.OrderV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/*
  [E2E 테스트]

  대상 : 주문 API 전체 흐름

  테스트 범위: HTTP 요청 → Controller → Facade → Service → Repository → Database
  사용 라이브러리 : JUnit 5, Spring Boot Test, TestRestTemplate, Testcontainers, AssertJ
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderV1ApiE2ETest {

    private static final String ENDPOINT_ORDERS = "/api/v1/orders";
    private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
    private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

    @MockitoBean
    private com.loopers.infrastructure.payment.PgFeignClient pgFeignClient;

    private final TestRestTemplate testRestTemplate;
    private final MemberJpaRepository memberJpaRepository;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final ProductOptionJpaRepository productOptionJpaRepository;
    private final PointJpaRepository pointJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public OrderV1ApiE2ETest(
            TestRestTemplate testRestTemplate,
            MemberJpaRepository memberJpaRepository,
            BrandJpaRepository brandJpaRepository,
            ProductJpaRepository productJpaRepository,
            ProductOptionJpaRepository productOptionJpaRepository,
            PointJpaRepository pointJpaRepository,
            DatabaseCleanUp databaseCleanUp,
            PasswordEncoder passwordEncoder) {
        this.testRestTemplate = testRestTemplate;
        this.memberJpaRepository = memberJpaRepository;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.productOptionJpaRepository = productOptionJpaRepository;
        this.pointJpaRepository = pointJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
        this.passwordEncoder = passwordEncoder;
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // PG 결제 요청 → 접수 성공 응답 stub
        when(pgFeignClient.requestPayment(anyString(), any()))
                .thenReturn(new com.loopers.infrastructure.payment.PgPaymentResponse(
                        "20250319:TR:test123", "1", "ACCEPTED", null));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Member saveMember() {
        Member member = new Member(
                new LoginId("testuser1"),
                passwordEncoder.encode("Password1!"),
                new MemberName("홍길동"),
                new Email("test@example.com"),
                new BirthDate("19900101"));
        return memberJpaRepository.save(member);
    }

    private Brand saveActiveBrand() {
        Brand brand = new Brand("테스트브랜드", "브랜드 설명");
        brand.changeStatus(BrandStatus.ACTIVE);
        return brandJpaRepository.save(brand);
    }

    private Product saveProduct(Brand brand) {
        Product product = new Product(brand, "테스트상품", 10000, 8000, 1000, 2500,
                "상품 설명", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y", List.of());
        return productJpaRepository.save(product);
    }

    private ProductOption saveProductOption(Long productId) {
        ProductOption option = new ProductOption(productId, "기본 옵션", 100);
        return productOptionJpaRepository.save(option);
    }

    private Point savePoint(Long memberId, int balance) {
        Point point = Point.create(memberId, balance);
        return pointJpaRepository.save(point);
    }

    private HttpHeaders memberAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_LOGIN_ID, "testuser1");
        headers.set(HEADER_LOGIN_PW, "Password1!");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @DisplayName("POST /api/v1/orders")
    @Nested
    class CreateOrder {

        @Test
        @DisplayName("유효한 요청이면, 200 OK와 주문 상세 정보를 반환한다.")
        void success_whenValidRequest() {
            // given
            Member member = saveMember();
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand);
            ProductOption option = saveProductOption(product.getId());
            savePoint(member.getId(), 50000);

            OrderV1Dto.CreateOrderRequest request = new OrderV1Dto.CreateOrderRequest(
                    List.of(new OrderV1Dto.OrderItemRequest(
                            product.getId(), option.getId(), 2)),
                    null,
                    0,
                    List.of(),
                    "SAMSUNG",
                    "1234-5678-9012-3456");

            // when
            ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<OrderV1Dto.OrderDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ORDERS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, memberAuthHeaders()),
                    responseType);

            // then
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().id()).isNotNull(),
                    () -> assertThat(response.getBody().data().orderItems()).hasSize(1),
                    () -> assertThat(response.getBody().data().totalAmount()).isGreaterThan(0));
        }

        @Test
        @DisplayName("인증 없이 요청하면, 401 UNAUTHORIZED를 반환한다.")
        void fail_whenNoAuth() {
            // given
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand);
            ProductOption option = saveProductOption(product.getId());

            OrderV1Dto.CreateOrderRequest request = new OrderV1Dto.CreateOrderRequest(
                    List.of(new OrderV1Dto.OrderItemRequest(
                            product.getId(), option.getId(), 1)),
                    null,
                    0,
                    List.of(),
                    "SAMSUNG",
                    "1234-5678-9012-3456");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // when
            ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<OrderV1Dto.OrderDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ORDERS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    responseType);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @DisplayName("GET /api/v1/orders")
    @Nested
    class GetOrders {

        @Test
        @DisplayName("인증된 사용자가 주문 목록을 조회하면, 200 OK와 페이지 정보를 반환한다.")
        void success_whenAuthenticated() {
            // given
            Member member = saveMember();
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand);
            ProductOption option = saveProductOption(product.getId());
            savePoint(member.getId(), 50000);

            // 주문 먼저 생성
            OrderV1Dto.CreateOrderRequest createRequest = new OrderV1Dto.CreateOrderRequest(
                    List.of(new OrderV1Dto.OrderItemRequest(
                            product.getId(), option.getId(), 1)),
                    null,
                    0,
                    List.of(),
                    "SAMSUNG",
                    "1234-5678-9012-3456");
            testRestTemplate.exchange(
                    ENDPOINT_ORDERS,
                    HttpMethod.POST,
                    new HttpEntity<>(createRequest, memberAuthHeaders()),
                    new ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderDetailResponse>>() {});

            // when
            String startAt = ZonedDateTime.now().minusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            String endAt = ZonedDateTime.now().plusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    ENDPOINT_ORDERS + "?startAt={startAt}&endAt={endAt}&page=0&size=10",
                    HttpMethod.GET,
                    new HttpEntity<>(memberAuthHeaders()),
                    responseType,
                    startAt, endAt);

            // then
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data()).isNotNull());
        }
    }

    @DisplayName("GET /api/v1/orders/{orderId}")
    @Nested
    class GetOrderDetail {

        @Test
        @DisplayName("인증된 사용자가 주문 상세를 조회하면, 200 OK와 주문 상세 정보를 반환한다.")
        void success_whenAuthenticated() {
            // given
            Member member = saveMember();
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand);
            ProductOption option = saveProductOption(product.getId());
            savePoint(member.getId(), 50000);

            // 주문 먼저 생성
            OrderV1Dto.CreateOrderRequest createRequest = new OrderV1Dto.CreateOrderRequest(
                    List.of(new OrderV1Dto.OrderItemRequest(
                            product.getId(), option.getId(), 1)),
                    null,
                    0,
                    List.of(),
                    "SAMSUNG",
                    "1234-5678-9012-3456");
            ResponseEntity<ApiResponse<OrderV1Dto.OrderDetailResponse>> createResponse = testRestTemplate.exchange(
                    ENDPOINT_ORDERS,
                    HttpMethod.POST,
                    new HttpEntity<>(createRequest, memberAuthHeaders()),
                    new ParameterizedTypeReference<>() {});

            Long orderId = createResponse.getBody().data().id();

            // when
            ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<OrderV1Dto.OrderDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ORDERS + "/" + orderId,
                    HttpMethod.GET,
                    new HttpEntity<>(memberAuthHeaders()),
                    responseType);

            // then
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().id()).isEqualTo(orderId),
                    () -> assertThat(response.getBody().data().orderItems()).isNotEmpty());
        }

        @Test
        @DisplayName("존재하지 않는 주문을 조회하면, 404 NOT_FOUND를 반환한다.")
        void fail_whenOrderNotFound() {
            // given
            saveMember();

            // when
            ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<OrderV1Dto.OrderDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ORDERS + "/999",
                    HttpMethod.GET,
                    new HttpEntity<>(memberAuthHeaders()),
                    responseType);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
