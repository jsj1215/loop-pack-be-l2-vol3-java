package com.loopers.domain.cart;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductService;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [통합 테스트 - Service Integration]
 *
 * 테스트 대상: CartService (Domain Layer)
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: Service → Repository → Database
 */
@SpringBootTest
@Transactional
class CartServiceIntegrationTest {

    @Autowired
    private CartService cartService;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private BrandService brandService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Product createProductWithStock(int stockQuantity) {
        Brand brand = brandService.register("나이키", "스포츠 브랜드");
        brand = brandService.update(brand.getId(), "나이키", "스포츠 브랜드", BrandStatus.ACTIVE);
        ProductOption option = new ProductOption(null, "사이즈 270", stockQuantity);
        return productService.register(brand, "에어맥스", 100000, MarginType.RATE, 10,
                0, 3000, "에어맥스 설명", List.of(option));
    }

    @DisplayName("장바구니에 아이템을 추가할 때,")
    @Nested
    class AddToCart {

        @Test
        @DisplayName("새 아이템을 추가하면, 성공한다.")
        void addsNewItem_whenValidInput() {
            // given
            Product product = createProductWithStock(100);
            Long optionId = product.getOptions().get(0).getId();
            Long memberId = 1L;

            // when
            assertDoesNotThrow(() -> cartService.addToCart(memberId, optionId, 2));

            // then
            Optional<CartItem> cartItem = cartRepository.findByMemberIdAndProductOptionId(memberId, optionId);
            assertAll(
                    () -> assertThat(cartItem).isPresent(),
                    () -> assertThat(cartItem.get().getQuantity()).isEqualTo(2)
            );
        }

        @Test
        @DisplayName("기존 아이템에 수량을 추가하면, 수량이 병합된다.")
        void mergesQuantity_whenItemAlreadyExists() {
            // given
            Product product = createProductWithStock(100);
            Long optionId = product.getOptions().get(0).getId();
            Long memberId = 1L;
            cartService.addToCart(memberId, optionId, 2);

            // when
            cartService.addToCart(memberId, optionId, 3);

            // then
            Optional<CartItem> cartItem = cartRepository.findByMemberIdAndProductOptionId(memberId, optionId);
            assertThat(cartItem.get().getQuantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("재고가 부족하면, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNotEnoughStock() {
            // given
            Product product = createProductWithStock(5);
            Long optionId = product.getOptions().get(0).getId();
            Long memberId = 1L;

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> cartService.addToCart(memberId, optionId, 10));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("존재하지 않는 옵션이면, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenOptionNotFound() {
            // given
            Long memberId = 1L;
            Long nonExistentOptionId = 999L;

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> cartService.addToCart(memberId, nonExistentOptionId, 1));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}
