package com.loopers.application.order;

import com.loopers.domain.coupon.CouponService;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.point.PointService;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 실패 보상 서비스 — 결제 실패 시 주문 생성 과정에서 선점한 리소스를 원복한다.
 *
 * 보상 대상:
 *   1. 주문 상태 → FAILED
 *   2. 재고 복원
 *   3. 쿠폰 사용 취소 (사용한 경우)
 *   4. 포인트 환불 (사용한 경우)
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OrderCompensationService {

    private final OrderService orderService;
    private final ProductService productService;
    private final CouponService couponService;
    private final PointService pointService;

    @Transactional
    public void compensateFailedOrder(Long orderId) {
        Order order = orderService.failOrder(orderId);

        restoreStock(order);
        restoreCoupon(order);
        restorePoints(order);

        log.info("주문 실패 보상 완료 orderId={}, memberId={}", orderId, order.getMemberId());
    }

    private void restoreStock(Order order) {
        for (OrderItem item : order.getOrderItems()) {
            productService.restoreStock(item.getProductOptionId(), item.getQuantity());
        }
    }

    private void restoreCoupon(Order order) {
        if (order.getMemberCouponId() != null) {
            couponService.cancelUsedCoupon(order.getMemberCouponId());
        }
    }

    private void restorePoints(Order order) {
        if (order.getUsedPoints() > 0) {
            pointService.restorePoint(
                    order.getMemberId(),
                    order.getUsedPoints(),
                    "주문 실패 환불",
                    order.getId()
            );
        }
    }
}
