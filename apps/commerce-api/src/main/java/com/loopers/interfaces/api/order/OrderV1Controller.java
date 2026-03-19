package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderDetailInfo;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderPaymentFacade;
import com.loopers.domain.member.Member;
import com.loopers.domain.order.OrderService.OrderItemRequest;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginMember;
import com.loopers.interfaces.api.order.dto.OrderV1Dto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/orders")
public class OrderV1Controller {

    private final OrderFacade orderFacade;
    private final OrderPaymentFacade orderPaymentFacade;

    @PostMapping
    public ApiResponse<OrderV1Dto.OrderDetailResponse> createOrder(
            @LoginMember Member member,
            @Valid @RequestBody OrderV1Dto.CreateOrderRequest request) {

        List<OrderItemRequest> itemRequests = request.items().stream()
                .map(item -> new OrderItemRequest(item.productId(), item.productOptionId(), item.quantity()))
                .toList();

        try {
            OrderDetailInfo info = orderPaymentFacade.createOrder(
                    member.getId(),
                    itemRequests,
                    request.memberCouponId(),
                    request.usedPoints(),
                    request.cartProductOptionIds(),
                    request.cardType(),
                    request.cardNo());

            log.info("주문 생성 성공 memberId={}, orderId={}, totalAmount={}, discountAmount={}, usedPoints={}, paymentAmount={}",
                    member.getId(), info.id(), info.totalAmount(), info.discountAmount(), info.usedPoints(), info.paymentAmount());

            return ApiResponse.success(OrderV1Dto.OrderDetailResponse.from(info));
        } catch (Exception e) {
            log.warn("주문 생성 실패 memberId={}, itemCount={}", member.getId(), itemRequests.size(), e);
            throw e;
        }
    }

    @GetMapping
    public ApiResponse<Page<OrderV1Dto.OrderResponse>> getOrders(
            @LoginMember Member member,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime startAt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime endAt,
            Pageable pageable) {

        Page<OrderInfo> orders = orderFacade.findOrders(member.getId(), startAt, endAt, pageable);
        return ApiResponse.success(orders.map(OrderV1Dto.OrderResponse::from));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderV1Dto.OrderDetailResponse> getOrderDetail(
            @LoginMember Member member,
            @PathVariable Long orderId) {

        OrderDetailInfo info = orderFacade.findOrderDetail(orderId, member.getId());
        return ApiResponse.success(OrderV1Dto.OrderDetailResponse.from(info));
    }
}
