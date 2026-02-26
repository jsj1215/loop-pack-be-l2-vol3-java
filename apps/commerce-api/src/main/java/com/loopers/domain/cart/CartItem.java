package com.loopers.domain.cart;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "cart_item")
public class CartItem extends BaseEntity {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "product_option_id", nullable = false)
    private Long productOptionId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected CartItem() {}

    private CartItem(Long memberId, Long productOptionId, int quantity) {
        this.memberId = memberId;
        this.productOptionId = productOptionId;
        this.quantity = quantity;
    }

    public static CartItem create(Long memberId, Long productOptionId, int quantity) {
        return new CartItem(memberId, productOptionId, quantity);
    }

    public void addQuantity(int quantity) {
        this.quantity += quantity;
    }
}
