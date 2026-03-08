package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.brand.Brand;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Entity
@Table(name = "product")
public class Product extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "price", nullable = false)
    private int price;

    @Column(name = "supply_price", nullable = false)
    private int supplyPrice;

    @Column(name = "discount_price", nullable = false)
    private int discountPrice;

    @Column(name = "shipping_fee", nullable = false)
    private int shippingFee;

    @Column(name = "like_count", nullable = false)
    private int likeCount;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "margin_type", nullable = false)
    private MarginType marginType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProductStatus status;

    @Column(name = "display_yn", nullable = false, length = 1)
    private String displayYn;

    @Transient
    private List<ProductOption> options = new ArrayList<>();

    protected Product() {}

    public Product(Brand brand, String name, int price, int supplyPrice, int discountPrice,
                   int shippingFee, String description, MarginType marginType,
                   ProductStatus status, String displayYn, List<ProductOption> options) {
        this.brand = brand;
        this.name = name;
        this.price = price;
        this.supplyPrice = supplyPrice;
        this.discountPrice = discountPrice;
        this.shippingFee = shippingFee;
        this.likeCount = 0;
        this.description = description;
        this.marginType = marginType;
        this.status = status;
        this.displayYn = displayYn;
        this.options = options != null ? options : new ArrayList<>();
    }

    public Long getBrandId() {
        return brand != null ? brand.getId() : null;
    }

    public Product withOptions(List<ProductOption> options) {
        this.options = options != null ? options : new ArrayList<>();
        return this;
    }

    public static int calculateSupplyPrice(int price, MarginType marginType, int marginValue) {
        return switch (marginType) {
            case AMOUNT -> price - marginValue;
            case RATE -> price - (price * marginValue / 100);
        };
    }

    public void updateInfo(String name, int price, int supplyPrice, int discountPrice,
                           int shippingFee, String description, ProductStatus status,
                           String displayYn, List<ProductOption> options) {
        this.name = name;
        this.price = price;
        this.supplyPrice = supplyPrice;
        this.discountPrice = discountPrice;
        this.shippingFee = shippingFee;
        this.description = description;
        this.status = status;
        this.displayYn = displayYn;
        this.options = options;
    }
}
