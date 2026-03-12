package com.loopers.domain.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [통합 테스트 - Service Integration]
 *
 * 테스트 대상: ProductService (Domain Layer)
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: Service → Repository → Database
 */
@SpringBootTest
@Transactional
class ProductServiceIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private BrandService brandService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Brand createActiveBrand(String name) {
        Brand brand = brandService.register(name, name + " 설명");
        return brandService.update(brand.getId(), name, name + " 설명", BrandStatus.ACTIVE);
    }

    private Product registerProduct(Brand brand, String productName, int price,
                                    MarginType marginType, int marginValue, int stockQuantity) {
        ProductOption option = new ProductOption(null, productName + " 옵션", stockQuantity);
        return productService.register(brand, productName, price, marginType, marginValue,
                0, 3000, productName + " 설명", List.of(option));
    }

    @DisplayName("상품을 등록할 때,")
    @Nested
    class Register {

        @Test
        @DisplayName("브랜드에 상품을 등록하면, 공급가가 계산되어 생성된다.")
        void createsProduct_withCalculatedSupplyPrice() {
            // given
            Brand brand = createActiveBrand("나이키");
            ProductOption option = new ProductOption(null, "사이즈 270", 100);

            // when
            Product product = productService.register(brand, "에어맥스", 100000, MarginType.RATE, 10,
                    0, 3000, "에어맥스 설명", List.of(option));

            // then
            assertAll(
                    () -> assertThat(product.getId()).isNotNull(),
                    () -> assertThat(product.getName()).isEqualTo("에어맥스"),
                    () -> assertThat(product.getPrice()).isEqualTo(100000),
                    () -> assertThat(product.getSupplyPrice()).isEqualTo(90000), // 100000 - (100000 * 10 / 100)
                    () -> assertThat(product.getBrand().getId()).isEqualTo(brand.getId()),
                    () -> assertThat(product.getOptions()).hasSize(1),
                    () -> assertThat(product.getOptions().get(0).getOptionName()).isEqualTo("사이즈 270"),
                    () -> assertThat(product.getOptions().get(0).getStockQuantity()).isEqualTo(100)
            );
        }

        @Test
        @DisplayName("같은 브랜드 내 중복 상품명이면, CONFLICT 예외가 발생한다.")
        void throwsException_whenDuplicateNameInSameBrand() {
            // given
            Brand brand = createActiveBrand("나이키");
            ProductOption option = new ProductOption(null, "사이즈 270", 100);
            productService.register(brand, "에어맥스", 100000, MarginType.RATE, 10,
                    0, 3000, "에어맥스 설명", List.of(option));

            ProductOption option2 = new ProductOption(null, "사이즈 280", 50);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> productService.register(brand, "에어맥스", 120000, MarginType.RATE, 10,
                            0, 3000, "다른 설명", List.of(option2)));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                    () -> assertThat(exception.getMessage()).contains("같은 브랜드 내 동일한 상품명")
            );
        }
    }

    @DisplayName("상품을 ID로 조회할 때,")
    @Nested
    class FindById {

        @Test
        @DisplayName("존재하는 상품을 조회하면, 성공한다.")
        void returnsProduct_whenExists() {
            // given
            Brand brand = createActiveBrand("나이키");
            Product savedProduct = registerProduct(brand, "에어맥스", 100000, MarginType.RATE, 10, 100);

            // when
            Product product = productService.findById(savedProduct.getId());

            // then
            assertAll(
                    () -> assertThat(product.getId()).isEqualTo(savedProduct.getId()),
                    () -> assertThat(product.getName()).isEqualTo("에어맥스")
            );
        }

        @Test
        @DisplayName("존재하지 않는 상품을 조회하면, NOT_FOUND 예외가 발생한다.")
        void throwsException_whenNotExists() {
            // given
            Long nonExistentId = 999L;

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> productService.findById(nonExistentId));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("상품을 소프트 삭제할 때,")
    @Nested
    class SoftDelete {

        @Test
        @DisplayName("존재하는 상품을 삭제하면, 조회되지 않는다.")
        void softDeletesProduct_whenExists() {
            // given
            Brand brand = createActiveBrand("나이키");
            Product savedProduct = registerProduct(brand, "에어맥스", 100000, MarginType.RATE, 10, 100);

            // when
            productService.softDelete(savedProduct.getId());

            // then
            CoreException exception = assertThrows(CoreException.class,
                    () -> productService.findById(savedProduct.getId()));
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("좋아요 수를 증감할 때,")
    @Nested
    class LikeCount {

        @Test
        @DisplayName("incrementLikeCount로 좋아요 수가 1 증가한다.")
        void incrementsLikeCount() {
            // given
            Brand brand = createActiveBrand("나이키");
            Product savedProduct = registerProduct(brand, "에어맥스", 100000, MarginType.RATE, 10, 100);

            // when
            productService.incrementLikeCount(savedProduct.getId());
            entityManager.flush();
            entityManager.clear();

            // then
            Product product = productService.findById(savedProduct.getId());
            assertThat(product.getLikeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("decrementLikeCount로 좋아요 수가 1 감소한다.")
        void decrementsLikeCount() {
            // given
            Brand brand = createActiveBrand("나이키");
            Product savedProduct = registerProduct(brand, "에어맥스", 100000, MarginType.RATE, 10, 100);
            productService.incrementLikeCount(savedProduct.getId());
            entityManager.flush();
            entityManager.clear();

            // when
            productService.decrementLikeCount(savedProduct.getId());
            entityManager.flush();
            entityManager.clear();

            // then
            Product product = productService.findById(savedProduct.getId());
            assertThat(product.getLikeCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("likeCount가 0일 때 decrementLikeCount를 호출하면 음수가 되지 않는다.")
        void doesNotGoNegative_whenLikeCountIsZero() {
            // given
            Brand brand = createActiveBrand("나이키");
            Product savedProduct = registerProduct(brand, "에어맥스", 100000, MarginType.RATE, 10, 100);

            // when
            productService.decrementLikeCount(savedProduct.getId());
            entityManager.flush();
            entityManager.clear();

            // then
            Product product = productService.findById(savedProduct.getId());
            assertThat(product.getLikeCount()).isEqualTo(0);
        }
    }

    @DisplayName("재고를 차감할 때,")
    @Nested
    class DeductStock {

        @Test
        @DisplayName("충분한 재고가 있으면, 재고 차감에 성공한다.")
        void deductsStock_whenEnoughStock() {
            // given
            Brand brand = createActiveBrand("나이키");
            Product savedProduct = registerProduct(brand, "에어맥스", 100000, MarginType.RATE, 10, 100);
            Long optionId = savedProduct.getOptions().get(0).getId();

            // when
            productService.deductStock(optionId, 30);

            // then
            ProductOption option = productService.findOptionById(optionId);
            assertThat(option.getStockQuantity()).isEqualTo(70);
        }

        @Test
        @DisplayName("재고가 부족하면, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNotEnoughStock() {
            // given
            Brand brand = createActiveBrand("나이키");
            Product savedProduct = registerProduct(brand, "에어맥스", 100000, MarginType.RATE, 10, 10);
            Long optionId = savedProduct.getOptions().get(0).getId();

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> productService.deductStock(optionId, 20));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("재고가 부족")
            );
        }
    }
}
