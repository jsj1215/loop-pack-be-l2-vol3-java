package com.loopers.domain.cart;

import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@RequiredArgsConstructor
@Component
public class CartService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;

    /**
     * 장바구니에 상품을 추가한다.
     * 재고 검증은 이번에 담으려는 수량만 체크한다 (장바구니 누적 수량과 무관).
     * INSERT ... ON DUPLICATE KEY UPDATE로 원자적 UPSERT를 수행한다.
     *
     * @implNote 트랜잭션 내에서 호출되어야 한다 (Facade에서 @Transactional 관리).
     */
    public void addToCart(Long memberId, Long productOptionId, int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "수량은 1개 이상이어야 합니다.");
        }

        productRepository.findOptionById(productOptionId)
                .filter(option -> option.hasEnoughStock(quantity))
                .orElseThrow(() -> new CoreException(ErrorType.BAD_REQUEST, "상품 옵션이 존재하지 않거나 재고가 부족합니다."));

        cartRepository.upsert(memberId, productOptionId, quantity);
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
