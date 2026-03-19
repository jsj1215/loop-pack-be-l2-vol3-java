package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "order_item")
public class OrderItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Order order;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_option_id", nullable = false)
    private Long productOptionId;

    @Column(name = "brand_id", nullable = false)
    private Long brandId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "option_name", nullable = false)
    private String optionName;

    @Column(name = "brand_name", nullable = false)
    private String brandName;

    @Column(name = "price", nullable = false)
    private int price;

    @Column(name = "supply_price", nullable = false)
    private int supplyPrice;

    @Column(name = "shipping_fee", nullable = false)
    private int shippingFee;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected OrderItem() {}

    private OrderItem(Long productId, Long productOptionId, Long brandId, String productName, String optionName,
                      String brandName, int price, int supplyPrice, int shippingFee, int quantity) {
        this.productId = productId;
        this.productOptionId = productOptionId;
        this.brandId = brandId;
        this.productName = productName;
        this.optionName = optionName;
        this.brandName = brandName;
        this.price = price;
        this.supplyPrice = supplyPrice;
        this.shippingFee = shippingFee;
        this.quantity = quantity;
    }

    public static OrderItem createSnapshot(Product product, ProductOption option, int quantity) {
        return new OrderItem(
                product.getId(),
                option.getId(),
                product.getBrand().getId(),
                product.getName(),
                option.getOptionName(),
                product.getBrand().getName(),
                product.getPrice(),
                product.getSupplyPrice(),
                product.getShippingFee(),
                quantity);
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public int getSubtotal() {
        return price * quantity;
    }
}
