package com.loopers.interfaces.api.order.dto;

import com.loopers.application.order.OrderDetailInfo;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.ZonedDateTime;
import java.util.List;

public class OrderV1Dto {

    public record CreateOrderRequest(
            @NotEmpty(message = "주문 항목은 1개 이상이어야 합니다.")
            @Valid
            List<OrderItemRequest> items,
            Long memberCouponId,
            @Min(value = 0, message = "사용 포인트는 0 이상이어야 합니다.")
            int usedPoints,
            List<Long> cartProductOptionIds,
            @NotBlank(message = "카드 종류는 필수입니다.")
            String cardType,
            @NotBlank(message = "카드 번호는 필수입니다.")
            String cardNo) {}

    public record OrderItemRequest(
            @NotNull(message = "상품 ID는 필수입니다.")
            Long productId,
            @NotNull(message = "상품 옵션 ID는 필수입니다.")
            Long productOptionId,
            @Min(value = 1, message = "수량은 1 이상이어야 합니다.")
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
                    info.shippingFee(),
                    info.quantity(),
                    info.subtotal());
        }
    }
}
