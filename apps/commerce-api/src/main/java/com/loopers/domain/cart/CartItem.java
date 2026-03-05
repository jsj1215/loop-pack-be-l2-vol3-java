package com.loopers.domain.cart;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

@Getter
@Entity
@Table(name = "cart_item", uniqueConstraints = {
        @UniqueConstraint(name = "uk_cart_item_member_option", columnNames = {"member_id", "product_option_id"})
})
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
}
