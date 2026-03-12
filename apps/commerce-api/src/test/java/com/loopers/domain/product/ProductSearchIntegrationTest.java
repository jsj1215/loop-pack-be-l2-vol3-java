package com.loopers.domain.product;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * [통합 테스트 - 상품 검색 성능]
 *
 * 테스트 대상: ProductService.search (Domain Layer)
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: Service → Repository(QueryDSL) → Database
 * 데이터: seed-data.sql (브랜드 80개, 상품 10만건, 옵션 ~20만건)
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductSearchIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private DataSource dataSource;

    @BeforeAll
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("seed-data.sql"));
        }
    }

    @AfterAll
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("브랜드 필터 + 좋아요순 정렬 조회 시,")
    @Nested
    class BrandFilterWithLikeSort {

        @Test
        @DisplayName("특정 브랜드의 상품을 좋아요 내림차순으로 조회한다.")
        void searchByBrand_sortByLikeDesc() {
            // given
            Long brandId = 1L;
            ProductSearchCondition condition = ProductSearchCondition.of(null, ProductSortType.LIKE_DESC, brandId);
            Pageable pageable = PageRequest.of(0, 20);

            // when
            Page<Product> result = productService.search(condition, pageable);

            // then
            assertAll(
                    () -> assertThat(result.getContent()).isNotEmpty(),
                    () -> assertThat(result.getContent()).hasSizeLessThanOrEqualTo(20),
                    () -> assertThat(result.getContent())
                            .allMatch(p -> p.getBrandId().equals(brandId)),
                    () -> assertThat(result.getContent())
                            .extracting(Product::getLikeCount)
                            .isSortedAccordingTo((a, b) -> Integer.compare(b, a))
            );
        }
    }

    @DisplayName("브랜드 필터 + 최신순 정렬 조회 시,")
    @Nested
    class BrandFilterWithLatestSort {

        @Test
        @DisplayName("특정 브랜드의 상품을 최신순으로 조회한다.")
        void searchByBrand_sortByLatest() {
            // given
            Long brandId = 1L;
            ProductSearchCondition condition = ProductSearchCondition.of(null, ProductSortType.LATEST, brandId);
            Pageable pageable = PageRequest.of(0, 20);

            // when
            Page<Product> result = productService.search(condition, pageable);

            // then
            assertAll(
                    () -> assertThat(result.getContent()).isNotEmpty(),
                    () -> assertThat(result.getContent()).hasSizeLessThanOrEqualTo(20),
                    () -> assertThat(result.getContent())
                            .allMatch(p -> p.getBrandId().equals(brandId)),
                    () -> assertThat(result.getContent())
                            .extracting(Product::getCreatedAt)
                            .isSortedAccordingTo((a, b) -> b.compareTo(a))
            );
        }
    }

    @DisplayName("전체 목록 + 좋아요순 정렬 조회 시,")
    @Nested
    class AllProductsWithLikeSort {

        @Test
        @DisplayName("브랜드 필터 없이 좋아요 내림차순으로 조회한다.")
        void searchAll_sortByLikeDesc() {
            // given
            ProductSearchCondition condition = ProductSearchCondition.of(null, ProductSortType.LIKE_DESC, null);
            Pageable pageable = PageRequest.of(0, 20);

            // when
            Page<Product> result = productService.search(condition, pageable);

            // then
            assertAll(
                    () -> assertThat(result.getContent()).hasSize(20),
                    () -> assertThat(result.getTotalElements()).isGreaterThanOrEqualTo(50000L),
                    () -> assertThat(result.getContent())
                            .extracting(Product::getLikeCount)
                            .isSortedAccordingTo((a, b) -> Integer.compare(b, a))
            );
        }
    }

    @DisplayName("전체 목록 + 최신순 정렬 조회 시,")
    @Nested
    class AllProductsWithLatestSort {

        @Test
        @DisplayName("브랜드 필터 없이 최신순으로 조회한다.")
        void searchAll_sortByLatest() {
            // given
            ProductSearchCondition condition = ProductSearchCondition.of(null, ProductSortType.LATEST, null);
            Pageable pageable = PageRequest.of(0, 20);

            // when
            Page<Product> result = productService.search(condition, pageable);

            // then
            assertAll(
                    () -> assertThat(result.getContent()).hasSize(20),
                    () -> assertThat(result.getTotalElements()).isGreaterThanOrEqualTo(50000L),
                    () -> assertThat(result.getContent())
                            .extracting(Product::getCreatedAt)
                            .isSortedAccordingTo((a, b) -> b.compareTo(a))
            );
        }
    }

    @DisplayName("전체 목록 + 가격순 정렬 조회 시,")
    @Nested
    class AllProductsWithPriceSort {

        @Test
        @DisplayName("브랜드 필터 없이 가격 오름차순으로 조회한다.")
        void searchAll_sortByPriceAsc() {
            // given
            ProductSearchCondition condition = ProductSearchCondition.of(null, ProductSortType.PRICE_ASC, null);
            Pageable pageable = PageRequest.of(0, 20);

            // when
            Page<Product> result = productService.search(condition, pageable);

            // then
            assertAll(
                    () -> assertThat(result.getContent()).hasSize(20),
                    () -> assertThat(result.getTotalElements()).isGreaterThanOrEqualTo(50000L),
                    () -> assertThat(result.getContent())
                            .extracting(Product::getPrice)
                            .isSorted()
            );
        }
    }

    @DisplayName("브랜드 필터 + COUNT 조회 시,")
    @Nested
    class BrandFilterCount {

        @Test
        @DisplayName("특정 브랜드의 전체 상품 수를 조회한다.")
        void countByBrand() {
            // given
            Long brandId = 1L;
            ProductSearchCondition condition = ProductSearchCondition.of(null, ProductSortType.LATEST, brandId);
            Pageable pageable = PageRequest.of(0, 20);

            // when
            Page<Product> result = productService.search(condition, pageable);

            // then
            assertThat(result.getTotalElements()).isGreaterThan(0);
        }
    }
}
