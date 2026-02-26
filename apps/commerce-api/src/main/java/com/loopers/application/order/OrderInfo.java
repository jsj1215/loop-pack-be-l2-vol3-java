package com.loopers.application.order;

import com.loopers.domain.order.Order;

import java.time.ZonedDateTime;
import java.util.List;

public record OrderInfo(
        Long id,
        int totalAmount,
        int discountAmount,
        int usedPoints,
        int paymentAmount,
        int itemCount,
        ZonedDateTime createdAt) {

    public static OrderInfo from(Order order) {
        return new OrderInfo(
                order.getId(),
                order.getTotalAmount(),
                order.getDiscountAmount(),
                order.getUsedPoints(),
                order.getPaymentAmount(),
                order.getOrderItems().size(),
                order.getCreatedAt());
    }
}
