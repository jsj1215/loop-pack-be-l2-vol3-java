package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ProductOption 도메인 모델 단위 테스트")
class ProductOptionTest {

    private ProductOption createProductOptionWithId(Long id, Long productId, String optionName, int stockQuantity) {
        ProductOption option = new ProductOption(productId, optionName, stockQuantity);
        ReflectionTestUtils.setField(option, "id", id);
        return option;
    }

    @Nested
    @DisplayName("재고 확인을 할 때,")
    class HasEnoughStock {

        @Test
        @DisplayName("재고가 요청 수량 이상이면 true를 반환한다.")
        void returnsTrue_whenStockIsSufficient() {
            // given
            ProductOption option = createProductOptionWithId(1L, 1L, "M 사이즈", 10);

            // when
            boolean result = option.hasEnoughStock(5);

            // then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("재고가 요청 수량과 같으면 true를 반환한다.")
        void returnsTrue_whenStockEqualsQuantity() {
            // given
            ProductOption option = createProductOptionWithId(1L, 1L, "M 사이즈", 10);

            // when
            boolean result = option.hasEnoughStock(10);

            // then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("재고가 요청 수량보다 적으면 false를 반환한다.")
        void returnsFalse_whenStockIsInsufficient() {
            // given
            ProductOption option = createProductOptionWithId(1L, 1L, "M 사이즈", 3);

            // when
            boolean result = option.hasEnoughStock(5);

            // then
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("재고를 차감할 때,")
    class DeductStock {

        @Test
        @DisplayName("재고가 충분하면 요청 수량만큼 차감된다.")
        void deductsStock_whenSufficient() {
            // given
            ProductOption option = createProductOptionWithId(1L, 1L, "M 사이즈", 10);

            // when
            option.deductStock(3);

            // then
            assertThat(option.getStockQuantity()).isEqualTo(7);
        }

        @Test
        @DisplayName("재고가 부족하면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenInsufficient() {
            // given
            ProductOption option = createProductOptionWithId(1L, 1L, "M 사이즈", 2);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> option.deductStock(5));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("재고가 부족합니다."),
                    () -> assertThat(option.getStockQuantity()).isEqualTo(2)
            );
        }

        @Test
        @DisplayName("차감 수량이 0이면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenDeductZero() {
            // given
            ProductOption option = createProductOptionWithId(1L, 1L, "M 사이즈", 10);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> option.deductStock(0));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(option.getStockQuantity()).isEqualTo(10));
        }

        @Test
        @DisplayName("차감 수량이 음수이면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenDeductNegative() {
            // given
            ProductOption option = createProductOptionWithId(1L, 1L, "M 사이즈", 10);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> option.deductStock(-1));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(option.getStockQuantity()).isEqualTo(10));
        }

        @Test
        @DisplayName("재고를 전부 차감하면 0이 된다.")
        void becomesZero_whenAllDeducted() {
            // given
            ProductOption option = createProductOptionWithId(1L, 1L, "M 사이즈", 5);

            // when
            option.deductStock(5);

            // then
            assertThat(option.getStockQuantity()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("재고를 복원할 때,")
    class RestoreStock {

        @Test
        @DisplayName("요청 수량만큼 재고가 증가한다.")
        void restoresStock() {
            // given
            ProductOption option = createProductOptionWithId(1L, 1L, "M 사이즈", 5);

            // when
            option.restoreStock(3);

            // then
            assertThat(option.getStockQuantity()).isEqualTo(8);
        }

        @Test
        @DisplayName("복원 수량이 0이면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenRestoreZero() {
            // given
            ProductOption option = createProductOptionWithId(1L, 1L, "M 사이즈", 5);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> option.restoreStock(0));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(option.getStockQuantity()).isEqualTo(5));
        }

        @Test
        @DisplayName("복원 수량이 음수이면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenRestoreNegative() {
            // given
            ProductOption option = createProductOptionWithId(1L, 1L, "M 사이즈", 5);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> option.restoreStock(-1));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(option.getStockQuantity()).isEqualTo(5));
        }

        @Test
        @DisplayName("재고가 0인 상태에서 복원하면 요청 수량이 재고가 된다.")
        void restoresFromZero() {
            // given
            ProductOption option = createProductOptionWithId(1L, 1L, "M 사이즈", 0);

            // when
            option.restoreStock(10);

            // then
            assertThat(option.getStockQuantity()).isEqualTo(10);
        }
    }
}
