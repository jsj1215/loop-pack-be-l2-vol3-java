package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<OrderItem> orderItems = new ArrayList<>();

    @Column(name = "total_amount", nullable = false)
    private int totalAmount;

    @Column(name = "discount_amount", nullable = false)
    private int discountAmount;

    @Column(name = "member_coupon_id")
    private Long memberCouponId;

    @Column(name = "used_points", nullable = false)
    private int usedPoints;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    protected Order() {}

    private Order(Long memberId, List<OrderItem> orderItems,
                  int totalAmount, int discountAmount, Long memberCouponId, int usedPoints) {
        this.memberId = memberId;
        this.totalAmount = totalAmount;
        this.discountAmount = discountAmount;
        this.memberCouponId = memberCouponId;
        this.usedPoints = usedPoints;
        this.status = OrderStatus.PENDING;

        if (orderItems != null) {
            for (OrderItem item : orderItems) {
                item.setOrder(this);
                this.orderItems.add(item);
            }
        }
    }

    public static Order create(Long memberId, List<OrderItem> items,
                               int discountAmount, Long memberCouponId, int usedPoints) {
        if (items == null || items.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 항목은 1개 이상이어야 합니다.");
        }
        int totalAmount = calculateTotalAmount(items);
        if (discountAmount + usedPoints > totalAmount) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                    "할인 금액과 포인트의 합이 주문 총액을 초과할 수 없습니다.");
        }
        return new Order(memberId, items, totalAmount, discountAmount,
                memberCouponId, usedPoints);
    }

    public static int calculateTotalAmount(List<OrderItem> items) {
        return items.stream()
                .mapToInt(OrderItem::getSubtotal)
                .sum();
    }

    public int getPaymentAmount() {
        return totalAmount - discountAmount - usedPoints;
    }

    public void validateOwner(Long memberId) {
        if (!this.memberId.equals(memberId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다.");
        }
    }

    public void markPaid() {
        validateNotFinalized();
        this.status = OrderStatus.PAID;
    }

    public void markFailed() {
        validateNotFinalized();
        this.status = OrderStatus.FAILED;
    }

    public void cancel() {
        validateNotFinalized();
        this.status = OrderStatus.CANCELLED;
    }

    private void validateNotFinalized() {
        if (this.status == OrderStatus.PAID || this.status == OrderStatus.FAILED || this.status == OrderStatus.CANCELLED) {
            throw new CoreException(ErrorType.CONFLICT, "이미 처리 완료된 주문입니다.");
        }
    }
}
