package com.loopers.domain.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService 단위 테스트")
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductService productService;

    @InjectMocks
    private OrderService orderService;

    private Brand createBrandWithId(Long id) {
        Brand brand = new Brand("나이키", "스포츠");
        brand.changeStatus(BrandStatus.ACTIVE);
        ReflectionTestUtils.setField(brand, "id", id);
        return brand;
    }

    private Product createProductWithId(Long id, Brand brand) {
        Product product = new Product(brand, "운동화", 50000, 40000, 5000, 3000,
                "설명", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y", List.of());
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private ProductOption createProductOptionWithId(Long id, Long productId, String optionName, int stockQuantity) {
        ProductOption option = new ProductOption(productId, optionName, stockQuantity);
        ReflectionTestUtils.setField(option, "id", id);
        return option;
    }

    private OrderItem createOrderItemFromSnapshot(Product product, ProductOption option, int quantity) {
        return OrderItem.createSnapshot(product, option, quantity);
    }

    private OrderItem createOrderItemWithId(Long id, Long productId, Long brandId,
                                             String productName, String optionName, String brandName,
                                             int price, int supplyPrice, int shippingFee, int quantity) {
        Brand brand = createBrandWithId(brandId);
        ReflectionTestUtils.setField(brand, "name", brandName);
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
    @DisplayName("주문 항목을 준비할 때,")
    class PrepareOrderItems {

        @Test
        @DisplayName("상품과 옵션을 조회하고 재고를 차감한 뒤 스냅샷을 생성한다.")
        void createsSnapshotAfterDeductingStock() {
            // given
            Brand brand = createBrandWithId(1L);
            Product product = createProductWithId(1L, brand);
            ProductOption option = createProductOptionWithId(1L, 1L, "270mm", 100);

            when(productService.findById(1L)).thenReturn(product);
            when(productService.findOptionById(1L)).thenReturn(option);

            List<OrderService.OrderItemRequest> requests = List.of(
                    new OrderService.OrderItemRequest(1L, 1L, 2)
            );

            // when
            List<OrderItem> result = orderService.prepareOrderItems(requests);

            // then
            assertAll(
                    () -> assertThat(result).hasSize(1),
                    () -> assertThat(result.get(0).getProductName()).isEqualTo("운동화"),
                    () -> assertThat(result.get(0).getOptionName()).isEqualTo("270mm"),
                    () -> assertThat(result.get(0).getQuantity()).isEqualTo(2),
                    () -> verify(productService, times(1)).deductStock(1L, 2)
            );
        }

        @Test
        @DisplayName("상품이 존재하지 않으면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenProductNotFound() {
            // given
            when(productService.findById(999L))
                    .thenThrow(new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));

            List<OrderService.OrderItemRequest> requests = List.of(
                    new OrderService.OrderItemRequest(999L, 1L, 1)
            );

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> orderService.prepareOrderItems(requests));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @Test
        @DisplayName("재고가 부족하면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenStockInsufficient() {
            // given
            Brand brand = createBrandWithId(1L);
            Product product = createProductWithId(1L, brand);
            ProductOption option = createProductOptionWithId(1L, 1L, "270mm", 100);

            when(productService.findById(1L)).thenReturn(product);
            when(productService.findOptionById(1L)).thenReturn(option);
            doThrow(new CoreException(ErrorType.BAD_REQUEST, "재고가 부족합니다."))
                    .when(productService).deductStock(1L, 200);

            List<OrderService.OrderItemRequest> requests = List.of(
                    new OrderService.OrderItemRequest(1L, 1L, 200)
            );

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> orderService.prepareOrderItems(requests));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("주문을 생성할 때,")
    class CreateOrder {

        @Test
        @DisplayName("주문을 저장하고 반환한다.")
        void savesOrderViaRepository() {
            // given
            Long memberId = 1L;
            OrderItem item = createOrderItemWithId(null, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 2);
            List<OrderItem> orderItems = List.of(item);
            int discountAmount = 5000;
            Long memberCouponId = 10L;
            int usedPoints = 1000;

            when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            Order result = orderService.createOrder(memberId, orderItems, discountAmount, memberCouponId, usedPoints);

            // then
            assertAll(
                    () -> assertThat(result.getMemberId()).isEqualTo(memberId),
                    () -> assertThat(result.getTotalAmount()).isEqualTo(100000),
                    () -> assertThat(result.getDiscountAmount()).isEqualTo(5000),
                    () -> assertThat(result.getUsedPoints()).isEqualTo(1000),
                    () -> verify(orderRepository, times(1)).save(any(Order.class))
            );
        }
    }

    @Nested
    @DisplayName("주문 목록을 조회할 때,")
    class FindOrders {

        @Test
        @DisplayName("회원 ID와 기간으로 주문 목록을 조회한다.")
        void delegatesToRepository() {
            // given
            Long memberId = 1L;
            ZonedDateTime startAt = ZonedDateTime.now().minusDays(30);
            ZonedDateTime endAt = ZonedDateTime.now();
            Pageable pageable = PageRequest.of(0, 20);

            when(orderRepository.findByMemberIdAndCreatedAtBetween(memberId, startAt, endAt, pageable))
                    .thenReturn(new PageImpl<>(Collections.emptyList(), pageable, 0));

            // when
            Page<Order> result = orderService.findOrders(memberId, startAt, endAt, pageable);

            // then
            assertAll(
                    () -> assertThat(result.getContent()).isEmpty(),
                    () -> verify(orderRepository, times(1))
                            .findByMemberIdAndCreatedAtBetween(memberId, startAt, endAt, pageable)
            );
        }
    }

    @Nested
    @DisplayName("주문 상세를 조회할 때,")
    class FindOrderDetail {

        @Test
        @DisplayName("주문이 존재하고 소유자가 일치하면 주문을 반환한다.")
        void returnsOrder_whenExistsAndOwnerMatches() {
            // given
            Long orderId = 1L;
            Long memberId = 1L;
            Order order = createOrderWithId(orderId, memberId, List.of(),
                    100000, 0, null, 0);
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

            // when
            Order result = orderService.findOrderDetail(orderId, memberId);

            // then
            assertAll(
                    () -> assertThat(result.getId()).isEqualTo(orderId),
                    () -> assertThat(result.getMemberId()).isEqualTo(memberId)
            );
        }

        @Test
        @DisplayName("주문이 존재하지 않으면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenOrderNotFound() {
            // given
            when(orderRepository.findById(999L)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> orderService.findOrderDetail(999L, 1L));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @Test
        @DisplayName("주문 소유자가 일치하지 않으면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenOwnerDoesNotMatch() {
            // given
            Long orderId = 1L;
            Long ownerMemberId = 1L;
            Long otherMemberId = 999L;
            Order order = createOrderWithId(orderId, ownerMemberId, List.of(),
                    100000, 0, null, 0);
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> orderService.findOrderDetail(orderId, otherMemberId));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
