package com.loopers.domain.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductService 단위 테스트")
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    private Brand createBrandWithId(Long id) {
        Brand brand = new Brand("나이키", "스포츠 브랜드");
        brand.changeStatus(BrandStatus.ACTIVE);
        ReflectionTestUtils.setField(brand, "id", id);
        return brand;
    }

    private ProductOption createProductOptionWithId(Long id, Long productId, String optionName, int stockQuantity) {
        ProductOption option = new ProductOption(productId, optionName, stockQuantity);
        ReflectionTestUtils.setField(option, "id", id);
        return option;
    }

    private Product createProduct() {
        Brand brand = createBrandWithId(1L);
        Product product = new Product(brand, "에어맥스", 150000, 120000, 10000,
                3000, "나이키 에어맥스", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y",
                List.of(createProductOptionWithId(1L, 1L, "M 사이즈", 100)));
        ReflectionTestUtils.setField(product, "id", 1L);
        return product;
    }

    @Nested
    @DisplayName("상품을 등록할 때,")
    class Register {

        @Test
        @DisplayName("유효한 정보로 등록하면 상품이 생성된다.")
        void createsProduct_whenValidInfo() {
            // given
            Brand brand = createBrandWithId(1L);
            List<ProductOption> options = List.of(new ProductOption(null, "M 사이즈", 100));

            when(productRepository.existsByBrandIdAndName(1L, "에어맥스")).thenReturn(false);
            when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            Product product = productService.register(brand, "에어맥스", 150000, MarginType.AMOUNT, 30000,
                    10000, 3000, "설명", options);

            // then
            assertAll(
                    () -> assertThat(product.getName()).isEqualTo("에어맥스"),
                    () -> assertThat(product.getSupplyPrice()).isEqualTo(120000),
                    () -> assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE),
                    () -> verify(productRepository, times(1)).save(any(Product.class))
            );
        }

        @Test
        @DisplayName("같은 브랜드 내 동일 상품명이 존재하면 CONFLICT 예외가 발생한다.")
        void throwsConflict_whenDuplicateNameInBrand() {
            // given
            Brand brand = createBrandWithId(1L);
            List<ProductOption> options = List.of(new ProductOption(null, "M 사이즈", 100));

            when(productRepository.existsByBrandIdAndName(1L, "에어맥스")).thenReturn(true);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> productService.register(brand, "에어맥스", 150000, MarginType.AMOUNT, 30000,
                            10000, 3000, "설명", options));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                    () -> verify(productRepository, never()).save(any(Product.class))
            );
        }

        @Test
        @DisplayName("AMOUNT 마진 타입으로 등록하면 공급가가 올바르게 계산된다.")
        void calculatesSupplyPrice_withAmountMarginType() {
            // given
            Brand brand = createBrandWithId(1L);
            List<ProductOption> options = List.of(new ProductOption(null, "M 사이즈", 100));

            when(productRepository.existsByBrandIdAndName(1L, "에어맥스")).thenReturn(false);
            when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            Product product = productService.register(brand, "에어맥스", 100000, MarginType.AMOUNT, 20000,
                    10000, 3000, "설명", options);

            // then
            assertThat(product.getSupplyPrice()).isEqualTo(80000);
        }

        @Test
        @DisplayName("RATE 마진 타입으로 등록하면 공급가가 비율로 계산된다.")
        void calculatesSupplyPrice_withRateMarginType() {
            // given
            Brand brand = createBrandWithId(1L);
            List<ProductOption> options = List.of(new ProductOption(null, "M 사이즈", 100));

            when(productRepository.existsByBrandIdAndName(1L, "에어맥스")).thenReturn(false);
            when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            Product product = productService.register(brand, "에어맥스", 100000, MarginType.RATE, 20,
                    10000, 3000, "설명", options);

            // then
            assertThat(product.getSupplyPrice()).isEqualTo(80000);
        }
    }

    @Nested
    @DisplayName("상품을 조회할 때,")
    class FindById {

        @Test
        @DisplayName("존재하는 상품 ID로 조회하면 상품을 반환한다.")
        void returnsProduct_whenExists() {
            // given
            Product product = createProduct();
            when(productRepository.findById(1L)).thenReturn(Optional.of(product));

            // when
            Product result = productService.findById(1L);

            // then
            assertThat(result.getName()).isEqualTo("에어맥스");
        }

        @Test
        @DisplayName("존재하지 않는 상품 ID로 조회하면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenNotExists() {
            // given
            when(productRepository.findById(999L)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> productService.findById(999L));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("상품을 검색할 때,")
    class Search {

        @Test
        @DisplayName("검색 조건으로 상품 목록을 페이지로 반환한다.")
        void returnsPagedProducts_whenSearched() {
            // given
            Pageable pageable = PageRequest.of(0, 20);
            ProductSearchCondition condition = ProductSearchCondition.of("에어맥스", ProductSortType.LATEST, null);
            Product product = createProduct();

            when(productRepository.search(condition, pageable))
                    .thenReturn(new PageImpl<>(List.of(product), pageable, 1));

            // when
            Page<Product> result = productService.search(condition, pageable);

            // then
            assertAll(
                    () -> assertThat(result.getContent()).hasSize(1),
                    () -> assertThat(result.getContent().get(0).getName()).isEqualTo("에어맥스")
            );
        }

        @Test
        @DisplayName("결과가 없으면 빈 페이지를 반환한다.")
        void returnsEmptyPage_whenNoResults() {
            // given
            Pageable pageable = PageRequest.of(0, 20);
            ProductSearchCondition condition = ProductSearchCondition.of("없는상품", ProductSortType.LATEST, null);

            when(productRepository.search(condition, pageable))
                    .thenReturn(new PageImpl<>(Collections.emptyList(), pageable, 0));

            // when
            Page<Product> result = productService.search(condition, pageable);

            // then
            assertThat(result.getContent()).isEmpty();
        }
    }

    @Nested
    @DisplayName("관리자 상품 검색을 할 때,")
    class AdminSearch {

        @Test
        @DisplayName("관리자 검색 조건으로 상품 목록을 페이지로 반환한다.")
        void returnsPagedProducts_whenAdminSearched() {
            // given
            Pageable pageable = PageRequest.of(0, 20);
            AdminProductSearchCondition condition = AdminProductSearchCondition.of(
                    AdminProductSearchType.PRODUCT_NAME, "에어맥스");
            Product product = createProduct();

            when(productRepository.adminSearch(condition, pageable))
                    .thenReturn(new PageImpl<>(List.of(product), pageable, 1));

            // when
            Page<Product> result = productService.adminSearch(condition, pageable);

            // then
            assertAll(
                    () -> assertThat(result.getContent()).hasSize(1),
                    () -> assertThat(result.getContent().get(0).getName()).isEqualTo("에어맥스")
            );
        }
    }

    @Nested
    @DisplayName("상품을 수정할 때,")
    class Update {

        @Test
        @DisplayName("유효한 정보로 수정하면 상품이 수정된다.")
        void updatesProduct_whenValidInfo() {
            // given
            Product product = createProduct();
            List<ProductOption> newOptions = List.of(
                    new ProductOption(1L, "L 사이즈", 200)
            );

            when(productRepository.findById(1L)).thenReturn(Optional.of(product));
            when(productRepository.existsByBrandIdAndNameAndIdNot(1L, "에어포스", 1L)).thenReturn(false);
            when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            Product result = productService.update(1L, "에어포스", 180000, 140000, 15000,
                    2500, "새로운 설명", ProductStatus.ON_SALE, "Y", newOptions);

            // then
            assertAll(
                    () -> assertThat(result.getName()).isEqualTo("에어포스"),
                    () -> assertThat(result.getPrice()).isEqualTo(180000),
                    () -> verify(productRepository, times(1)).save(any(Product.class))
            );
        }

        @Test
        @DisplayName("다른 상품과 이름이 중복되면 CONFLICT 예외가 발생한다.")
        void throwsConflict_whenDuplicateNameExcludingSelf() {
            // given
            Product product = createProduct();
            List<ProductOption> newOptions = List.of(
                    new ProductOption(1L, "L 사이즈", 200)
            );

            when(productRepository.findById(1L)).thenReturn(Optional.of(product));
            when(productRepository.existsByBrandIdAndNameAndIdNot(1L, "에어포스", 1L)).thenReturn(true);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> productService.update(1L, "에어포스", 180000, 140000, 15000,
                            2500, "새로운 설명", ProductStatus.ON_SALE, "Y", newOptions));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                    () -> verify(productRepository, never()).save(any(Product.class))
            );
        }

        @Test
        @DisplayName("수정 시 옵션이 교체된다.")
        void replacesOptions_whenUpdated() {
            // given
            Product product = createProduct();
            List<ProductOption> newOptions = List.of(
                    new ProductOption(1L, "S 사이즈", 50),
                    new ProductOption(1L, "L 사이즈", 80)
            );

            when(productRepository.findById(1L)).thenReturn(Optional.of(product));
            when(productRepository.existsByBrandIdAndNameAndIdNot(1L, "에어맥스", 1L)).thenReturn(false);
            when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            Product result = productService.update(1L, "에어맥스", 150000, 120000, 10000,
                    3000, "설명", ProductStatus.ON_SALE, "Y", newOptions);

            // then
            assertAll(
                    () -> assertThat(result.getOptions()).hasSize(2),
                    () -> assertThat(result.getOptions().get(0).getOptionName()).isEqualTo("S 사이즈"),
                    () -> assertThat(result.getOptions().get(1).getOptionName()).isEqualTo("L 사이즈")
            );
        }

        @Test
        @DisplayName("존재하지 않는 상품을 수정하면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenProductNotExists() {
            // given
            when(productRepository.findById(999L)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> productService.update(999L, "에어포스", 180000, 140000, 15000,
                            2500, "설명", ProductStatus.ON_SALE, "Y", List.of()));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND),
                    () -> verify(productRepository, never()).save(any(Product.class))
            );
        }
    }

    @Nested
    @DisplayName("상품을 삭제할 때,")
    class SoftDelete {

        @Test
        @DisplayName("존재하는 상품을 삭제하면 softDelete가 호출된다.")
        void callsSoftDelete_whenExists() {
            // given
            Product product = createProduct();
            when(productRepository.findById(1L)).thenReturn(Optional.of(product));

            // when
            productService.softDelete(1L);

            // then
            verify(productRepository, times(1)).softDelete(1L);
        }

        @Test
        @DisplayName("존재하지 않는 상품을 삭제하면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenNotExists() {
            // given
            when(productRepository.findById(999L)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> productService.softDelete(999L));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("브랜드 기준 상품을 삭제할 때,")
    class SoftDeleteByBrandId {

        @Test
        @DisplayName("브랜드 ID로 softDeleteByBrandId가 호출된다.")
        void callsSoftDeleteByBrandId() {
            // given & when
            productService.softDeleteByBrandId(1L);

            // then
            verify(productRepository, times(1)).softDeleteByBrandId(1L);
        }
    }

    @Nested
    @DisplayName("좋아요 수를 증가시킬 때,")
    class IncrementLikeCount {

        @Test
        @DisplayName("productRepository.incrementLikeCount가 호출된다.")
        void callsRepositoryIncrementLikeCount() {
            // given
            when(productRepository.incrementLikeCount(1L)).thenReturn(1);

            // when
            productService.incrementLikeCount(1L);

            // then
            verify(productRepository, times(1)).incrementLikeCount(1L);
        }
    }

    @Nested
    @DisplayName("좋아요 수를 감소시킬 때,")
    class DecrementLikeCount {

        @Test
        @DisplayName("likeCount가 1 이상이면 감소에 성공한다.")
        void decrementsSuccessfully_whenLikeCountAboveZero() {
            // given
            when(productRepository.decrementLikeCount(1L)).thenReturn(1);

            // when
            productService.decrementLikeCount(1L);

            // then
            verify(productRepository, times(1)).decrementLikeCount(1L);
        }

        @Test
        @DisplayName("likeCount가 이미 0이면 감소하지 않고 경고 로그를 남긴다.")
        void doesNotDecrement_whenLikeCountIsZero() {
            // given
            when(productRepository.decrementLikeCount(1L)).thenReturn(0);

            // when
            productService.decrementLikeCount(1L);

            // then
            verify(productRepository, times(1)).decrementLikeCount(1L);
        }
    }

    @Nested
    @DisplayName("재고를 차감할 때,")
    class DeductStock {

        @Test
        @DisplayName("재고가 충분하면 차감 후 옵션이 저장된다.")
        void deductsAndSavesOption_whenSufficient() {
            // given
            ProductOption option = createProductOptionWithId(1L, 1L, "M 사이즈", 10);
            when(productRepository.findOptionByIdWithLock(1L)).thenReturn(Optional.of(option));

            // when
            ProductOption result = productService.deductStock(1L, 3);

            // then
            assertAll(
                    () -> assertThat(result).isEqualTo(option),
                    () -> assertThat(result.getStockQuantity()).isEqualTo(7),
                    () -> verify(productRepository, times(1)).saveOption(any(ProductOption.class))
            );
        }

        @Test
        @DisplayName("재고가 부족하면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenInsufficient() {
            // given
            ProductOption option = createProductOptionWithId(1L, 1L, "M 사이즈", 2);
            when(productRepository.findOptionByIdWithLock(1L)).thenReturn(Optional.of(option));

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> productService.deductStock(1L, 5));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> verify(productRepository, never()).saveOption(any(ProductOption.class))
            );
        }

        @Test
        @DisplayName("존재하지 않는 옵션의 재고를 차감하면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenOptionNotExists() {
            // given
            when(productRepository.findOptionByIdWithLock(999L)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> productService.deductStock(999L, 3));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("재고를 복원할 때,")
    class RestoreStock {

        @Test
        @DisplayName("재고가 복원된 후 옵션이 저장된다.")
        void restoresAndSavesOption() {
            // given
            ProductOption option = createProductOptionWithId(1L, 1L, "M 사이즈", 5);
            when(productRepository.findOptionByIdWithLock(1L)).thenReturn(Optional.of(option));

            // when
            productService.restoreStock(1L, 3);

            // then
            assertAll(
                    () -> assertThat(option.getStockQuantity()).isEqualTo(8),
                    () -> verify(productRepository, times(1)).saveOption(any(ProductOption.class))
            );
        }

        @Test
        @DisplayName("존재하지 않는 옵션의 재고를 복원하면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenOptionNotExists() {
            // given
            when(productRepository.findOptionByIdWithLock(999L)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> productService.restoreStock(999L, 3));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
