package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderFacade 단위 테스트")
class OrderFacadeTest {

    @Mock
    private OrderService orderService;

    @InjectMocks
    private OrderFacade orderFacade;

    private Brand createBrandWithId(Long id, String name) {
        Brand brand = new Brand(name, "스포츠");
        brand.changeStatus(BrandStatus.ACTIVE);
        ReflectionTestUtils.setField(brand, "id", id);
        return brand;
    }

    private OrderItem createOrderItemWithId(Long id, Long productId, Long brandId,
                                             String productName, String optionName, String brandName,
                                             int price, int supplyPrice, int shippingFee, int quantity) {
        Brand brand = createBrandWithId(brandId, brandName);
        Product product = new Product(brand, productName, price, supplyPrice, 0, shippingFee,
                "", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y", List.of());
        ReflectionTestUtils.setField(product, "id", productId);

        ProductOption option = new ProductOption(productId, optionName, 100);
        ReflectionTestUtils.setField(option, "id", 1L);

        OrderItem item = OrderItem.createSnapshot(product, option, quantity);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private Order createOrderWithId(Long id, Long memberId, List<OrderItem> items,
                                     int totalAmount, int discountAmount, Long memberCouponId, int usedPoints) {
        Order order = Order.create(memberId, items, discountAmount, memberCouponId, usedPoints);
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "totalAmount", totalAmount);
        return order;
    }

    @Nested
    @DisplayName("주문 목록을 조회할 때,")
    class FindOrders {

        @Test
        @DisplayName("OrderService에 위임하여 조회한다.")
        void delegatesToOrderService() {
            // given
            Long memberId = 1L;
            ZonedDateTime startAt = ZonedDateTime.now().minusDays(30);
            ZonedDateTime endAt = ZonedDateTime.now();
            Pageable pageable = PageRequest.of(0, 20);

            OrderItem item = createOrderItemWithId(1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 2);
            Order order = createOrderWithId(1L, memberId, List.of(item),
                    100000, 0, null, 0);

            when(orderService.findOrders(memberId, startAt, endAt, pageable))
                    .thenReturn(new PageImpl<>(List.of(order), pageable, 1));

            // when
            Page<OrderInfo> result = orderFacade.findOrders(memberId, startAt, endAt, pageable);

            // then
            assertAll(
                    () -> assertThat(result.getContent()).hasSize(1),
                    () -> assertThat(result.getContent().get(0).id()).isEqualTo(1L),
                    () -> verify(orderService, times(1)).findOrders(memberId, startAt, endAt, pageable)
            );
        }
    }

    @Nested
    @DisplayName("주문 상세를 조회할 때,")
    class FindOrderDetail {

        @Test
        @DisplayName("OrderService에 위임하여 조회한다.")
        void delegatesToOrderService() {
            // given
            Long orderId = 1L;
            Long memberId = 1L;

            OrderItem item = createOrderItemWithId(1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 2);
            Order order = createOrderWithId(orderId, memberId, List.of(item),
                    100000, 5000, 10L, 1000);

            when(orderService.findOrderDetail(orderId, memberId)).thenReturn(order);

            // when
            OrderDetailInfo result = orderFacade.findOrderDetail(orderId, memberId);

            // then
            assertAll(
                    () -> assertThat(result.id()).isEqualTo(orderId),
                    () -> assertThat(result.totalAmount()).isEqualTo(100000),
                    () -> assertThat(result.discountAmount()).isEqualTo(5000),
                    () -> assertThat(result.usedPoints()).isEqualTo(1000),
                    () -> verify(orderService, times(1)).findOrderDetail(orderId, memberId)
            );
        }
    }
}
