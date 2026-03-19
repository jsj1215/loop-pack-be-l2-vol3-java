package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

@RequiredArgsConstructor
@Component
public class OrderFacade {

    private final OrderService orderService;

    @Transactional(readOnly = true)
    public Page<OrderInfo> findOrders(Long memberId, ZonedDateTime startAt, ZonedDateTime endAt, Pageable pageable) {
        return orderService.findOrders(memberId, startAt, endAt, pageable)
                .map(OrderInfo::from);
    }

    @Transactional(readOnly = true)
    public OrderDetailInfo findOrderDetail(Long orderId, Long memberId) {
        Order order = orderService.findOrderDetail(orderId, memberId);
        return OrderDetailInfo.from(order);
    }
}
