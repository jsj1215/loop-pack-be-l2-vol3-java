package com.loopers.domain.coupon;

import java.time.ZonedDateTime;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "coupon")
public class Coupon extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "coupon_scope", nullable = false)
    private CouponScope couponScope;

    @Column(name = "target_id")
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private int discountValue;

    @Column(name = "min_order_amount", nullable = false)
    private int minOrderAmount;

    @Column(name = "max_discount_amount", nullable = false)
    private int maxDiscountAmount;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "issued_quantity", nullable = false)
    private int issuedQuantity;

    @Column(name = "valid_from", nullable = false)
    private ZonedDateTime validFrom;

    @Column(name = "valid_to", nullable = false)
    private ZonedDateTime validTo;

    protected Coupon() {}

    public Coupon(String name, CouponScope couponScope, Long targetId,
                  DiscountType discountType, int discountValue, int minOrderAmount,
                  int maxDiscountAmount, int totalQuantity,
                  ZonedDateTime validFrom, ZonedDateTime validTo) {
        this.name = name;
        this.couponScope = couponScope;
        this.targetId = targetId;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.minOrderAmount = minOrderAmount;
        this.maxDiscountAmount = maxDiscountAmount;
        this.totalQuantity = totalQuantity;
        this.issuedQuantity = 0;
        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    /**
     * 쿠폰 발급 가능 여부 확인
     * : 발급 수량이 총 수량 미만이고, 현재 시간이 유효기간 내에 있어야 한다.
     */
    public boolean isIssuable() {
        ZonedDateTime now = ZonedDateTime.now();
        return issuedQuantity < totalQuantity
                && !now.isBefore(validFrom)
                && !now.isAfter(validTo);
    }

    /**
     * 쿠폰 유효기간 확인
     */
    public boolean isValid() {
        ZonedDateTime now = ZonedDateTime.now();
        return !now.isBefore(validFrom) && !now.isAfter(validTo);
    }

    /**
     * 쿠폰 발급 처리
     */
    public void issue() {
        if (!isIssuable()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰 발급이 불가합니다.");
        }
        this.issuedQuantity++;
    }

    /**
     * 할인 금액 계산
     */
    public int calculateDiscount(int applicableAmount) {
        int discount;

        if (discountType == DiscountType.FIXED_AMOUNT) {
            discount = discountValue;
        } else {
            discount = applicableAmount * discountValue / 100;
            if (maxDiscountAmount > 0) {
                discount = Math.min(discount, maxDiscountAmount);
            }
        }

        return Math.min(discount, applicableAmount);
    }
}
