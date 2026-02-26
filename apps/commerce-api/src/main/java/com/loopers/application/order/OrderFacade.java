package com.loopers.application.order;

import com.loopers.domain.cart.CartService;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponService;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderService.OrderItemRequest;
import com.loopers.domain.point.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

@RequiredArgsConstructor
@Component
public class OrderFacade {

    private final OrderService orderService;
    private final CouponService couponService;
    private final PointService pointService;
    private final CartService cartService;

    @Transactional
    public OrderDetailInfo createOrder(Long memberId, List<OrderItemRequest> itemRequests,
                                       Long memberCouponId, int usedPoints,
                                       List<Long> productOptionIdsFromCart) {
        // 1. 재고 검증/차감 + 스냅샷 생성
        List<OrderItem> orderItems = orderService.prepareOrderItems(itemRequests);
        int totalAmount = Order.calculateTotalAmount(orderItems);

        // 2. 쿠폰 할인 계산
        int discountAmount = 0;
        if (memberCouponId != null) {
            int applicableAmount = calculateApplicableAmount(memberCouponId, orderItems, totalAmount);
            discountAmount = couponService.calculateCouponDiscount(memberId, memberCouponId, applicableAmount);
        }

        // 3. 주문 생성 및 저장
        Order order = orderService.createOrder(memberId, orderItems, discountAmount, memberCouponId, usedPoints);

        // 4. 쿠폰 사용 처리
        if (memberCouponId != null) {
            couponService.useCoupon(memberCouponId, order.getId());
        }

        // 5. 포인트 사용 처리
        if (usedPoints > 0) {
            pointService.usePoint(memberId, usedPoints, "주문 사용", order.getId());
        }

        // 6. 장바구니 삭제 처리
        if (productOptionIdsFromCart != null && !productOptionIdsFromCart.isEmpty()) {
            cartService.deleteByMemberIdAndProductOptionIds(memberId, productOptionIdsFromCart);
        }

        return OrderDetailInfo.from(order);
    }

    /**
     * 쿠폰 scope에 따른 적용 대상 금액 계산
     * - CART: 전체 주문 금액
     * - PRODUCT: 해당 상품의 소계
     * - BRAND: 해당 브랜드 상품들의 소계
     */
    private int calculateApplicableAmount(Long memberCouponId, List<OrderItem> orderItems, int totalAmount) {
        Coupon coupon = couponService.findById(
                couponService.getMemberCoupon(memberCouponId).getCouponId());

        return switch (coupon.getCouponScope()) {
            case CART -> totalAmount;
            case PRODUCT -> orderItems.stream()
                    .filter(item -> item.getProductId().equals(coupon.getTargetId()))
                    .mapToInt(OrderItem::getSubtotal)
                    .sum();
            case BRAND -> orderItems.stream()
                    .filter(item -> item.getBrandId().equals(coupon.getTargetId()))
                    .mapToInt(OrderItem::getSubtotal)
                    .sum();
        };
    }

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
