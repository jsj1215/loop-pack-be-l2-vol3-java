package com.loopers.infrastructure.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductStatus;
import com.loopers.infrastructure.product.ProductRedisCacheStore.CachedProductData;
import com.loopers.infrastructure.product.ProductRedisCacheStore.CachedSearchPage;
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
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * [Redis 캐시 연산 비용 격리 측정 테스트]
 *
 * 목적: Cache Miss 시 발생하는 추가 비용(JSON 직렬화 + Redis SET)이
 *       실제로 얼마나 걸리는지 순수 연산 단위로 분리 측정한다.
 *
 * 측정 항목:
 *   1) ObjectMapper.writeValueAsString - JSON 직렬화 비용
 *   2) RedisTemplate SET - Redis 네트워크 + 저장 비용
 *   3) RedisTemplate GET - Redis 네트워크 + 조회 비용
 *   4) ObjectMapper.readValue - JSON 역직렬화 비용
 *   5) 전체 Cache Miss 추가 비용 (직렬화 + SET) vs DB 조회 시간 대비 비율
 *
 * 데이터: 브랜드 5개, 상품 50개, 옵션 각 2개 (실제 운영과 유사한 크기)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisCacheOperationCostTest {

    private static final int ITERATIONS = 100;
    private static final String CACHE_KEY = "test:cost:product:detail:1";
    private static final String SEARCH_CACHE_KEY = "test:cost:product:search:1";

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private RedisCleanUp redisCleanUp;

    private Product sampleProduct;
    private List<Product> sampleProducts;

    @BeforeAll
    void setUp() {
        // given - restoreFromCache로 순수 도메인 객체 생성 (LazyLoading 이슈 회피)
        ZonedDateTime now = ZonedDateTime.now();

        sampleProducts = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Brand brand = Brand.restoreFromCache((long) (i % 5 + 1), "브랜드" + (i % 5));
            List<ProductOption> options = List.of(
                    ProductOption.restoreFromCache((long) (i * 2 + 1), (long) (i + 1), "옵션A-" + i, 100),
                    ProductOption.restoreFromCache((long) (i * 2 + 2), (long) (i + 1), "옵션B-" + i, 50)
            );
            Product product = Product.restoreFromCache(
                    (long) (i + 1), brand, "테스트상품" + i,
                    10000 + i * 100, 8000 + i * 80, 1000, 2500, 0,
                    "상품에 대한 상세 설명입니다. 이 상품은 고품질 소재를 사용하여 제작되었습니다." + i,
                    MarginType.AMOUNT, ProductStatus.ON_SALE, "Y", options, now
            );
            sampleProducts.add(product);
        }

        sampleProduct = sampleProducts.getFirst();
    }

    @AfterAll
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    // ==================== 1. 단건 상품 직렬화/역직렬화 비용 ====================

    @Test
    @Order(1)
    @DisplayName("[직렬화] 단건 상품 JSON 직렬화 비용 측정 (ObjectMapper.writeValueAsString)")
    void measure_serialization_singleProduct() throws Exception {
        // given
        CachedProductData data = CachedProductData.from(sampleProduct);

        // warm-up (JIT 컴파일 안정화)
        for (int i = 0; i < 50; i++) {
            objectMapper.writeValueAsString(data);
        }

        // when
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            String json = objectMapper.writeValueAsString(data);
            long elapsed = System.nanoTime() - start;
            durations.add(elapsed);
        }

        // then
        printStats("[직렬화] 단건 상품 JSON 직렬화", durations);
    }

    @Test
    @Order(2)
    @DisplayName("[역직렬화] 단건 상품 JSON 역직렬화 비용 측정 (ObjectMapper.readValue)")
    void measure_deserialization_singleProduct() throws Exception {
        // given
        String json = objectMapper.writeValueAsString(CachedProductData.from(sampleProduct));

        // warm-up
        for (int i = 0; i < 50; i++) {
            objectMapper.readValue(json, CachedProductData.class);
        }

        // when
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            CachedProductData data = objectMapper.readValue(json, CachedProductData.class);
            long elapsed = System.nanoTime() - start;
            durations.add(elapsed);
        }

        // then
        printStats("[역직렬화] 단건 상품 JSON 역직렬화", durations);
    }

    // ==================== 2. Redis SET/GET 비용 ====================

    @Test
    @Order(3)
    @DisplayName("[Redis SET] 단건 상품 Redis 저장 비용 측정")
    void measure_redisSet_singleProduct() throws Exception {
        // given - 미리 JSON 직렬화 완료 (순수 Redis SET 비용만 측정)
        String json = objectMapper.writeValueAsString(CachedProductData.from(sampleProduct));

        // warm-up
        for (int i = 0; i < 50; i++) {
            redisTemplate.opsForValue().set(CACHE_KEY, json, Duration.ofMinutes(10));
        }

        // when
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            redisTemplate.opsForValue().set(CACHE_KEY, json, Duration.ofMinutes(10));
            long elapsed = System.nanoTime() - start;
            durations.add(elapsed);
        }

        // then
        printStats("[Redis SET] 단건 상품 저장", durations);
    }

    @Test
    @Order(4)
    @DisplayName("[Redis GET] 단건 상품 Redis 조회 비용 측정")
    void measure_redisGet_singleProduct() throws Exception {
        // given - 캐시에 데이터가 있는 상태 (Order 3에서 저장됨)

        // warm-up
        for (int i = 0; i < 50; i++) {
            redisTemplate.opsForValue().get(CACHE_KEY);
        }

        // when
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            String json = redisTemplate.opsForValue().get(CACHE_KEY);
            long elapsed = System.nanoTime() - start;
            durations.add(elapsed);
        }

        // then
        printStats("[Redis GET] 단건 상품 조회", durations);
    }

    // ==================== 3. 목록(10건) 직렬화/역직렬화 비용 ====================

    @Test
    @Order(5)
    @DisplayName("[직렬화] 상품 목록(10건) JSON 직렬화 비용 측정")
    void measure_serialization_productList() throws Exception {
        // given - 10건 페이지 (일반적인 목록 조회 크기)
        List<CachedProductData> content = sampleProducts.subList(0, 10).stream()
                .map(CachedProductData::from)
                .toList();
        CachedSearchPage searchPage = new CachedSearchPage(content, 50);

        // warm-up
        for (int i = 0; i < 50; i++) {
            objectMapper.writeValueAsString(searchPage);
        }

        // when
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            String json = objectMapper.writeValueAsString(searchPage);
            long elapsed = System.nanoTime() - start;
            durations.add(elapsed);
        }

        // then
        printStats("[직렬화] 상품 목록(10건) JSON 직렬화", durations);
    }

    @Test
    @Order(6)
    @DisplayName("[역직렬화] 상품 목록(10건) JSON 역직렬화 비용 측정")
    void measure_deserialization_productList() throws Exception {
        // given
        List<CachedProductData> content = sampleProducts.subList(0, 10).stream()
                .map(CachedProductData::from)
                .toList();
        String json = objectMapper.writeValueAsString(new CachedSearchPage(content, 50));

        // warm-up
        for (int i = 0; i < 50; i++) {
            objectMapper.readValue(json, CachedSearchPage.class);
        }

        // when
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            CachedSearchPage data = objectMapper.readValue(json, CachedSearchPage.class);
            long elapsed = System.nanoTime() - start;
            durations.add(elapsed);
        }

        // then
        printStats("[역직렬화] 상품 목록(10건) JSON 역직렬화", durations);
    }

    @Test
    @Order(7)
    @DisplayName("[Redis SET] 상품 목록(10건) Redis 저장 비용 측정")
    void measure_redisSet_productList() throws Exception {
        // given
        List<CachedProductData> content = sampleProducts.subList(0, 10).stream()
                .map(CachedProductData::from)
                .toList();
        String json = objectMapper.writeValueAsString(new CachedSearchPage(content, 50));

        // warm-up
        for (int i = 0; i < 50; i++) {
            redisTemplate.opsForValue().set(SEARCH_CACHE_KEY, json, Duration.ofMinutes(3));
        }

        // when
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            redisTemplate.opsForValue().set(SEARCH_CACHE_KEY, json, Duration.ofMinutes(3));
            long elapsed = System.nanoTime() - start;
            durations.add(elapsed);
        }

        // then
        printStats("[Redis SET] 상품 목록(10건) 저장", durations);
    }

    @Test
    @Order(8)
    @DisplayName("[Redis GET] 상품 목록(10건) Redis 조회 비용 측정")
    void measure_redisGet_productList() throws Exception {
        // given - Order 7에서 저장됨

        // warm-up
        for (int i = 0; i < 50; i++) {
            redisTemplate.opsForValue().get(SEARCH_CACHE_KEY);
        }

        // when
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            String json = redisTemplate.opsForValue().get(SEARCH_CACHE_KEY);
            long elapsed = System.nanoTime() - start;
            durations.add(elapsed);
        }

        // then
        printStats("[Redis GET] 상품 목록(10건) 조회", durations);
    }

    // ==================== 4. Cache Miss 전체 비용 (직렬화 + SET) 합산 ====================

    @Test
    @Order(9)
    @DisplayName("[Cache Miss 추가 비용] 단건: 직렬화 + Redis SET 합산 비용 측정")
    void measure_totalCacheMissCost_singleProduct() throws Exception {
        // given
        CachedProductData data = CachedProductData.from(sampleProduct);

        // warm-up
        for (int i = 0; i < 50; i++) {
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(CACHE_KEY, json, Duration.ofMinutes(10));
        }

        // when - 직렬화 + Redis SET을 하나의 단위로 측정
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(CACHE_KEY, json, Duration.ofMinutes(10));
            long elapsed = System.nanoTime() - start;
            durations.add(elapsed);
        }

        // then
        printStats("[Cache Miss 추가 비용] 단건 (직렬화 + SET)", durations);
    }

    @Test
    @Order(10)
    @DisplayName("[Cache Hit 전체 비용] 단건: Redis GET + 역직렬화 합산 비용 측정")
    void measure_totalCacheHitCost_singleProduct() throws Exception {
        // given - 캐시에 데이터가 있는 상태

        // warm-up
        for (int i = 0; i < 50; i++) {
            String json = redisTemplate.opsForValue().get(CACHE_KEY);
            objectMapper.readValue(json, CachedProductData.class);
        }

        // when - Redis GET + 역직렬화를 하나의 단위로 측정
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            String json = redisTemplate.opsForValue().get(CACHE_KEY);
            CachedProductData data = objectMapper.readValue(json, CachedProductData.class);
            long elapsed = System.nanoTime() - start;
            durations.add(elapsed);
        }

        // then
        printStats("[Cache Hit 전체 비용] 단건 (GET + 역직렬화)", durations);
    }

    // ==================== 5. JSON 페이로드 크기 확인 ====================

    @Test
    @Order(11)
    @DisplayName("[페이로드 크기] 캐시에 저장되는 JSON 크기 확인")
    void measure_jsonPayloadSize() throws Exception {
        // given
        CachedProductData singleData = CachedProductData.from(sampleProduct);
        List<CachedProductData> listData = sampleProducts.subList(0, 10).stream()
                .map(CachedProductData::from)
                .toList();
        List<CachedProductData> fullPageData = sampleProducts.subList(0, 20).stream()
                .map(CachedProductData::from)
                .toList();

        // when
        String singleJson = objectMapper.writeValueAsString(singleData);
        String listJson = objectMapper.writeValueAsString(new CachedSearchPage(listData, 50));
        String fullPageJson = objectMapper.writeValueAsString(new CachedSearchPage(fullPageData, 50));

        // then
        System.out.printf("""

                ========== JSON 페이로드 크기 ==========
                  단건 상품 (옵션 2개):   %,d bytes (%.1f KB)
                  목록 10건 (옵션 각 2개): %,d bytes (%.1f KB)
                  목록 20건 (옵션 각 2개): %,d bytes (%.1f KB)
                =========================================%n""",
                singleJson.length(), singleJson.length() / 1024.0,
                listJson.length(), listJson.length() / 1024.0,
                fullPageJson.length(), fullPageJson.length() / 1024.0);
    }

    // ==================== 통계 유틸 ====================

    private void printStats(String label, List<Long> durationsNano) {
        List<Long> sorted = durationsNano.stream().sorted().toList();
        int size = sorted.size();

        double avgMs = sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        double avgUs = sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000.0;
        double minUs = sorted.getFirst() / 1_000.0;
        double maxUs = sorted.getLast() / 1_000.0;
        double p50Us = sorted.get((int) (size * 0.50)) / 1_000.0;
        double p95Us = sorted.get((int) (size * 0.95)) / 1_000.0;
        double p99Us = sorted.get(Math.min((int) (size * 0.99), size - 1)) / 1_000.0;

        System.out.printf("""

                ========== %s (%d회) ==========
                  평균: %.2fms (%.0fμs)
                  최소: %.0fμs | 최대: %.0fμs
                  p50:  %.0fμs | p95: %.0fμs | p99: %.0fμs
                ===========================================%n""",
                label, size, avgMs, avgUs, minUs, maxUs, p50Us, p95Us, p99Us);
    }
}
