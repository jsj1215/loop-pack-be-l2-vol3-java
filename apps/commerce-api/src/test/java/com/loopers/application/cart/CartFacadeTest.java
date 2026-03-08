package com.loopers.application.cart;

import com.loopers.domain.cart.CartService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
        @DisplayName("CartService.addToCart를 호출한다.")
        void callsServiceAddToCart() {
            // given
            Long memberId = 1L;
            Long productOptionId = 10L;
            int quantity = 2;

            // when
            cartFacade.addToCart(memberId, productOptionId, quantity);

            // then
            verify(cartService, times(1)).addToCart(memberId, productOptionId, quantity);
        }
    }
}
