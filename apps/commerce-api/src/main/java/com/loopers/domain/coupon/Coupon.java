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

    @Column(name = "valid_from", nullable = false)
    private ZonedDateTime validFrom;

    @Column(name = "valid_to", nullable = false)
    private ZonedDateTime validTo;

    @Column(name = "max_issue_count", nullable = false)
    private int maxIssueCount;

    protected Coupon() {}

    public Coupon(String name, CouponScope couponScope, Long targetId,
                  DiscountType discountType, int discountValue, int minOrderAmount,
                  int maxDiscountAmount,
                  ZonedDateTime validFrom, ZonedDateTime validTo) {
        this(name, couponScope, targetId, discountType, discountValue, minOrderAmount,
                maxDiscountAmount, validFrom, validTo, 0);
    }

    public Coupon(String name, CouponScope couponScope, Long targetId,
                  DiscountType discountType, int discountValue, int minOrderAmount,
                  int maxDiscountAmount,
                  ZonedDateTime validFrom, ZonedDateTime validTo, int maxIssueCount) {
        validateCoupon(name, discountType, discountValue, validFrom, validTo);
        this.name = name;
        this.couponScope = couponScope;
        this.targetId = targetId;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.minOrderAmount = minOrderAmount;
        this.maxDiscountAmount = maxDiscountAmount;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.maxIssueCount = maxIssueCount;
    }

    /**
     * 쿠폰 정보 수정
     */
    public void updateInfo(String name, CouponScope couponScope, Long targetId,
                           DiscountType discountType, int discountValue, int minOrderAmount,
                           int maxDiscountAmount,
                           ZonedDateTime validFrom, ZonedDateTime validTo) {
        validateCoupon(name, discountType, discountValue, validFrom, validTo);
        this.name = name;
        this.couponScope = couponScope;
        this.targetId = targetId;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.minOrderAmount = minOrderAmount;
        this.maxDiscountAmount = maxDiscountAmount;
        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    private static void validateCoupon(String name, DiscountType discountType, int discountValue,
                                       ZonedDateTime validFrom, ZonedDateTime validTo) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰 이름은 필수입니다.");
        }
        if (discountValue <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "할인 값은 0보다 커야 합니다.");
        }
        if (discountType == DiscountType.FIXED_RATE && discountValue > 100) {
            throw new CoreException(ErrorType.BAD_REQUEST, "정률 할인은 100%를 초과할 수 없습니다.");
        }
        if (validFrom == null || validTo == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "유효기간은 필수입니다.");
        }
        if (!validFrom.isBefore(validTo)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "유효기간 시작일은 종료일보다 이전이어야 합니다.");
        }
    }

    /**
     * 선착순 수량 제한 쿠폰인지 확인
     */
    public boolean isLimitedIssue() {
        return this.maxIssueCount > 0;
    }

    /**
     * 쿠폰 유효기간 확인
     */
    public boolean isValid() {
        ZonedDateTime now = ZonedDateTime.now();
        return !now.isBefore(validFrom) && !now.isAfter(validTo);
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
