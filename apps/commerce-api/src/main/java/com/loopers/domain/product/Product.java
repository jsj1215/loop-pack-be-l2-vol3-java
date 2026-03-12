package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.brand.Brand;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Entity
@Table(name = "product", indexes = {
        @Index(name = "idx_product_brand_id", columnList = "brand_id"),
        @Index(name = "idx_product_status_display_like", columnList = "status, display_yn, like_count"),
        @Index(name = "idx_product_status_display_created", columnList = "status, display_yn, created_at"),
        @Index(name = "idx_product_status_display_price", columnList = "status, display_yn, price")
})
public class Product extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
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

    /**
     * 캐시 복원용 팩토리 메서드.
     * Infrastructure 레이어에서 Reflection 없이 도메인 객체를 복원할 수 있도록 한다.
     */
    public static Product restoreFromCache(Long id, Brand brand, String name, int price, int supplyPrice,
                                           int discountPrice, int shippingFee, int likeCount,
                                           String description, MarginType marginType, ProductStatus status,
                                           String displayYn, List<ProductOption> options, ZonedDateTime createdAt) {
        Product product = new Product(brand, name, price, supplyPrice, discountPrice,
                shippingFee, description, marginType, status, displayYn, options);
        product.likeCount = likeCount;
        product.restoreBase(id, createdAt);
        return product;
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
