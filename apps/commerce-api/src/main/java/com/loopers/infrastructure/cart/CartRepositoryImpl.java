package com.loopers.infrastructure.cart;

import com.loopers.domain.cart.CartItem;
import com.loopers.domain.cart.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CartRepositoryImpl implements CartRepository {

    private final CartJpaRepository cartJpaRepository;

    @Override
    public CartItem save(CartItem cartItem) {
        return cartJpaRepository.save(cartItem);
    }

    @Override
    public Optional<CartItem> findByMemberIdAndProductOptionId(Long memberId, Long productOptionId) {
        return cartJpaRepository.findByMemberIdAndProductOptionIdAndDeletedAtIsNull(memberId, productOptionId);
    }

    @Override
    public void deleteByProductOptionIds(List<Long> productOptionIds) {
        cartJpaRepository.deleteByProductOptionIdIn(productOptionIds);
    }

    @Override
    public void deleteByMemberIdAndProductOptionIds(Long memberId, List<Long> productOptionIds) {
        cartJpaRepository.deleteByMemberIdAndProductOptionIdIn(memberId, productOptionIds);
    }
}
