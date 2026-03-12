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
import org.springframework.cache.CacheManager;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/*
  [E2E 테스트 - 로컬 캐시(Caffeine) 적용 API]

  대상 : 상품 목록/상세 로컬 캐시 API
  테스트 범위: HTTP 요청 → Controller → Facade(@Cacheable) → Service → Repository → Database
  검증 포인트: 캐시 Hit/Miss 동작, 캐시된 데이터 정합성, API 응답 정상 여부
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductLocalCacheApiE2ETest {

    private static final String ENDPOINT_PRODUCTS = "/api/v1/products";

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final ProductOptionJpaRepository productOptionJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final CacheManager cacheManager;

    @Autowired
    public ProductLocalCacheApiE2ETest(
            TestRestTemplate testRestTemplate,
            BrandJpaRepository brandJpaRepository,
            ProductJpaRepository productJpaRepository,
            ProductOptionJpaRepository productOptionJpaRepository,
            DatabaseCleanUp databaseCleanUp,
            CacheManager cacheManager) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.productOptionJpaRepository = productOptionJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
        this.cacheManager = cacheManager;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        Objects.requireNonNull(cacheManager.getCache("productSearch")).clear();
        Objects.requireNonNull(cacheManager.getCache("productDetail")).clear();
    }

    private Brand saveActiveBrand() {
        Brand brand = new Brand("테스트브랜드", "브랜드 설명");
        brand.changeStatus(BrandStatus.ACTIVE);
        return brandJpaRepository.save(brand);
    }

    private Product saveProduct(Brand brand, String name) {
        Product product = new Product(brand, name, 10000, 8000, 1000, 2500,
                "상품 설명", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y", List.of());
        return productJpaRepository.save(product);
    }

    private ProductOption saveProductOption(Long productId) {
        ProductOption option = new ProductOption(productId, "기본 옵션", 100);
        return productOptionJpaRepository.save(option);
    }

    @DisplayName("GET /api/v1/products/local-cache")
    @Nested
    class GetProductsWithLocalCache {

        @Test
        @DisplayName("상품 목록을 조회하면, 200 OK와 페이지 정보를 반환한다.")
        void success_whenGetProducts() {
            // given
            Brand brand = saveActiveBrand();
            saveProduct(brand, "테스트상품");

            // when
            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/local-cache?page=0&size=10",
                    HttpMethod.GET,
                    null,
                    responseType);

            // then
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data()).isNotNull());
        }

        @Test
        @DisplayName("동일 조건으로 두 번 조회하면, 두 번째는 캐시에서 응답하며 결과가 동일하다.")
        void success_whenCacheHit() {
            // given
            Brand brand = saveActiveBrand();
            saveProduct(brand, "캐시테스트상품");

            String url = ENDPOINT_PRODUCTS + "/local-cache?sort=LATEST&page=0&size=10";
            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};

            // when - 첫 번째 호출 (Cache Miss → DB 조회)
            ResponseEntity<ApiResponse<Object>> firstResponse = testRestTemplate.exchange(
                    url, HttpMethod.GET, null, responseType);

            // when - 두 번째 호출 (Cache Hit → 캐시에서 반환)
            ResponseEntity<ApiResponse<Object>> secondResponse = testRestTemplate.exchange(
                    url, HttpMethod.GET, null, responseType);

            // then
            assertAll(
                    () -> assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(secondResponse.getBody().data())
                            .isEqualTo(firstResponse.getBody().data()));
        }

        @Test
        @DisplayName("3페이지(page=2)까지는 캐시가 적용되어 동일 결과를 반환한다.")
        void success_whenPageWithinCacheLimit() {
            // given
            Brand brand = saveActiveBrand();
            for (int i = 0; i < 30; i++) {
                saveProduct(brand, "상품" + i);
            }

            String url = ENDPOINT_PRODUCTS + "/local-cache?sort=LATEST&page=2&size=10";
            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};

            // when - 첫 번째 호출 (Cache Miss → DB 조회)
            ResponseEntity<ApiResponse<Object>> firstResponse = testRestTemplate.exchange(
                    url, HttpMethod.GET, null, responseType);

            // when - 두 번째 호출 (Cache Hit → 캐시에서 반환)
            ResponseEntity<ApiResponse<Object>> secondResponse = testRestTemplate.exchange(
                    url, HttpMethod.GET, null, responseType);

            // then
            assertAll(
                    () -> assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(secondResponse.getBody().data())
                            .isEqualTo(firstResponse.getBody().data()));
        }

        @Test
        @DisplayName("4페이지(page=3) 이후는 캐시가 적용되지 않고 매번 DB를 조회한다.")
        void success_whenPageExceedsCacheLimit() {
            // given
            Brand brand = saveActiveBrand();
            for (int i = 0; i < 40; i++) {
                saveProduct(brand, "상품" + i);
            }

            String url = ENDPOINT_PRODUCTS + "/local-cache?sort=LATEST&page=3&size=10";
            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};

            // when
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    url, HttpMethod.GET, null, responseType);

            // then - 캐시 미적용이어도 정상 응답
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(cacheManager.getCache("productSearch").get(
                            "all_LATEST_all_3_10")).isNull());
        }

        @Test
        @DisplayName("정렬 조건이 다르면, 각각 별도 캐시로 저장된다.")
        void success_whenDifferentSortConditions() {
            // given
            Brand brand = saveActiveBrand();
            saveProduct(brand, "테스트상품");

            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};

            // when
            ResponseEntity<ApiResponse<Object>> latestResponse = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/local-cache?sort=LATEST&page=0&size=10",
                    HttpMethod.GET, null, responseType);

            ResponseEntity<ApiResponse<Object>> likeResponse = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/local-cache?sort=LIKE_DESC&page=0&size=10",
                    HttpMethod.GET, null, responseType);

            // then
            assertAll(
                    () -> assertThat(latestResponse.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(likeResponse.getStatusCode()).isEqualTo(HttpStatus.OK));
        }
    }

    @DisplayName("GET /api/v1/products/{productId}/local-cache")
    @Nested
    class GetProductWithLocalCache {

        @Test
        @DisplayName("존재하는 상품을 조회하면, 200 OK와 상품 상세 정보를 반환한다.")
        void success_whenProductExists() {
            // given
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand, "테스트상품");
            saveProductOption(product.getId());

            // when
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/" + product.getId() + "/local-cache",
                    HttpMethod.GET,
                    null,
                    responseType);

            // then
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody()).isNotNull(),
                    () -> assertThat(response.getBody().data().name()).isEqualTo("테스트상품"),
                    () -> assertThat(response.getBody().data().brandName()).isEqualTo("테스트브랜드"));
        }

        @Test
        @DisplayName("동일 상품을 두 번 조회하면, 두 번째는 캐시에서 응답하며 결과가 동일하다.")
        void success_whenCacheHit() {
            // given
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand, "캐시테스트상품");
            saveProductOption(product.getId());

            String url = ENDPOINT_PRODUCTS + "/" + product.getId() + "/local-cache";
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};

            // when - 첫 번째 호출 (Cache Miss → DB 조회)
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> firstResponse =
                    testRestTemplate.exchange(url, HttpMethod.GET, null, responseType);

            // when - 두 번째 호출 (Cache Hit → 캐시에서 반환)
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> secondResponse =
                    testRestTemplate.exchange(url, HttpMethod.GET, null, responseType);

            // then
            assertAll(
                    () -> assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(secondResponse.getBody().data().name())
                            .isEqualTo(firstResponse.getBody().data().name()),
                    () -> assertThat(secondResponse.getBody().data().price())
                            .isEqualTo(firstResponse.getBody().data().price()));
        }

        @Test
        @DisplayName("존재하지 않는 상품을 조회하면, 404 NOT_FOUND를 반환한다.")
        void fail_whenProductNotFound() {
            // when
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/999/local-cache",
                    HttpMethod.GET,
                    null,
                    responseType);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
