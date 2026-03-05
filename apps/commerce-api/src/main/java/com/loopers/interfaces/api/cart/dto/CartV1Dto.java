package com.loopers.interfaces.api.cart.dto;

public class CartV1Dto {

    public record AddToCartRequest(
            Long productOptionId,
            int quantity) {
    }
}
