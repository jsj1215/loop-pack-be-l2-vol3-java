package com.loopers.domain.cart;

import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
@DisplayName("CartService 단위 테스트")
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private CartService cartService;

    private CartItem createCartItemWithId(Long id, Long memberId, Long productOptionId, int quantity) {
        CartItem cartItem = CartItem.create(memberId, productOptionId, quantity);
        ReflectionTestUtils.setField(cartItem, "id", id);
        return cartItem;
    }

    private ProductOption createProductOptionWithId(Long id, Long productId, String optionName, int stockQuantity) {
        ProductOption option = new ProductOption(productId, optionName, stockQuantity);
        ReflectionTestUtils.setField(option, "id", id);
        return option;
    }

    @Nested
    @DisplayName("장바구니에 아이템을 추가할 때,")
    class AddToCart {

        @Test
        @DisplayName("새로운 아이템을 추가하면 장바구니에 저장된다.")
        void savesNewItem_whenNotExists() {
            // given
            Long memberId = 1L;
            Long productOptionId = 10L;
            int quantity = 2;

            ProductOption option = createProductOptionWithId(productOptionId, 1L, "옵션A", 10);
            when(productRepository.findOptionById(productOptionId)).thenReturn(Optional.of(option));
            when(cartRepository.findByMemberIdAndProductOptionId(memberId, productOptionId)).thenReturn(Optional.empty());
            when(cartRepository.save(any(CartItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            CartItem result = cartService.addToCart(memberId, productOptionId, quantity);

            // then
            assertAll(
                    () -> assertThat(result.getMemberId()).isEqualTo(memberId),
                    () -> assertThat(result.getProductOptionId()).isEqualTo(productOptionId),
                    () -> assertThat(result.getQuantity()).isEqualTo(quantity),
                    () -> verify(cartRepository, times(1)).save(any(CartItem.class)));
        }

        @Test
        @DisplayName("재고가 부족하면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenInsufficientStock() {
            // given
            Long memberId = 1L;
            Long productOptionId = 10L;
            int quantity = 15;

            ProductOption option = createProductOptionWithId(productOptionId, 1L, "옵션A", 10);
            when(productRepository.findOptionById(productOptionId)).thenReturn(Optional.of(option));

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> cartService.addToCart(memberId, productOptionId, quantity));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> verify(cartRepository, never()).save(any(CartItem.class)));
        }

        @Test
        @DisplayName("기존 아이템이 있으면 수량이 병합된다.")
        void mergesQuantity_whenItemExists() {
            // given
            Long memberId = 1L;
            Long productOptionId = 10L;
            int existingQuantity = 3;
            int additionalQuantity = 2;

            ProductOption option = createProductOptionWithId(productOptionId, 1L, "옵션A", 10);
            CartItem existingItem = createCartItemWithId(1L, memberId, productOptionId, existingQuantity);

            when(productRepository.findOptionById(productOptionId)).thenReturn(Optional.of(option));
            when(cartRepository.findByMemberIdAndProductOptionId(memberId, productOptionId)).thenReturn(Optional.of(existingItem));
            when(cartRepository.save(any(CartItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            CartItem result = cartService.addToCart(memberId, productOptionId, additionalQuantity);

            // then
            assertAll(
                    () -> assertThat(result.getQuantity()).isEqualTo(existingQuantity + additionalQuantity),
                    () -> verify(cartRepository, times(1)).save(any(CartItem.class)));
        }

        @Test
        @DisplayName("기존 수량과 합산하면 재고를 초과할 경우 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenMergedQuantityExceedsStock() {
            // given
            Long memberId = 1L;
            Long productOptionId = 10L;
            int existingQuantity = 8;
            int additionalQuantity = 5;

            ProductOption option = createProductOptionWithId(productOptionId, 1L, "옵션A", 10);
            CartItem existingItem = createCartItemWithId(1L, memberId, productOptionId, existingQuantity);

            when(productRepository.findOptionById(productOptionId)).thenReturn(Optional.of(option));
            when(cartRepository.findByMemberIdAndProductOptionId(memberId, productOptionId)).thenReturn(Optional.of(existingItem));

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> cartService.addToCart(memberId, productOptionId, additionalQuantity));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> verify(cartRepository, never()).save(any(CartItem.class)));
        }

        @Test
        @DisplayName("존재하지 않는 상품 옵션이면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenOptionNotExists() {
            // given
            Long memberId = 1L;
            Long productOptionId = 999L;
            int quantity = 1;

            when(productRepository.findOptionById(productOptionId)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> cartService.addToCart(memberId, productOptionId, quantity));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND),
                    () -> verify(cartRepository, never()).save(any(CartItem.class)));
        }
    }

    @Nested
    @DisplayName("상품 옵션 ID 목록으로 장바구니를 삭제할 때,")
    class DeleteByProductOptionIds {

        @Test
        @DisplayName("비어있지 않은 목록이면 삭제를 수행한다.")
        void deletesItems_whenListNotEmpty() {
            // given
            List<Long> optionIds = List.of(1L, 2L, 3L);

            // when
            cartService.deleteByProductOptionIds(optionIds);

            // then
            verify(cartRepository, times(1)).deleteByProductOptionIds(optionIds);
        }

        @Test
        @DisplayName("빈 목록이면 삭제를 수행하지 않는다.")
        void doesNotDelete_whenListEmpty() {
            // given
            List<Long> optionIds = List.of();

            // when
            cartService.deleteByProductOptionIds(optionIds);

            // then
            verify(cartRepository, never()).deleteByProductOptionIds(any());
        }
    }

    @Nested
    @DisplayName("브랜드 ID로 장바구니를 삭제할 때,")
    class DeleteByBrandId {

        @Test
        @DisplayName("해당 브랜드의 상품 옵션 ID 목록을 조회하여 삭제한다.")
        void deletesItemsByBrandOptionIds() {
            // given
            Long brandId = 1L;
            List<Long> optionIds = List.of(10L, 20L);

            when(productRepository.findOptionIdsByBrandId(brandId)).thenReturn(optionIds);

            // when
            cartService.deleteByBrandId(brandId);

            // then
            assertAll(
                    () -> verify(productRepository, times(1)).findOptionIdsByBrandId(brandId),
                    () -> verify(cartRepository, times(1)).deleteByProductOptionIds(optionIds));
        }

        @Test
        @DisplayName("해당 브랜드의 상품 옵션이 없으면 삭제를 수행하지 않는다.")
        void doesNotDelete_whenNoOptionsForBrand() {
            // given
            Long brandId = 1L;

            when(productRepository.findOptionIdsByBrandId(brandId)).thenReturn(List.of());

            // when
            cartService.deleteByBrandId(brandId);

            // then
            assertAll(
                    () -> verify(productRepository, times(1)).findOptionIdsByBrandId(brandId),
                    () -> verify(cartRepository, never()).deleteByProductOptionIds(any()));
        }
    }
}
