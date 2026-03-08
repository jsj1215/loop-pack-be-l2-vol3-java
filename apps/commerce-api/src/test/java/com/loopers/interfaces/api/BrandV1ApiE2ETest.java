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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/*
  [E2E 테스트]

  대상 : 브랜드 고객 API 전체 흐름

  테스트 범위: HTTP 요청 → Controller → Facade → Service → Repository → Database
  사용 라이브러리 : JUnit 5, Spring Boot Test, TestRestTemplate, Testcontainers, AssertJ
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrandV1ApiE2ETest {

    private static final String ENDPOINT_BRANDS = "/api/v1/brands";

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public BrandV1ApiE2ETest(
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

    private Brand saveActiveBrand(String name, String description) {
        Brand brand = new Brand(name, description);
        brand.changeStatus(BrandStatus.ACTIVE);
        return brandJpaRepository.save(brand);
    }

    private Brand savePendingBrand(String name, String description) {
        Brand brand = new Brand(name, description);
        return brandJpaRepository.save(brand);
    }

    @DisplayName("GET /api/v1/brands/{brandId}")
    @Nested
    class GetBrand {

        @Test
        @DisplayName("활성 브랜드를 조회하면, 200 OK와 브랜드 정보를 반환한다.")
        void success_whenActiveBrand() {
            // arrange
            Brand brand = saveActiveBrand("테스트브랜드", "브랜드 설명");

            // act
            ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_BRANDS + "/" + brand.getId(),
                    HttpMethod.GET,
                    null,
                    responseType);

            // assert
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().name()).isEqualTo("테스트브랜드"),
                    () -> assertThat(response.getBody().data().description()).isEqualTo("브랜드 설명"),
                    () -> assertThat(response.getBody().data().status()).isEqualTo(BrandStatus.ACTIVE));
        }

        @Test
        @DisplayName("존재하지 않는 브랜드를 조회하면, 404 NOT_FOUND를 반환한다.")
        void fail_whenBrandNotFound() {
            // act
            ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_BRANDS + "/999",
                    HttpMethod.GET,
                    null,
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("비활성 브랜드를 조회하면, 404 NOT_FOUND를 반환한다.")
        void fail_whenBrandIsInactive() {
            // arrange
            Brand brand = savePendingBrand("비활성브랜드", "비활성 설명");

            // act
            ParameterizedTypeReference<ApiResponse<BrandV1Dto.BrandResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_BRANDS + "/" + brand.getId(),
                    HttpMethod.GET,
                    null,
                    responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
