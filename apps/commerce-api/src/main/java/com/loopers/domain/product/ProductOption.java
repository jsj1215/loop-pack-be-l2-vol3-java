package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "product_option")
public class ProductOption extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "option_name", nullable = false)
    private String optionName;

    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    protected ProductOption() {}

    public ProductOption(Long productId, String optionName, int stockQuantity) {
        this.productId = productId;
        this.optionName = optionName;
        this.stockQuantity = stockQuantity;
    }

    public boolean hasEnoughStock(int quantity) {
        return this.stockQuantity >= quantity;
    }

    public void deductStock(int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "차감 수량은 1개 이상이어야 합니다.");
        }
        if (!hasEnoughStock(quantity)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "재고가 부족합니다.");
        }
        this.stockQuantity -= quantity;
    }

    public void restoreStock(int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "복원 수량은 1개 이상이어야 합니다.");
        }
        this.stockQuantity += quantity;
    }

    /**
     * 캐시 복원용 팩토리 메서드.
     */
    public static ProductOption restoreFromCache(Long id, Long productId, String optionName, int stockQuantity) {
        ProductOption option = new ProductOption(productId, optionName, stockQuantity);
        option.restoreBase(id, null);
        return option;
    }
}
