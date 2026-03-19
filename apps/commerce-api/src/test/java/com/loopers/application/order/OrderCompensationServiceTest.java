package com.loopers.application.order;

import com.loopers.domain.coupon.CouponService;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.point.PointService;
import com.loopers.domain.product.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderCompensationService 단위 테스트")
class OrderCompensationServiceTest {

    @Mock
    private OrderService orderService;

    @Mock
    private ProductService productService;

    @Mock
    private CouponService couponService;

    @Mock
    private PointService pointService;

    @InjectMocks
    private OrderCompensationService orderCompensationService;

    private Order createOrderWithCompensationData(Long orderId, Long memberId,
                                                   Long memberCouponId, int usedPoints,
                                                   List<OrderItem> orderItems) {
        Order order = Order.create(memberId, orderItems, 5000, memberCouponId, usedPoints);
        ReflectionTestUtils.setField(order, "id", orderId);
        return order;
    }

    private OrderItem createOrderItemStub(Long productOptionId, int quantity) {
        try {
            var constructor = OrderItem.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            var orderItem = constructor.newInstance();
            ReflectionTestUtils.setField(orderItem, "productOptionId", productOptionId);
            ReflectionTestUtils.setField(orderItem, "quantity", quantity);
            ReflectionTestUtils.setField(orderItem, "price", 10000);
            return orderItem;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("주문 실패 보상을 처리할 때,")
    class CompensateFailedOrder {

        @Test
        @DisplayName("재고, 쿠폰, 포인트를 모두 원복한다.")
        void compensatesAll_whenOrderHasCouponAndPoints() {
            // given
            Long orderId = 1L;
            Long memberId = 10L;
            Long memberCouponId = 100L;
            int usedPoints = 3000;

            OrderItem item1 = createOrderItemStub(1L, 2);
            OrderItem item2 = createOrderItemStub(2L, 3);
            List<OrderItem> orderItems = List.of(item1, item2);

            Order order = createOrderWithCompensationData(orderId, memberId, memberCouponId, usedPoints, orderItems);
            when(orderService.failOrder(orderId)).thenReturn(order);

            // when
            orderCompensationService.compensateFailedOrder(orderId);

            // then
            verify(orderService, times(1)).failOrder(orderId);
            verify(productService, times(1)).restoreStock(1L, 2);
            verify(productService, times(1)).restoreStock(2L, 3);
            verify(couponService, times(1)).cancelUsedCoupon(memberCouponId);
            verify(pointService, times(1)).restorePoint(memberId, usedPoints, "주문 실패 환불", orderId);
        }

        @Test
        @DisplayName("쿠폰을 사용하지 않은 주문이면 쿠폰 원복을 생략한다.")
        void skipsCoupon_whenNoCouponUsed() {
            // given
            Long orderId = 1L;
            Long memberId = 10L;

            OrderItem item = createOrderItemStub(1L, 1);
            Order order = createOrderWithCompensationData(orderId, memberId, null, 0, List.of(item));
            when(orderService.failOrder(orderId)).thenReturn(order);

            // when
            orderCompensationService.compensateFailedOrder(orderId);

            // then
            verify(orderService, times(1)).failOrder(orderId);
            verify(productService, times(1)).restoreStock(1L, 1);
            verify(couponService, never()).cancelUsedCoupon(any());
            verify(pointService, never()).restorePoint(any(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("포인트를 사용하지 않은 주문이면 포인트 원복을 생략한다.")
        void skipsPoints_whenNoPointsUsed() {
            // given
            Long orderId = 1L;
            Long memberId = 10L;
            Long memberCouponId = 100L;

            OrderItem item = createOrderItemStub(1L, 1);
            Order order = createOrderWithCompensationData(orderId, memberId, memberCouponId, 0, List.of(item));
            when(orderService.failOrder(orderId)).thenReturn(order);

            // when
            orderCompensationService.compensateFailedOrder(orderId);

            // then
            verify(orderService, times(1)).failOrder(orderId);
            verify(productService, times(1)).restoreStock(1L, 1);
            verify(couponService, times(1)).cancelUsedCoupon(memberCouponId);
            verify(pointService, never()).restorePoint(any(), anyInt(), any(), any());
        }
    }
}
