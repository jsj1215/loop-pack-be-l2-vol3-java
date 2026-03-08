package com.loopers.interfaces.api;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.interfaces.api.brand.dto.BrandV1Dto;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/*
  [E2E 테스트]

  대상 : 어드민 브랜드 API 전체 흐름

  테스트 범위: HTTP 요청 → Controller → Facade → Service → Repository → Database
  사용 라이브러리 : JUnit 5, Spring Boot Test, TestRestTemplate, Testcontainers, AssertJ
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminBrandV1ApiE2ETest {

    private static final String ENDPOINT_ADMIN_BRANDS = "/api-admin/v1/brands";
    private static final String HEADER_ADMIN_LDAP = "X-Loopers-Ldap";
    private static final String ADMIN_LDAP_VALUE = "loopers.admin";

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public AdminBrandV1ApiE2ETest(
            TestRestTemplate testRestTemplate,
            BrandJpaRepository brandJpaRepository,
            DatabaseCleanUp databaseCleanUp) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
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

    private Brand saveBrand(String name, String description, BrandStatus status) {
        Brand brand = new Brand(name, description);
        brand.changeStatus(status);
        return brandJpaRepository.save(brand);
    }

    @DisplayName("POST /api-admin/v1/brands")
    @Nested
    class CreateBrand {

        @Test
        @DisplayName("유효한 요청이면, 201 CREATED와 브랜드 정보를 반환한다.")
        void success_whenValidRequest() {
            // arrange
            BrandV1Dto.CreateBrandRequest request = new BrandV1Dto.CreateBrandRequest(
                    "신규브랜드", "브랜드 설명");

            // act
            ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_BRANDS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, adminHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().name()).isEqualTo("신규브랜드"),
                    () -> assertThat(response.getBody().data().description()).isEqualTo("브랜드 설명"),
                    () -> assertThat(response.getBody().data().id()).isNotNull());
        }

        @Test
        @DisplayName("어드민 인증 없이 요청하면, 401 UNAUTHORIZED를 반환한다.")
        void fail_whenNoAdminAuth() {
            // arrange
            BrandV1Dto.CreateBrandRequest request = new BrandV1Dto.CreateBrandRequest(
                    "신규브랜드", "브랜드 설명");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // act
            ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_BRANDS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("중복된 브랜드명이면, 409 CONFLICT를 반환한다.")
        void fail_whenDuplicateName() {
            // arrange
            saveBrand("기존브랜드", "설명", BrandStatus.ACTIVE);

            BrandV1Dto.CreateBrandRequest request = new BrandV1Dto.CreateBrandRequest(
                    "기존브랜드", "다른 설명");

            // act
            ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_BRANDS,
                    HttpMethod.POST,
                    new HttpEntity<>(request, adminHeaders()),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @DisplayName("GET /api-admin/v1/brands")
    @Nested
    class GetBrands {

        @Test
        @DisplayName("브랜드 목록을 조회하면, 200 OK와 페이지 정보를 반환한다.")
        void success_whenGetBrands() {
            // arrange
            saveBrand("브랜드A", "설명A", BrandStatus.ACTIVE);
            saveBrand("브랜드B", "설명B", BrandStatus.PENDING);

            // act
            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_BRANDS + "?page=0&size=10",
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

    @DisplayName("GET /api-admin/v1/brands/{brandId}")
    @Nested
    class GetBrand {

        @Test
        @DisplayName("존재하는 브랜드를 조회하면, 200 OK와 브랜드 정보를 반환한다.")
        void success_whenBrandExists() {
            // arrange
            Brand brand = saveBrand("테스트브랜드", "설명", BrandStatus.ACTIVE);

            // act
            ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_BRANDS + "/" + brand.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().name()).isEqualTo("테스트브랜드"));
        }

        @Test
        @DisplayName("존재하지 않는 브랜드를 조회하면, 404 NOT_FOUND를 반환한다.")
        void fail_whenBrandNotFound() {
            // act
            ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_BRANDS + "/999",
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders()),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("PUT /api-admin/v1/brands/{brandId}")
    @Nested
    class UpdateBrand {

        @Test
        @DisplayName("유효한 요청이면, 200 OK와 수정된 브랜드 정보를 반환한다.")
        void success_whenValidRequest() {
            // arrange
            Brand brand = saveBrand("원래브랜드", "원래 설명", BrandStatus.PENDING);

            BrandV1Dto.UpdateBrandRequest request = new BrandV1Dto.UpdateBrandRequest(
                    "수정브랜드", "수정 설명", BrandStatus.ACTIVE);

            // act
            ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_BRANDS + "/" + brand.getId(),
                    HttpMethod.PUT,
                    new HttpEntity<>(request, adminHeaders()),
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().name()).isEqualTo("수정브랜드"),
                    () -> assertThat(response.getBody().data().description()).isEqualTo("수정 설명"),
                    () -> assertThat(response.getBody().data().status()).isEqualTo(BrandStatus.ACTIVE));
        }
    }

    @DisplayName("DELETE /api-admin/v1/brands/{brandId}")
    @Nested
    class DeleteBrand {

        @Test
        @DisplayName("존재하는 브랜드를 삭제하면, 200 OK를 반환한다.")
        void success_whenBrandExists() {
            // arrange
            Brand brand = saveBrand("삭제브랜드", "설명", BrandStatus.ACTIVE);

            // act
            ParameterizedTypeReference<ApiResponse<Void>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response = testRestTemplate.exchange(
                    ENDPOINT_ADMIN_BRANDS + "/" + brand.getId(),
                    HttpMethod.DELETE,
                    new HttpEntity<>(adminHeaders()),
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
