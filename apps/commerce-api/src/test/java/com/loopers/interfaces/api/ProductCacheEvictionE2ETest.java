package com.loopers.interfaces.api;

import com.loopers.application.product.AdminProductFacade;
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

/**
 * [E2E 테스트 - 캐시 무효화(Eviction)]
 *
 * 대상: 관리자 상품 수정/삭제 후 사용자 API에서 최신 데이터가 반환되는지 검증
 * 테스트 범위: 사용자 API(HTTP) → Redis 캐시 → AdminProductFacade(evict) → 재조회 시 최신 데이터
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductCacheEvictionE2ETest {

    private static final String ENDPOINT_PRODUCTS = "/api/v1/products";

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final ProductOptionJpaRepository productOptionJpaRepository;
    private final AdminProductFacade adminProductFacade;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;
    private final RedisTemplate<String, String> redisTemplate;

    @Autowired
    public ProductCacheEvictionE2ETest(
            TestRestTemplate testRestTemplate,
            BrandJpaRepository brandJpaRepository,
            ProductJpaRepository productJpaRepository,
            ProductOptionJpaRepository productOptionJpaRepository,
            AdminProductFacade adminProductFacade,
            DatabaseCleanUp databaseCleanUp,
            RedisCleanUp redisCleanUp,
            RedisTemplate<String, String> redisTemplate) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.productOptionJpaRepository = productOptionJpaRepository;
        this.adminProductFacade = adminProductFacade;
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

    private Product saveProduct(Brand brand, String name, int price) {
        Product product = new Product(brand, name, price, 8000, 1000, 2500,
                "상품 설명", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y", List.of());
        return productJpaRepository.save(product);
    }

    private ProductOption saveProductOption(Long productId) {
        ProductOption option = new ProductOption(productId, "기본 옵션", 100);
        return productOptionJpaRepository.save(option);
    }

    @DisplayName("상품 상세 캐시 무효화")
    @Nested
    class ProductDetailCacheEviction {

        @Test
        @DisplayName("관리자가 상품을 수정하면, 캐시가 삭제되고 사용자 API에서 최신 데이터가 반환된다.")
        void returnsUpdatedData_afterAdminUpdate() {
            // given - 상품 생성 + 사용자 API 호출로 캐시 적재
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand, "원래 상품명", 10000);
            saveProductOption(product.getId());

            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};

            // 첫 조회로 Redis 캐시 적재
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> firstResponse =
                    testRestTemplate.exchange(
                            ENDPOINT_PRODUCTS + "/" + product.getId(),
                            HttpMethod.GET, null, responseType);

            assertThat(firstResponse.getBody().data().name()).isEqualTo("원래 상품명");
            assertThat(redisTemplate.opsForValue().get("product:detail:" + product.getId())).isNotNull();

            // when - 관리자가 상품명 수정 (AdminProductFacade가 evictDetail 호출)
            adminProductFacade.updateProduct(
                    product.getId(), "수정된 상품명", 10000, 8000, 1000, 2500,
                    "상품 설명", ProductStatus.ON_SALE, "Y",
                    List.of(new ProductOption(null, "기본 옵션", 100)));

            // then - 캐시가 삭제되고, 재조회 시 최신 데이터가 반환된다
            assertThat(redisTemplate.opsForValue().get("product:detail:" + product.getId())).isNull();

            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> secondResponse =
                    testRestTemplate.exchange(
                            ENDPOINT_PRODUCTS + "/" + product.getId(),
                            HttpMethod.GET, null, responseType);

            assertAll(
                    () -> assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK),
                    () -> assertThat(secondResponse.getBody().data().name()).isEqualTo("수정된 상품명")
            );
        }

        @Test
        @DisplayName("관리자가 상품을 삭제하면, 캐시가 삭제되고 사용자 API에서 404를 반환한다.")
        void returns404_afterAdminDelete() {
            // given - 상품 생성 + 사용자 API 호출로 캐시 적재
            Brand brand = saveActiveBrand();
            Product product = saveProduct(brand, "삭제 예정 상품", 10000);
            saveProductOption(product.getId());

            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                    new ParameterizedTypeReference<>() {};

            // 첫 조회로 Redis 캐시 적재
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> firstResponse =
                    testRestTemplate.exchange(
                            ENDPOINT_PRODUCTS + "/" + product.getId(),
                            HttpMethod.GET, null, responseType);

            assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(redisTemplate.opsForValue().get("product:detail:" + product.getId())).isNotNull();

            // when - 관리자가 상품 삭제 (AdminProductFacade가 evictDetail 호출)
            adminProductFacade.deleteProduct(product.getId());

            // then - 캐시가 삭제되고, 재조회 시 404가 반환된다
            Set<String> keys = redisTemplate.keys("product:detail:" + product.getId());
            assertThat(keys).isEmpty();

            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> secondResponse =
                    testRestTemplate.exchange(
                            ENDPOINT_PRODUCTS + "/" + product.getId(),
                            HttpMethod.GET, null, responseType);

            assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
