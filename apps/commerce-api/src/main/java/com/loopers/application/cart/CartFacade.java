package com.loopers.application.cart;

import com.loopers.domain.cart.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class CartFacade {

    private final CartService cartService;

    @Transactional
    public void addToCart(Long memberId, Long productOptionId, int quantity) {
        cartService.addToCart(memberId, productOptionId, quantity);
    }
}
