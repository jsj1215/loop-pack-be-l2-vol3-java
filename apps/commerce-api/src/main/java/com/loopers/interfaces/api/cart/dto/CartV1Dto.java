package com.loopers.interfaces.api.cart.dto;

import com.loopers.domain.cart.CartItem;

import java.time.ZonedDateTime;

public class CartV1Dto {

    public record AddToCartRequest(
            Long productOptionId,
            int quantity) {
    }

    public record CartItemResponse(
            Long id,
            Long memberId,
            Long productOptionId,
            int quantity,
            ZonedDateTime createdAt) {

        public static CartItemResponse from(CartItem cartItem) {
            return new CartItemResponse(
                    cartItem.getId(),
                    cartItem.getMemberId(),
                    cartItem.getProductOptionId(),
                    cartItem.getQuantity(),
                    cartItem.getCreatedAt());
        }
    }
}
