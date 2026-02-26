package com.loopers.interfaces.api.order.dto;

import com.loopers.application.order.OrderDetailInfo;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemInfo;

import java.time.ZonedDateTime;
import java.util.List;

public class OrderV1Dto {

    public record CreateOrderRequest(
            List<OrderItemRequest> items,
            Long memberCouponId,
            int usedPoints,
            List<Long> cartProductOptionIds) {}

    public record OrderItemRequest(
            Long productId,
            Long productOptionId,
            int quantity) {}

    public record OrderResponse(
            Long id,
            int totalAmount,
            int discountAmount,
            int usedPoints,
            int paymentAmount,
            int itemCount,
            ZonedDateTime createdAt) {

        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                    info.id(),
                    info.totalAmount(),
                    info.discountAmount(),
                    info.usedPoints(),
                    info.paymentAmount(),
                    info.itemCount(),
                    info.createdAt());
        }
    }

    public record OrderDetailResponse(
            Long id,
            int totalAmount,
            int discountAmount,
            int usedPoints,
            int paymentAmount,
            List<OrderItemResponse> orderItems,
            ZonedDateTime createdAt) {

        public static OrderDetailResponse from(OrderDetailInfo info) {
            List<OrderItemResponse> items = info.orderItems().stream()
                    .map(OrderItemResponse::from)
                    .toList();

            return new OrderDetailResponse(
                    info.id(),
                    info.totalAmount(),
                    info.discountAmount(),
                    info.usedPoints(),
                    info.paymentAmount(),
                    items,
                    info.createdAt());
        }
    }

    public record OrderItemResponse(
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
            int subtotal) {

        public static OrderItemResponse from(OrderItemInfo info) {
            return new OrderItemResponse(
                    info.id(),
                    info.productId(),
                    info.brandId(),
                    info.productName(),
                    info.optionName(),
                    info.brandName(),
                    info.price(),
                    info.supplyPrice(),
                    info.shippingFee(),
                    info.quantity(),
                    info.subtotal());
        }
    }
}
