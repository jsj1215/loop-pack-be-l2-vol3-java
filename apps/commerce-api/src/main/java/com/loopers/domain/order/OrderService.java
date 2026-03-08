package com.loopers.domain.order;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RequiredArgsConstructor
@Component
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductService productService;

    /**
     * 주문 항목 준비 (재고 검증/차감 + 스냅샷 생성)
     * 데드락 방지를 위해 productOptionId 오름차순으로 정렬하여 비관적 락 획득 순서를 일관되게 유지한다.
     */
    public List<OrderItem> prepareOrderItems(List<OrderItemRequest> itemRequests) {
        List<OrderItemRequest> sortedRequests = itemRequests.stream()
                .sorted(Comparator.comparing(OrderItemRequest::productOptionId))
                .toList();

        List<OrderItem> orderItems = new ArrayList<>();

        for (OrderItemRequest request : sortedRequests) {
            Product product = productService.findProductOnly(request.productId());
            ProductOption option = productService.deductStock(request.productOptionId(), request.quantity());

            OrderItem item = OrderItem.createSnapshot(product, option, request.quantity());
            orderItems.add(item);
        }

        return orderItems;
    }

    public Order createOrder(Long memberId, List<OrderItem> orderItems,
                             int discountAmount, Long memberCouponId, int usedPoints) {
        Order order = Order.create(memberId, orderItems, discountAmount, memberCouponId, usedPoints);
        return orderRepository.save(order);
    }

    public Page<Order> findOrders(Long memberId, ZonedDateTime startAt, ZonedDateTime endAt, Pageable pageable) {
        return orderRepository.findByMemberIdAndCreatedAtBetween(memberId, startAt, endAt, pageable);
    }

    public Order findOrderDetail(Long orderId, Long memberId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));

        order.validateOwner(memberId);
        return order;
    }

    public record OrderItemRequest(Long productId, Long productOptionId, int quantity) {}
}
