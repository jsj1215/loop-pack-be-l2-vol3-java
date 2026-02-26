package com.loopers.domain.cart;

import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class CartService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;

    public CartItem addToCart(Long memberId, Long productOptionId, int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "수량은 1개 이상이어야 합니다.");
        }

        ProductOption option = productRepository.findOptionById(productOptionId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품 옵션을 찾을 수 없습니다."));

        if (!option.hasEnoughStock(quantity)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "재고가 부족합니다.");
        }

        Optional<CartItem> existing = cartRepository.findByMemberIdAndProductOptionId(memberId, productOptionId);

        if (existing.isPresent()) {
            CartItem cartItem = existing.get();
            int totalQuantity = cartItem.getQuantity() + quantity;
            if (!option.hasEnoughStock(totalQuantity)) {
                throw new CoreException(ErrorType.BAD_REQUEST, "재고가 부족합니다.");
            }
            cartItem.addQuantity(quantity);
            return cartRepository.save(cartItem);
        }

        CartItem cartItem = CartItem.create(memberId, productOptionId, quantity);
        return cartRepository.save(cartItem);
    }

    public void deleteByProductOptionIds(List<Long> productOptionIds) {
        if (!productOptionIds.isEmpty()) {
            cartRepository.deleteByProductOptionIds(productOptionIds);
        }
    }

    public void deleteByBrandId(Long brandId) {
        List<Long> optionIds = productRepository.findOptionIdsByBrandId(brandId);
        deleteByProductOptionIds(optionIds);
    }

    public void deleteByMemberIdAndProductOptionIds(Long memberId, List<Long> productOptionIds) {
        if (!productOptionIds.isEmpty()) {
            cartRepository.deleteByMemberIdAndProductOptionIds(memberId, productOptionIds);
        }
    }
}
