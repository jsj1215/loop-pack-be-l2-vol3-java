package com.loopers.domain.cart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("CartItem 도메인 모델 단위 테스트")
class CartItemTest {

    @Nested
    @DisplayName("장바구니 아이템을 생성할 때,")
    class Create {

        @Test
        @DisplayName("memberId, productOptionId, quantity로 생성하면 필드가 올바르게 설정된다.")
        void setsFields_whenCreated() {
            // given
            Long memberId = 1L;
            Long productOptionId = 10L;
            int quantity = 3;

            // when
            CartItem cartItem = CartItem.create(memberId, productOptionId, quantity);

            // then
            assertAll(
                    () -> assertThat(cartItem.getId()).isEqualTo(0L),
                    () -> assertThat(cartItem.getMemberId()).isEqualTo(1L),
                    () -> assertThat(cartItem.getProductOptionId()).isEqualTo(10L),
                    () -> assertThat(cartItem.getQuantity()).isEqualTo(3),
                    () -> assertThat(cartItem.getCreatedAt()).isNull(),
                    () -> assertThat(cartItem.getUpdatedAt()).isNull());
        }
    }

}
