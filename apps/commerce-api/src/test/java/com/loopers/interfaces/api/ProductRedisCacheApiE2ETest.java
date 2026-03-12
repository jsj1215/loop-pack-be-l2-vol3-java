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
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/*
  [E2E 테스트 - Redis 캐시 적용 API]

  대상 : 상품 목록/상세 API (Redis 캐시가 기존 메서드에 적용됨)
  테스트 범위: HTTP 요청 → Controller → Facade(Redis Cache) → Service → Repository → Database
  검증 포인트: 캐시 Hit/Miss 동작, 캐시 키 생성, 페이지 제한(3페이지), 캐시 무효화
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductRedisCacheApiE2ETest {

    private static final String ENDPOINT_PRODUCTS = "/api/v1/products";

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final ProductOptionJpaRepository productOptionJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;
    private final RedisTemplate<String, String> redisTemplate;

    @Autowired
    public ProductRedisCacheApiE2ETest(
            TestRestTemplate testRestTemplate,
            BrandJpaRepository brandJpaRepository,
            ProductJpaRepository productJpaRepository,
            ProductOptionJpaRepository productOptionJpaRepository,
            DatabaseCleanUp databaseCleanUp,
            RedisCleanUp redisCleanUp,
            RedisTemplate<String, String> redisTemplate) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.productOptionJpaRepository = productOptionJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
        this.redisTemplate = redisTemplate;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
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

    @DisplayName("GET /api/v1/products - 상품 목록 Redis 캐시")
    @Nested
    class GetProductsWithRedisCache {

        @Test
        @DisplayName("상품 목록을 조회하면, Redis에 캐시가 저장된다.")
        void success_whenGetProducts_thenCacheCreated() {
            // given
            Brand brand = saveActiveBrand();
            saveProduct(brand, "테스트상품");

            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};

            // when
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "?sort=LATEST&page=0&size=10",
                    HttpMethod.GET, null, responseType);

            // then
            String expectedKey = "product:search:all:LATEST:all:0:10";
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(redisTemplate.opsForValue().get(expectedKey)).isNotNull());
        }

        @Test
        @DisplayName("동일 조건으로 두 번 조회하면, 두 번째는 Redis 캐시에서 응답하며 결과가 동일하다.")
        void success_whenCacheHit() {
            // given
            Brand brand = saveActiveBrand();
            saveProduct(brand, "캐시테스트상품");

            String url = ENDPOINT_PRODUCTS + "?sort=LATEST&page=0&size=10";
            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};

            // when - 첫 번째 호출 (Cache Miss → DB 조회 → Redis 저장)
            ResponseEntity<ApiResponse<Object>> firstResponse = testRestTemplate.exchange(
                    url, HttpMethod.GET, null, responseType);

            // when - 두 번째 호출 (Cache Hit → Redis에서 반환)
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
        @DisplayName("정렬 조건이 다르면, 각각 별도 캐시 키로 저장된다.")
        void success_whenDifferentSortConditions() {
            // given
            Brand brand = saveActiveBrand();
            saveProduct(brand, "테스트상품");

            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};

            // when
            testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "?sort=LATEST&page=0&size=10",
                    HttpMethod.GET, null, responseType);

            testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "?sort=LIKE_DESC&page=0&size=10",
                    HttpMethod.GET, null, responseType);

            // then
            Set<String> keys = redisTemplate.keys("product:search:*");
            assertThat(keys).hasSize(2);
        }

        @Test
        @DisplayName("4페이지(page=3) 이상 조회 시 캐시에 저장하지 않는다.")
        void success_whenPageExceedsLimit_thenNoCache() {
            // given
            Brand brand = saveActiveBrand();
            saveProduct(brand, "테스트상품");

            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};

            // when
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "?sort=LATEST&page=3&size=10",
                    HttpMethod.GET, null, responseType);

            // then
            Set<String> keys = redisTemplate.keys("product:search:*");
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(keys).isEmpty());
        }
    }

    @DisplayName("GET /api/v1/products/{productId} - 상품 상세 Redis 캐시")
    @Nested
    class GetProductWithRedisCache {

        @Test
        @DisplayName("존재하는 상품을 조회하면, Redis에 캐시가 저장된다.")
        void success_whenProductExists_thenCacheCreated() {
            // given
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand, "테스트상품");
            saveProductOption(product.getId());

            // when
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/" + product.getId(),
                    HttpMethod.GET, null, responseType);

            // then
            String expectedKey = "product:detail:" + product.getId();
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody().data().name()).isEqualTo("테스트상품"),
                    () -> assertThat(redisTemplate.opsForValue().get(expectedKey)).isNotNull());
        }

        @Test
        @DisplayName("동일 상품을 두 번 조회하면, 두 번째는 Redis 캐시에서 응답하며 결과가 동일하다.")
        void success_whenCacheHit() {
            // given
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand, "캐시테스트상품");
            saveProductOption(product.getId());

            String url = ENDPOINT_PRODUCTS + "/" + product.getId();
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};

            // when - 첫 번째 호출 (Cache Miss → DB 조회 → Redis 저장)
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> firstResponse =
                    testRestTemplate.exchange(url, HttpMethod.GET, null, responseType);

            // when - 두 번째 호출 (Cache Hit → Redis에서 반환)
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
        @DisplayName("존재하지 않는 상품을 조회하면, 404 NOT_FOUND를 반환하고 캐시에 저장하지 않는다.")
        void fail_whenProductNotFound_thenNoCache() {
            // when
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/999",
                    HttpMethod.GET, null, responseType);

            // then
            Set<String> keys = redisTemplate.keys("product:detail:*");
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                    () -> assertThat(keys).isEmpty());
        }
    }
}
