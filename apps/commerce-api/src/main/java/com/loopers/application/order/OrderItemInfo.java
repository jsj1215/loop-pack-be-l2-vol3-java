package com.loopers.application.order;

import com.loopers.domain.order.OrderItem;

import java.time.ZonedDateTime;

public record OrderItemInfo(
        Long id,
        Long productId,
        Long brandId,
        String productName,
        String optionName,
        String brandName,
        int price,
        int supplyPrice,
        int shippingFee,
        int quantity,
        int subtotal,
        ZonedDateTime createdAt,
        ZonedDateTime updatedAt) {

    public static OrderItemInfo from(OrderItem item) {
        return new OrderItemInfo(
                item.getId(),
                item.getProductId(),
                item.getBrandId(),
                item.getProductName(),
                item.getOptionName(),
                item.getBrandName(),
                item.getPrice(),
                item.getSupplyPrice(),
                item.getShippingFee(),
                item.getQuantity(),
                item.getSubtotal(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }
}
