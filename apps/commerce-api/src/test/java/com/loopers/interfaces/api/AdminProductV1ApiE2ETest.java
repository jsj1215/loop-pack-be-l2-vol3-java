package com.loopers.interfaces.api;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductStatus;
import com.loopers.infrastructure.brand.BrandJpaRepository;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/*
  [E2E 테스트]

  대상 : 어드민 상품 API 전체 흐름

  테스트 범위: HTTP 요청 → Controller → Facade → Service → Repository → Database
  사용 라이브러리 : JUnit 5, Spring Boot Test, TestRestTemplate, Testcontainers, AssertJ
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminProductV1ApiE2ETest {

    private static final String ENDPOINT_ADMIN_PRODUCTS = "/api-admin/v1/products";
    private static final String HEADER_ADMIN_LDAP = "X-Loopers-Ldap";
    private static final String ADMIN_LDAP_VALUE = "loopers.admin";

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final ProductOptionJpaRepository productOptionJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public AdminProductV1ApiE2ETest(
            TestRestTemplate testRestTemplate,
            BrandJpaRepository brandJpaRepository,
            ProductJpaRepository productJpaRepository,
            ProductOptionJpaRepository productOptionJpaRepository,
            DatabaseCleanUp databaseCleanUp) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.productOptionJpaRepository = productOptionJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_ADMIN_LDAP, ADMIN_LDAP_VALUE);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
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

    @DisplayName("POST /api-admin/v1/products")
    @Nested
    class CreateProduct {

        @Test
        @DisplayName("유효한 요청이면, 201 CREATED와 상품 상세 정보를 반환한다.")
        void success_whenValidRequest() {
            // arrange
            Brand brand = saveActiveBrand();

            ProductV1Dto.CreateProductRequest request = new ProductV1Dto.CreateProductRequest(
                    brand.getId(),
                    "신규상품",
                    10000,
                    MarginType.AMOUNT,
                    2000,
                    1000,
                    2500,
                    "상품 설명",
                    List.of(new ProductV1Dto.ProductOptionRequest("기본 옵션", 50)));

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_PRODUCTS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, adminHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().name()).isEqualTo("신규상품"),
                    () -> assertThat(response.getBody().data().id()).isNotNull());
        }

        @Test
        @DisplayName("어드민 인증 없이 요청하면, 401 UNAUTHORIZED를 반환한다.")
        void fail_whenNoAdminAuth() {
            // arrange
            Brand brand = saveActiveBrand();

            ProductV1Dto.CreateProductRequest request = new ProductV1Dto.CreateProductRequest(
                    brand.getId(), "신규상품", 10000, MarginType.AMOUNT, 2000,
                    1000, 2500, "상품 설명",
                    List.of(new ProductV1Dto.ProductOptionRequest("기본 옵션", 50)));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_PRODUCTS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @DisplayName("GET /api-admin/v1/products")
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
                    ENDPOINT_ADMIN_PRODUCTS + "?page=0&size=10",
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data()).isNotNull());
        }
    }

    @DisplayName("GET /api-admin/v1/products/{productId}")
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
                    ENDPOINT_ADMIN_PRODUCTS + "/" + product.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().name()).isEqualTo("테스트상품"));
        }

        @Test
        @DisplayName("존재하지 않는 상품을 조회하면, 404 NOT_FOUND를 반환한다.")
        void fail_whenProductNotFound() {
            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_PRODUCTS + "/999",
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{productId}")
    @Nested
    class UpdateProduct {

        @Test
        @DisplayName("유효한 요청이면, 200 OK와 수정된 상품 정보를 반환한다.")
        void success_whenValidRequest() {
            // arrange
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand);
            saveProductOption(product.getId());

            ProductV1Dto.UpdateProductRequest request = new ProductV1Dto.UpdateProductRequest(
                    "수정상품", 12000, 9000, 1500, 3000,
                    "수정 설명", ProductStatus.ON_SALE, "Y",
                    List.of(new ProductV1Dto.ProductOptionRequest("수정 옵션", 200)));

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_PRODUCTS + "/" + product.getId(),
                    HttpMethod.PUT,
                    new HttpEntity<>(request, adminHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().name()).isEqualTo("수정상품"));
        }
    }

    @DisplayName("DELETE /api-admin/v1/products/{productId}")
    @Nested
    class DeleteProduct {

        @Test
        @DisplayName("존재하는 상품을 삭제하면, 200 OK를 반환한다.")
        void success_whenProductExists() {
            // arrange
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand);

            // act
            ParameterizedTypeReference<ApiResponse<Void>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_PRODUCTS + "/" + product.getId(),
                    HttpMethod.DELETE,
                    new HttpEntity<>(adminHeaders()),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
