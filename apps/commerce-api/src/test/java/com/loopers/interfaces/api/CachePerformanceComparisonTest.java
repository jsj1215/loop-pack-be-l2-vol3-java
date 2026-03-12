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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [성능 비교 테스트 - Redis Cache vs Local Cache (Caffeine)]
 *
 * 테스트 대상: 상품 목록/상세 API의 캐시 전략별 응답 시간 비교
 * 테스트 유형: 성능 테스트 (E2E - HTTP 호출)
 *
 * 측정 항목:
 *   1) Cache Miss 응답시간 (첫 요청: DB 조회 + 캐시 저장)
 *   2) Cache Hit 응답시간 (캐시에서 반환)
 *   3) 반복 호출 평균/p95/p99 응답시간
 *   4) 캐시 무효화 후 재조회 비용
 *
 * 데이터: 브랜드 5개, 상품 50개, 옵션 각 1개
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CachePerformanceComparisonTest {

    private static final String ENDPOINT_PRODUCTS = "/api/v1/products";
    private static final int ITERATIONS = 50;

    @Autowired
    private TestRestTemplate testRestTemplate;
    @Autowired
    private BrandJpaRepository brandJpaRepository;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private ProductOptionJpaRepository productOptionJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private RedisCleanUp redisCleanUp;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private CacheManager cacheManager;

    private Long sampleProductId;

    @BeforeAll
    void setUp() {
        // 브랜드 5개, 상품 50개, 옵션 각 1개 생성
        List<Brand> brands = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Brand brand = new Brand("브랜드" + i, "설명" + i);
            brand.changeStatus(BrandStatus.ACTIVE);
            brands.add(brandJpaRepository.save(brand));
        }

        for (int i = 0; i < 50; i++) {
            Brand brand = brands.get(i % 5);
            Product product = new Product(brand, "테스트상품" + i, 10000 + i * 100, 8000 + i * 80,
                    1000, 2500, "상품 설명" + i, MarginType.AMOUNT, ProductStatus.ON_SALE, "Y", List.of());
            Product saved = productJpaRepository.save(product);
            productOptionJpaRepository.save(new ProductOption(saved.getId(), "옵션" + i, 100));

            if (i == 0) {
                sampleProductId = saved.getId();
            }
        }
    }

    @AfterAll
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        Objects.requireNonNull(cacheManager.getCache("productSearch")).clear();
        Objects.requireNonNull(cacheManager.getCache("productDetail")).clear();
    }

    // ==================== 1. 상품 목록 조회 성능 비교 ====================

    @Test
    @Order(1)
    @DisplayName("[No Cache] 상품 목록 조회 - DB 직접 조회 기준선 측정")
    void baseline_noCache_productList() {
        // given - 캐시를 모두 비운 상태에서 매번 DB 직접 조회 (page=3은 캐시 미적용)
        String url = ENDPOINT_PRODUCTS + "?sort=LATEST&page=3&size=10";
        ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};

        // warm-up
        testRestTemplate.exchange(url, HttpMethod.GET, null, responseType);

        // when
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    url, HttpMethod.GET, null, responseType);
            long elapsed = System.nanoTime() - start;

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            durations.add(elapsed);
        }

        // then
        printStats("[No Cache] 상품 목록 (DB 직접)", durations);
    }

    @Test
    @Order(2)
    @DisplayName("[Redis] 상품 목록 조회 - Cache Miss(첫 요청) 측정")
    void redis_cacheMiss_productList() {
        // given - Redis 캐시 비우기
        redisCleanUp.truncateAll();
        String url = ENDPOINT_PRODUCTS + "?sort=LATEST&page=0&size=10";
        ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};

        // when - Cache Miss (DB 조회 + Redis 저장)
        long start = System.nanoTime();
        ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                url, HttpMethod.GET, null, responseType);
        long elapsed = System.nanoTime() - start;

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        System.out.printf("[Redis] 상품 목록 Cache Miss: %.2fms%n", elapsed / 1_000_000.0);
    }

    @Test
    @Order(3)
    @DisplayName("[Redis] 상품 목록 조회 - Cache Hit 반복 성능 측정")
    void redis_cacheHit_productList() {
        // given - Order(2)에서 이미 캐시가 채워진 상태
        String url = ENDPOINT_PRODUCTS + "?sort=LATEST&page=0&size=10";
        ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};

        // warm-up
        testRestTemplate.exchange(url, HttpMethod.GET, null, responseType);

        // when
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    url, HttpMethod.GET, null, responseType);
            long elapsed = System.nanoTime() - start;

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            durations.add(elapsed);
        }

        // then
        printStats("[Redis] 상품 목록 Cache Hit", durations);
    }

    @Test
    @Order(4)
    @DisplayName("[Local] 상품 목록 조회 - Cache Miss(첫 요청) 측정")
    void local_cacheMiss_productList() {
        // given - 로컬 캐시 비우기
        Objects.requireNonNull(cacheManager.getCache("productSearch")).clear();
        String url = ENDPOINT_PRODUCTS + "/local-cache?sort=LATEST&page=0&size=10";
        ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};

        // when - Cache Miss (DB 조회 + 로컬 저장)
        long start = System.nanoTime();
        ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                url, HttpMethod.GET, null, responseType);
        long elapsed = System.nanoTime() - start;

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        System.out.printf("[Local] 상품 목록 Cache Miss: %.2fms%n", elapsed / 1_000_000.0);
    }

    @Test
    @Order(5)
    @DisplayName("[Local] 상품 목록 조회 - Cache Hit 반복 성능 측정")
    void local_cacheHit_productList() {
        // given - Order(4)에서 이미 캐시가 채워진 상태
        String url = ENDPOINT_PRODUCTS + "/local-cache?sort=LATEST&page=0&size=10";
        ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};

        // warm-up
        testRestTemplate.exchange(url, HttpMethod.GET, null, responseType);

        // when
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                    url, HttpMethod.GET, null, responseType);
            long elapsed = System.nanoTime() - start;

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            durations.add(elapsed);
        }

        // then
        printStats("[Local] 상품 목록 Cache Hit", durations);
    }

    // ==================== 2. 상품 상세 조회 성능 비교 ====================

    @Test
    @Order(6)
    @DisplayName("[No Cache] 상품 상세 조회 - DB 직접 조회 기준선 측정")
    void baseline_noCache_productDetail() {
        // given - 매번 캐시를 비워서 DB 직접 조회 강제
        ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                new ParameterizedTypeReference<>() {};

        // warm-up
        redisCleanUp.truncateAll();
        testRestTemplate.exchange(ENDPOINT_PRODUCTS + "/" + sampleProductId, HttpMethod.GET, null, responseType);

        // when
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            // 매 반복마다 Redis 캐시 삭제하여 항상 Cache Miss 유도
            redisTemplate.delete("product:detail:" + sampleProductId);

            long start = System.nanoTime();
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/" + sampleProductId, HttpMethod.GET, null, responseType);
            long elapsed = System.nanoTime() - start;

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            durations.add(elapsed);
        }

        // then
        printStats("[No Cache] 상품 상세 (DB 직접)", durations);
    }

    @Test
    @Order(7)
    @DisplayName("[Redis] 상품 상세 조회 - Cache Hit 반복 성능 측정")
    void redis_cacheHit_productDetail() {
        // given - 캐시 채우기
        ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                new ParameterizedTypeReference<>() {};
        testRestTemplate.exchange(ENDPOINT_PRODUCTS + "/" + sampleProductId, HttpMethod.GET, null, responseType);

        // warm-up
        testRestTemplate.exchange(ENDPOINT_PRODUCTS + "/" + sampleProductId, HttpMethod.GET, null, responseType);

        // when
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/" + sampleProductId, HttpMethod.GET, null, responseType);
            long elapsed = System.nanoTime() - start;

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            durations.add(elapsed);
        }

        // then
        printStats("[Redis] 상품 상세 Cache Hit", durations);
    }

    @Test
    @Order(8)
    @DisplayName("[Local] 상품 상세 조회 - Cache Hit 반복 성능 측정")
    void local_cacheHit_productDetail() {
        // given - 캐시 채우기
        ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                new ParameterizedTypeReference<>() {};
        testRestTemplate.exchange(
                ENDPOINT_PRODUCTS + "/" + sampleProductId + "/local-cache", HttpMethod.GET, null, responseType);

        // warm-up
        testRestTemplate.exchange(
                ENDPOINT_PRODUCTS + "/" + sampleProductId + "/local-cache", HttpMethod.GET, null, responseType);

        // when
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/" + sampleProductId + "/local-cache",
                    HttpMethod.GET, null, responseType);
            long elapsed = System.nanoTime() - start;

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            durations.add(elapsed);
        }

        // then
        printStats("[Local] 상품 상세 Cache Hit", durations);
    }

    // ==================== 3. 캐시 무효화 후 재조회 비용 ====================

    @Test
    @Order(9)
    @DisplayName("[Redis] 캐시 evict 후 재조회 비용 측정 (상세)")
    void redis_evictAndReload_productDetail() {
        // given
        ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                new ParameterizedTypeReference<>() {};

        // when - evict → 재조회를 반복하여 무효화 비용 측정
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            // 캐시 삭제 (관리자 상품 수정 시나리오)
            redisTemplate.delete("product:detail:" + sampleProductId);

            // 재조회 (Cache Miss → DB 조회 → Redis 저장)
            long start = System.nanoTime();
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/" + sampleProductId, HttpMethod.GET, null, responseType);
            long elapsed = System.nanoTime() - start;

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            durations.add(elapsed);
        }

        // then
        printStats("[Redis] 상품 상세 evict 후 재조회", durations);
    }

    @Test
    @Order(10)
    @DisplayName("[Local] 캐시 evict 후 재조회 비용 측정 (상세)")
    void local_evictAndReload_productDetail() {
        // given
        ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductDetailResponse>> responseType =
                new ParameterizedTypeReference<>() {};

        // when - evict → 재조회를 반복하여 무효화 비용 측정
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            // 캐시 삭제
            Objects.requireNonNull(cacheManager.getCache("productDetail")).clear();

            // 재조회 (Cache Miss → DB 조회 → Local 저장)
            long start = System.nanoTime();
            ResponseEntity<ApiResponse<ProductV1Dto.ProductDetailResponse>> response = testRestTemplate.exchange(
                    ENDPOINT_PRODUCTS + "/" + sampleProductId + "/local-cache",
                    HttpMethod.GET, null, responseType);
            long elapsed = System.nanoTime() - start;

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            durations.add(elapsed);
        }

        // then
        printStats("[Local] 상품 상세 evict 후 재조회", durations);
    }

    // ==================== 4. 다양한 검색 조건에서의 캐시 성능 ====================

    @Test
    @Order(11)
    @DisplayName("[Redis] 다양한 검색 조건 Cache Miss → Hit 전환 비용 측정")
    void redis_variousConditions() {
        // given - 캐시 초기화
        redisCleanUp.truncateAll();

        String[] urls = {
                ENDPOINT_PRODUCTS + "?sort=LATEST&page=0&size=10",
                ENDPOINT_PRODUCTS + "?sort=LIKE_DESC&page=0&size=10",
                ENDPOINT_PRODUCTS + "?sort=PRICE_ASC&page=0&size=10",
                ENDPOINT_PRODUCTS + "?sort=LATEST&page=1&size=10",
                ENDPOINT_PRODUCTS + "?sort=LATEST&page=0&size=20",
        };
        ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};

        // when - 첫 순회: 모든 조건 Cache Miss
        long totalMiss = 0;
        for (String url : urls) {
            long start = System.nanoTime();
            testRestTemplate.exchange(url, HttpMethod.GET, null, responseType);
            totalMiss += System.nanoTime() - start;
        }

        // when - 두 번째 순회: 모든 조건 Cache Hit
        long totalHit = 0;
        for (String url : urls) {
            long start = System.nanoTime();
            testRestTemplate.exchange(url, HttpMethod.GET, null, responseType);
            totalHit += System.nanoTime() - start;
        }

        // then
        double avgMissMs = (totalMiss / (double) urls.length) / 1_000_000.0;
        double avgHitMs = (totalHit / (double) urls.length) / 1_000_000.0;
        System.out.printf("[Redis] 다양한 조건 - Cache Miss 평균: %.2fms, Cache Hit 평균: %.2fms (%.1f%% 감소)%n",
                avgMissMs, avgHitMs, (1 - avgHitMs / avgMissMs) * 100);
    }

    @Test
    @Order(12)
    @DisplayName("[Local] 다양한 검색 조건 Cache Miss → Hit 전환 비용 측정")
    void local_variousConditions() {
        // given - 캐시 초기화
        Objects.requireNonNull(cacheManager.getCache("productSearch")).clear();

        String[] urls = {
                ENDPOINT_PRODUCTS + "/local-cache?sort=LATEST&page=0&size=10",
                ENDPOINT_PRODUCTS + "/local-cache?sort=LIKE_DESC&page=0&size=10",
                ENDPOINT_PRODUCTS + "/local-cache?sort=PRICE_ASC&page=0&size=10",
                ENDPOINT_PRODUCTS + "/local-cache?sort=LATEST&page=1&size=10",
                ENDPOINT_PRODUCTS + "/local-cache?sort=LATEST&page=0&size=20",
        };
        ParameterizedTypeReference<ApiResponse<Object>> responseType = new ParameterizedTypeReference<>() {};

        // when - 첫 순회: 모든 조건 Cache Miss
        long totalMiss = 0;
        for (String url : urls) {
            long start = System.nanoTime();
            testRestTemplate.exchange(url, HttpMethod.GET, null, responseType);
            totalMiss += System.nanoTime() - start;
        }

        // when - 두 번째 순회: 모든 조건 Cache Hit
        long totalHit = 0;
        for (String url : urls) {
            long start = System.nanoTime();
            testRestTemplate.exchange(url, HttpMethod.GET, null, responseType);
            totalHit += System.nanoTime() - start;
        }

        // then
        double avgMissMs = (totalMiss / (double) urls.length) / 1_000_000.0;
        double avgHitMs = (totalHit / (double) urls.length) / 1_000_000.0;
        System.out.printf("[Local] 다양한 조건 - Cache Miss 평균: %.2fms, Cache Hit 평균: %.2fms (%.1f%% 감소)%n",
                avgMissMs, avgHitMs, (1 - avgHitMs / avgMissMs) * 100);
    }

    // ==================== 통계 유틸 ====================

    private void printStats(String label, List<Long> durationsNano) {
        List<Long> sorted = durationsNano.stream().sorted().toList();
        int size = sorted.size();

        double avgMs = sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        double minMs = sorted.getFirst() / 1_000_000.0;
        double maxMs = sorted.getLast() / 1_000_000.0;
        double p50Ms = sorted.get((int) (size * 0.50)) / 1_000_000.0;
        double p95Ms = sorted.get((int) (size * 0.95)) / 1_000_000.0;
        double p99Ms = sorted.get(Math.min((int) (size * 0.99), size - 1)) / 1_000_000.0;

        System.out.printf("""
                %n========== %s (%d회) ==========
                  평균: %.2fms
                  최소: %.2fms | 최대: %.2fms
                  p50:  %.2fms | p95: %.2fms | p99: %.2fms
                ===========================================%n""",
                label, size, avgMs, minMs, maxMs, p50Ms, p95Ms, p99Ms);
    }
}
