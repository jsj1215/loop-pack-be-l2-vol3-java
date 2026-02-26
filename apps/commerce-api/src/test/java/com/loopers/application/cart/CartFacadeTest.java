package com.loopers.application.cart;

import com.loopers.domain.cart.CartItem;
import com.loopers.domain.cart.CartService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CartFacade 단위 테스트")
class CartFacadeTest {

    @Mock
    private CartService cartService;

    @InjectMocks
    private CartFacade cartFacade;

    @Nested
    @DisplayName("장바구니에 아이템을 추가할 때,")
    class AddToCart {

        @Test
        @DisplayName("CartService를 호출하고 CartItem을 반환한다.")
        void callsServiceAndReturnsCartItem() {
            // given
            Long memberId = 1L;
            Long productOptionId = 10L;
            int quantity = 2;

            CartItem expectedCartItem = CartItem.create(memberId, productOptionId, quantity);
            ReflectionTestUtils.setField(expectedCartItem, "id", 1L);
            when(cartService.addToCart(memberId, productOptionId, quantity)).thenReturn(expectedCartItem);

            // when
            CartItem result = cartFacade.addToCart(memberId, productOptionId, quantity);

            // then
            assertAll(
                    () -> assertThat(result.getId()).isEqualTo(1L),
                    () -> assertThat(result.getMemberId()).isEqualTo(memberId),
                    () -> assertThat(result.getProductOptionId()).isEqualTo(productOptionId),
                    () -> assertThat(result.getQuantity()).isEqualTo(quantity),
                    () -> verify(cartService, times(1)).addToCart(memberId, productOptionId, quantity));
        }
    }
}
