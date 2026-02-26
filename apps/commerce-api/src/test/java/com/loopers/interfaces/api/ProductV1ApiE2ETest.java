package com.loopers.interfaces.api;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.member.BirthDate;
import com.loopers.domain.member.Email;
import com.loopers.domain.member.LoginId;
import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberName;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductStatus;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.member.MemberJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.ProductOptionJpaRepository;
import com.loopers.interfaces.api.product.dto.ProductV1Dto;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/*
  [E2E 테스트]

  대상 : 상품 고객 API 전체 흐름

  테스트 범위: HTTP 요청 → Controller → Facade → Service → Repository → Database
  사용 라이브러리 : JUnit 5, Spring Boot Test, TestRestTemplate, Testcontainers, AssertJ
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductV1ApiE2ETest {

    private static final String ENDPOINT_PRODUCTS = "/api/v1/products";
    private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
    private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final ProductOptionJpaRepository productOptionJpaRepository;
    private final MemberJpaRepository memberJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public ProductV1ApiE2ETest(
            TestRestTemplate testRestTemplate,
            BrandJpaRepository brandJpaRepository,
            ProductJpaRepository productJpaRepository,
            ProductOptionJpaRepository productOptionJpaRepository,
            MemberJpaRepository memberJpaRepository,
            DatabaseCleanUp databaseCleanUp,
            PasswordEncoder passwordEncoder) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.productOptionJpaRepository = productOptionJpaRepository;
        this.memberJpaRepository = memberJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
        this.passwordEncoder = passwordEncoder;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
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

    private Member saveMember() {
        Member member = new Member(
                new LoginId("testuser1"),
                passwordEncoder.encode("Password1!"),
                new MemberName("홍길동"),
                new Email("test@example.com"),
                new BirthDate("19900101"));
        return memberJpaRepository.save(member);
    }

    private HttpHeaders memberAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_LOGIN_ID, "testuser1");
        headers.set(HEADER_LOGIN_PW, "Password1!");
        return headers;
    }

    @DisplayName("GET /api/v1/products")
    @Nested
    class GetProducts {

        @Test
        @DisplayName("상품 목록을 조회하면, 200 OK와 페이지 정보를 반환한다.")
        void success_whenGetProducts() {
            // arrange
            Brand brand = saveActiveBrand();
            saveProduct(brand);

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "?page=0&size=10",
                    HttpMethod.GET,
                    null,
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data()).isNotNull());
        }
    }

    @DisplayName("GET /api/v1/products/{productId}")
    @Nested
    class GetProduct {

        @Test
        @DisplayName("존재하는 상품을 조회하면, 200 OK와 상품 상세 정보를 반환한다.")
        void success_whenProductExists() {
            // arrange
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand);
            saveProductOption(product.getId());

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/" + product.getId(),
                    HttpMethod.GET,
                    null,
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().name()).isEqualTo("테스트상품"),
                    () -> assertThat(response.getBody().data().brandName()).isEqualTo("테스트브랜드"));
        }

        @Test
        @DisplayName("존재하지 않는 상품을 조회하면, 404 NOT_FOUND를 반환한다.")
        void fail_whenProductNotFound() {
            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/999",
                    HttpMethod.GET,
                    null,
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("POST /api/v1/products/{productId}/likes")
    @Nested
    class Like {

        @Test
        @DisplayName("인증된 사용자가 좋아요하면, 200 OK를 반환한다.")
        void success_whenAuthenticated() {
            // arrange
            saveMember();
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand);
            saveProductOption(product.getId());

            // act
            ParameterizedTypeReference<ApiResponse<Void>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/" + product.getId() + "/likes",
                    HttpMethod.POST,
                    new HttpEntity<>(memberAuthHeaders()),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("인증 없이 좋아요하면, 401 UNAUTHORIZED를 반환한다.")
        void fail_whenNoAuth() {
            // arrange
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand);

            // act
            ParameterizedTypeReference<ApiResponse<Void>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/" + product.getId() + "/likes",
                    HttpMethod.POST,
                    null,
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @DisplayName("PUT /api/v1/products/{productId}/likes")
    @Nested
    class Unlike {

        @Test
        @DisplayName("인증된 사용자가 좋아요 취소하면, 200 OK를 반환한다.")
        void success_whenAuthenticated() {
            // arrange
            saveMember();
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand);
            saveProductOption(product.getId());

            // 먼저 좋아요
            testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/" + product.getId() + "/likes",
                    HttpMethod.POST,
                    new HttpEntity<>(memberAuthHeaders()),
                    new ParameterizedTypeReference<ApiResponse<Void>>() {});

            // act - 좋아요 취소
            ParameterizedTypeReference<ApiResponse<Void>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/" + product.getId() + "/likes",
                    HttpMethod.PUT,
                    new HttpEntity<>(memberAuthHeaders()),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
