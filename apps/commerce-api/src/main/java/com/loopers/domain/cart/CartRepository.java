package com.loopers.domain.cart;

import java.util.List;
import java.util.Optional;

public interface CartRepository {

    CartItem save(CartItem cartItem);

    Optional<CartItem> findByMemberIdAndProductOptionId(Long memberId, Long productOptionId);

    void upsert(Long memberId, Long productOptionId, int quantity);

    void deleteByProductOptionIds(List<Long> productOptionIds);

    void deleteByMemberIdAndProductOptionIds(Long memberId, List<Long> productOptionIds);
}
