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
        if (!hasEnoughStock(quantity)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "재고가 부족합니다.");
        }
        this.stockQuantity -= quantity;
    }

    public void restoreStock(int quantity) {
        this.stockQuantity += quantity;
    }
}
