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
import com.loopers.interfaces.api.cart.dto.CartV1Dto;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/*
  [E2E 테스트]

  대상 : 장바구니 API 전체 흐름

  테스트 범위: HTTP 요청 → Controller → Facade → Service → Repository → Database
  사용 라이브러리 : JUnit 5, Spring Boot Test, TestRestTemplate, Testcontainers, AssertJ
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CartV1ApiE2ETest {

    private static final String ENDPOINT_CART = "/api/v1/cart";
    private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
    private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

    private final TestRestTemplate testRestTemplate;
    private final MemberJpaRepository memberJpaRepository;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final ProductOptionJpaRepository productOptionJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public CartV1ApiE2ETest(
            TestRestTemplate testRestTemplate,
            MemberJpaRepository memberJpaRepository,
            BrandJpaRepository brandJpaRepository,
            ProductJpaRepository productJpaRepository,
            ProductOptionJpaRepository productOptionJpaRepository,
            DatabaseCleanUp databaseCleanUp,
            PasswordEncoder passwordEncoder) {
        this.testRestTemplate = testRestTemplate;
        this.memberJpaRepository = memberJpaRepository;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.productOptionJpaRepository = productOptionJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
        this.passwordEncoder = passwordEncoder;
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

    private HttpHeaders memberAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_LOGIN_ID, "testuser1");
        headers.set(HEADER_LOGIN_PW, "Password1!");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @DisplayName("POST /api/v1/cart")
    @Nested
    class AddToCart {

        @Test
        @DisplayName("유효한 요청이면, 201 CREATED를 반환한다.")
        void success_whenValidRequest() {
            // arrange
            saveMember();
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand);
            ProductOption option = saveProductOption(product.getId());

            CartV1Dto.AddToCartRequest request = new CartV1Dto.AddToCartRequest(
                    option.getId(), 2);

            // act
            ParameterizedTypeReference<ApiResponse<Void>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                    ENDPOINT_CART,
                    HttpMethod.POST,
                    new HttpEntity<>(request, memberAuthHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().meta().result().name()).isEqualTo("SUCCESS"));
        }

        @Test
        @DisplayName("인증 없이 요청하면, 401 UNAUTHORIZED를 반환한다.")
        void fail_whenNoAuth() {
            // arrange
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand);
            ProductOption option = saveProductOption(product.getId());

            CartV1Dto.AddToCartRequest request = new CartV1Dto.AddToCartRequest(
                    option.getId(), 2);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // act
            ParameterizedTypeReference<ApiResponse<Void>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                    ENDPOINT_CART,
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("존재하지 않는 옵션이면, 400 BAD_REQUEST를 반환한다.")
        void fail_whenOptionNotFound() {
            // arrange
            saveMember();

            CartV1Dto.AddToCartRequest request = new CartV1Dto.AddToCartRequest(
                    999L, 2);

            // act
            ParameterizedTypeReference<ApiResponse<Void>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                    ENDPOINT_CART,
                    HttpMethod.POST,
                    new HttpEntity<>(request, memberAuthHeaders()),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
