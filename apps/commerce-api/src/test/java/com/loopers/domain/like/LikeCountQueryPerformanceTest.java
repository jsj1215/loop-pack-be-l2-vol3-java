package com.loopers.domain.like;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductSearchCondition;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSortType;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [성능 비교 테스트 - 비정규화 vs Materialized View]
 *
 * 테스트 대상: 좋아요순 정렬 조회 성능 (10만건 상품 데이터)
 * 테스트 유형: 성능 테스트
 * 테스트 범위: ProductService.search() vs ProductService.searchWithMaterializedView()
 *
 * 데이터: seed-data.sql (브랜드 80개, 상품 100,000건)
 *       + likes 10만건 + product_like_summary 갱신
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LikeCountQueryPerformanceTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private LikeService likeService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

    @BeforeAll
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // 상품 10만건 시드 데이터 로드
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("seed-data.sql"));

            // likes 10만건 생성 + product_like_summary 갱신
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("seed-likes-data.sql"));
        }
    }

    @AfterAll
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @Order(1)
    @DisplayName("[비정규화] 전체 목록 + 좋아요순 정렬 조회 성능 측정")
    void denormalized_allProducts_sortByLikeDesc() {
        // given
        ProductSearchCondition condition = ProductSearchCondition.of(null, ProductSortType.LIKE_DESC, null);
        Pageable pageable = PageRequest.of(0, 20);

        // warm-up
        productService.search(condition, pageable);

        // when
        int iterations = 10;
        long totalElapsed = 0;

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            Page<Product> result = productService.search(condition, pageable);
            long elapsed = System.nanoTime() - start;
            totalElapsed += elapsed;

            assertThat(result.getContent()).hasSize(20);
            assertThat(result.getContent())
                    .extracting(Product::getLikeCount)
                    .isSortedAccordingTo((a, b) -> Integer.compare(b, a));
        }

        // then
        double avgMs = (totalElapsed / (double) iterations) / 1_000_000.0;
        System.out.printf("[비정규화] 전체 + 좋아요순: 평균 %.2fms (%d회)%n", avgMs, iterations);
    }

    @Test
    @Order(2)
    @DisplayName("[MV] 전체 목록 + 좋아요순 정렬 조회 성능 측정")
    void materializedView_allProducts_sortByLikeDesc() {
        // given
        ProductSearchCondition condition = ProductSearchCondition.of(null, ProductSortType.LIKE_DESC, null);
        Pageable pageable = PageRequest.of(0, 20);

        // warm-up
        productService.searchWithMaterializedView(condition, pageable);

        // when
        int iterations = 10;
        long totalElapsed = 0;

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            Page<Product> result = productService.searchWithMaterializedView(condition, pageable);
            long elapsed = System.nanoTime() - start;
            totalElapsed += elapsed;

            assertThat(result.getContent()).hasSize(20);
        }

        // then
        double avgMs = (totalElapsed / (double) iterations) / 1_000_000.0;
        System.out.printf("[MV] 전체 + 좋아요순: 평균 %.2fms (%d회)%n", avgMs, iterations);
    }

    @Test
    @Order(3)
    @DisplayName("[비정규화] 브랜드 필터 + 좋아요순 정렬 조회 성능 측정")
    void denormalized_brandFilter_sortByLikeDesc() {
        // given
        Long brandId = 1L;
        ProductSearchCondition condition = ProductSearchCondition.of(null, ProductSortType.LIKE_DESC, brandId);
        Pageable pageable = PageRequest.of(0, 20);

        // warm-up
        productService.search(condition, pageable);

        // when
        int iterations = 10;
        long totalElapsed = 0;

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            Page<Product> result = productService.search(condition, pageable);
            long elapsed = System.nanoTime() - start;
            totalElapsed += elapsed;

            assertThat(result.getContent()).hasSizeLessThanOrEqualTo(20);
        }

        // then
        double avgMs = (totalElapsed / (double) iterations) / 1_000_000.0;
        System.out.printf("[비정규화] 브랜드 필터 + 좋아요순: 평균 %.2fms (%d회)%n", avgMs, iterations);
    }

    @Test
    @Order(4)
    @DisplayName("[MV] 브랜드 필터 + 좋아요순 정렬 조회 성능 측정")
    void materializedView_brandFilter_sortByLikeDesc() {
        // given
        Long brandId = 1L;
        ProductSearchCondition condition = ProductSearchCondition.of(null, ProductSortType.LIKE_DESC, brandId);
        Pageable pageable = PageRequest.of(0, 20);

        // warm-up
        productService.searchWithMaterializedView(condition, pageable);

        // when
        int iterations = 10;
        long totalElapsed = 0;

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            Page<Product> result = productService.searchWithMaterializedView(condition, pageable);
            long elapsed = System.nanoTime() - start;
            totalElapsed += elapsed;

            assertThat(result.getContent()).hasSizeLessThanOrEqualTo(20);
        }

        // then
        double avgMs = (totalElapsed / (double) iterations) / 1_000_000.0;
        System.out.printf("[MV] 브랜드 필터 + 좋아요순: 평균 %.2fms (%d회)%n", avgMs, iterations);
    }

    @Test
    @Order(5)
    @DisplayName("[비정규화] 최신순 정렬 조회 성능 측정 (좋아요 JOIN 없는 기준선)")
    void denormalized_allProducts_sortByLatest() {
        // given
        ProductSearchCondition condition = ProductSearchCondition.of(null, ProductSortType.LATEST, null);
        Pageable pageable = PageRequest.of(0, 20);

        // warm-up
        productService.search(condition, pageable);

        // when
        int iterations = 10;
        long totalElapsed = 0;

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            Page<Product> result = productService.search(condition, pageable);
            long elapsed = System.nanoTime() - start;
            totalElapsed += elapsed;

            assertThat(result.getContent()).hasSize(20);
        }

        // then
        double avgMs = (totalElapsed / (double) iterations) / 1_000_000.0;
        System.out.printf("[비정규화] 전체 + 최신순 (기준선): 평균 %.2fms (%d회)%n", avgMs, iterations);
    }

    @Test
    @Order(6)
    @DisplayName("[MV] 최신순 정렬 조회 성능 측정 (LEFT JOIN 오버헤드 확인)")
    void materializedView_allProducts_sortByLatest() {
        // given
        ProductSearchCondition condition = ProductSearchCondition.of(null, ProductSortType.LATEST, null);
        Pageable pageable = PageRequest.of(0, 20);

        // warm-up
        productService.searchWithMaterializedView(condition, pageable);

        // when
        int iterations = 10;
        long totalElapsed = 0;

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            Page<Product> result = productService.searchWithMaterializedView(condition, pageable);
            long elapsed = System.nanoTime() - start;
            totalElapsed += elapsed;

            assertThat(result.getContent()).hasSize(20);
        }

        // then
        double avgMs = (totalElapsed / (double) iterations) / 1_000_000.0;
        System.out.printf("[MV] 전체 + 최신순 (LEFT JOIN 오버헤드): 평균 %.2fms (%d회)%n", avgMs, iterations);
    }
}
