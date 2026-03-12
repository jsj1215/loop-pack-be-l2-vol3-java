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
  [E2E 테스트 - 캐시 미적용 API]

  대상 : 상품 목록/상세 API (캐시 없이 DB 직접 조회)
  테스트 범위: HTTP 요청 → Controller → Facade → Service → Repository → Database
  검증 포인트: 캐시 없이 정상 조회, Redis에 캐시가 저장되지 않음
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductNoCacheApiE2ETest {

    private static final String ENDPOINT_PRODUCTS = "/api/v1/products";

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final ProductOptionJpaRepository productOptionJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;
    private final RedisTemplate<String, String> redisTemplate;

    @Autowired
    public ProductNoCacheApiE2ETest(
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

    @DisplayName("GET /api/v1/products/no-cache - 상품 목록 캐시 미적용")
    @Nested
    class GetProductsNoCache {

        @Test
        @DisplayName("상품 목록을 조회하면, 정상적으로 결과를 반환한다.")
        void success_whenGetProducts_thenReturnsProducts() {
            // given
            Brand brand = saveActiveBrand();
            saveProduct(brand, "테스트상품1");
            saveProduct(brand, "테스트상품2");

            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};

            // when
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/no-cache?sort=LATEST&page=0&size=10",
                    HttpMethod.GET, null, responseType);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("상품 목록을 조회해도, Redis에 캐시가 저장되지 않는다.")
        void success_whenGetProducts_thenNoCacheCreated() {
            // given
            Brand brand = saveActiveBrand();
            saveProduct(brand, "테스트상품");

            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};

            // when
            testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/no-cache?sort=LATEST&page=0&size=10",
                    HttpMethod.GET, null, responseType);

            // then
            Set<String> keys = redisTemplate.keys("product:search:*");
            assertThat(keys).isEmpty();
        }

        @Test
        @DisplayName("동일 조건으로 두 번 조회해도, 매번 DB에서 조회하며 결과가 동일하다.")
        void success_whenCalledTwice_thenBothFromDb() {
            // given
            Brand brand = saveActiveBrand();
            saveProduct(brand, "테스트상품");

            String url = ENDPOINT_PRODUCTS + "/no-cache?sort=LATEST&page=0&size=10";
            ParameterizedTypeReference<ApiResponse<Object>> responseType =
                    new ParameterizedTypeReference<>() {};

            // when
            ResponseEntity<ApiResponse<Object>> firstResponse = testRestTemplate.exchange(
                    url, HttpMethod.GET, null, responseType);
            ResponseEntity<ApiResponse<Object>> secondResponse = testRestTemplate.exchange(
                    url, HttpMethod.GET, null, responseType);

            // then
            assertAll(
                    () -> assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(secondResponse.getBody().data())
                            .isEqualTo(firstResponse.getBody().data()),
                    () -> assertThat(redisTemplate.keys("product:search:*")).isEmpty());
        }
    }

    @DisplayName("GET /api/v1/products/{productId}/no-cache - 상품 상세 캐시 미적용")
    @Nested
    class GetProductNoCache {

        @Test
        @DisplayName("존재하는 상품을 조회하면, 정상적으로 상세 정보를 반환한다.")
        void success_whenProductExists_thenReturnsDetail() {
            // given
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand, "테스트상품");
            saveProductOption(product.getId());

            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};

            // when
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/" + product.getId() + "/no-cache",
                    HttpMethod.GET, null, responseType);

            // then
            assertAll(
                    () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(response.getBody().data().name()).isEqualTo("테스트상품"));
        }

        @Test
        @DisplayName("상품을 조회해도, Redis에 캐시가 저장되지 않는다.")
        void success_whenProductExists_thenNoCacheCreated() {
            // given
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand, "테스트상품");
            saveProductOption(product.getId());

            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};

            // when
            testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/" + product.getId() + "/no-cache",
                    HttpMethod.GET, null, responseType);

            // then
            Set<String> keys = redisTemplate.keys("product:detail:*");
            assertThat(keys).isEmpty();
        }

        @Test
        @DisplayName("존재하지 않는 상품을 조회하면, 404 NOT_FOUND를 반환한다.")
        void fail_whenProductNotFound_thenReturns404() {
            // when
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/999/no-cache",
                    HttpMethod.GET, null, responseType);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
