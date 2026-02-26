package com.loopers.application.order;

import com.loopers.domain.order.Order;

import java.time.ZonedDateTime;
import java.util.List;

public record OrderDetailInfo(
        Long id,
        int totalAmount,
        int discountAmount,
        int usedPoints,
        int paymentAmount,
        List<OrderItemInfo> orderItems,
        ZonedDateTime createdAt) {

    public static OrderDetailInfo from(Order order) {
        List<OrderItemInfo> items = order.getOrderItems().stream()
                .map(OrderItemInfo::from)
                .toList();

        return new OrderDetailInfo(
                order.getId(),
                order.getTotalAmount(),
                order.getDiscountAmount(),
                order.getUsedPoints(),
                order.getPaymentAmount(),
                items,
                order.getCreatedAt());
    }
}
