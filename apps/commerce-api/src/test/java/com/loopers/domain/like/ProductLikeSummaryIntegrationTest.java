package com.loopers.domain.like;

import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductSearchCondition;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSortType;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * [통합 테스트 - Materialized View 기반 좋아요 수 조회]
 *
 * 테스트 대상: ProductLikeSummary (Materialized View)
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: LikeService.refreshLikeSummary() → ProductService.searchWithMaterializedView()
 */
@SpringBootTest
@Transactional
class ProductLikeSummaryIntegrationTest {

    @Autowired
    private LikeService likeService;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private BrandService brandService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Brand createBrand(String name) {
        Brand brand = brandService.register(name, name + " 설명");
        return brandService.update(brand.getId(), name, name + " 설명", BrandStatus.ACTIVE);
    }

    private Product createProduct(Brand brand, String name, int price) {
        ProductOption option = new ProductOption(null, "기본 옵션", 100);
        return productService.register(brand, name, price, MarginType.RATE, 10,
                0, 3000, name + " 설명", List.of(option));
    }

    @DisplayName("MV 갱신(refreshLikeSummary) 시,")
    @Nested
    class RefreshLikeSummary {

        @Test
        @DisplayName("Like 테이블의 집계 결과가 product_like_summary에 정확히 반영된다.")
        void refreshesSummaryFromLikesTable() {
            // given
            Brand brand = createBrand("나이키");
            Product product1 = createProduct(brand, "에어맥스", 100000);
            Product product2 = createProduct(brand, "에어포스", 120000);

            // product1: 3명 좋아요
            likeService.like(1L, product1.getId());
            likeService.like(2L, product1.getId());
            likeService.like(3L, product1.getId());

            // product2: 1명 좋아요
            likeService.like(1L, product2.getId());

            entityManager.flush();
            entityManager.clear();

            // when
            likeService.refreshLikeSummary();
            entityManager.flush();
            entityManager.clear();

            // then - MV 기반 검색으로 좋아요순 정렬 검증
            ProductSearchCondition condition = ProductSearchCondition.of(null, ProductSortType.LIKE_DESC, brand.getId());
            Pageable pageable = PageRequest.of(0, 20);

            Page<Product> result = productService.searchWithMaterializedView(condition, pageable);

            assertAll(
                    () -> assertThat(result.getContent()).hasSize(2),
                    () -> assertThat(result.getContent().get(0).getId()).isEqualTo(product1.getId()),
                    () -> assertThat(result.getContent().get(1).getId()).isEqualTo(product2.getId())
            );
        }

        @Test
        @DisplayName("좋아요 취소 후 갱신하면, 취소된 좋아요가 반영된다.")
        void reflectsUnlikesAfterRefresh() {
            // given
            Brand brand = createBrand("아디다스");
            Product product1 = createProduct(brand, "울트라부스트", 200000);
            Product product2 = createProduct(brand, "스탠스미스", 150000);

            // product1: 2명 좋아요
            likeService.like(1L, product1.getId());
            likeService.like(2L, product1.getId());

            // product2: 3명 좋아요 → 1명 취소 = 실질 2명
            likeService.like(1L, product2.getId());
            likeService.like(2L, product2.getId());
            likeService.like(3L, product2.getId());
            likeService.unlike(3L, product2.getId());

            entityManager.flush();
            entityManager.clear();

            // when
            likeService.refreshLikeSummary();
            entityManager.flush();
            entityManager.clear();

            // then - 둘 다 2개이므로 결과에 둘 다 포함
            ProductSearchCondition condition = ProductSearchCondition.of(null, ProductSortType.LIKE_DESC, brand.getId());
            Pageable pageable = PageRequest.of(0, 20);

            Page<Product> result = productService.searchWithMaterializedView(condition, pageable);

            assertThat(result.getContent()).hasSize(2);
        }

        @Test
        @DisplayName("좋아요가 없는 상품도 MV 기반 검색에서 조회된다.")
        void includesProductsWithNoLikes() {
            // given
            Brand brand = createBrand("뉴발란스");
            Product productWithLikes = createProduct(brand, "993", 250000);
            Product productWithoutLikes = createProduct(brand, "574", 100000);

            likeService.like(1L, productWithLikes.getId());

            entityManager.flush();
            entityManager.clear();

            likeService.refreshLikeSummary();
            entityManager.flush();
            entityManager.clear();

            // when
            ProductSearchCondition condition = ProductSearchCondition.of(null, ProductSortType.LIKE_DESC, brand.getId());
            Pageable pageable = PageRequest.of(0, 20);

            Page<Product> result = productService.searchWithMaterializedView(condition, pageable);

            // then - LEFT JOIN이므로 좋아요 없는 상품도 포함
            assertAll(
                    () -> assertThat(result.getContent()).hasSize(2),
                    () -> assertThat(result.getContent().get(0).getId()).isEqualTo(productWithLikes.getId())
            );
        }
    }

    @DisplayName("비정규화 vs MV 결과 비교 시,")
    @Nested
    class DenormalizedVsMaterializedView {

        @Test
        @DisplayName("MV 갱신 직후, 비정규화와 MV 기반 검색의 정렬 결과가 동일하다.")
        void sameResultWhenMvIsFresh() {
            // given
            Brand brand = createBrand("푸마");
            Product product1 = createProduct(brand, "RS-X", 130000);
            Product product2 = createProduct(brand, "Suede", 90000);
            Product product3 = createProduct(brand, "Clyde", 110000);

            // product2: 5명, product3: 3명, product1: 1명
            // Facade를 통해 호출하여 Like 생성 + product.likeCount 증감을 함께 수행
            for (long i = 1; i <= 5; i++) {
                productFacade.like(i, product2.getId());
            }
            for (long i = 1; i <= 3; i++) {
                productFacade.like(i, product3.getId());
            }
            productFacade.like(1L, product1.getId());

            entityManager.flush();
            entityManager.clear();

            likeService.refreshLikeSummary();
            entityManager.flush();
            entityManager.clear();

            // when
            ProductSearchCondition condition = ProductSearchCondition.of(null, ProductSortType.LIKE_DESC, brand.getId());
            Pageable pageable = PageRequest.of(0, 20);

            Page<Product> denormalizedResult = productService.search(condition, pageable);
            Page<Product> mvResult = productService.searchWithMaterializedView(condition, pageable);

            // then - 정렬 순서가 동일
            assertAll(
                    () -> assertThat(denormalizedResult.getContent()).hasSize(3),
                    () -> assertThat(mvResult.getContent()).hasSize(3),
                    () -> assertThat(denormalizedResult.getContent())
                            .extracting(Product::getId)
                            .containsExactlyElementsOf(
                                    mvResult.getContent().stream().map(Product::getId).toList()
                            )
            );
        }
    }
}
