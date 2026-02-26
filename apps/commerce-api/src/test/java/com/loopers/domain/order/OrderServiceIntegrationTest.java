package com.loopers.domain.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [통합 테스트 - Service Integration]
 *
 * 테스트 대상: OrderService (Domain Layer)
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: Service → Repository → Database
 */
@SpringBootTest
@Transactional
class OrderServiceIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductService productService;

    @Autowired
    private BrandService brandService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Product createProductWithStock(String brandName, String productName, int price, int stockQuantity) {
        Brand brand = brandService.register(brandName, brandName + " 설명");
        brand = brandService.update(brand.getId(), brandName, brandName + " 설명", BrandStatus.ACTIVE);
        ProductOption option = new ProductOption(null, productName + " 옵션", stockQuantity);
        return productService.register(brand, productName, price, MarginType.RATE, 10,
                0, 3000, productName + " 설명", List.of(option));
    }

    @DisplayName("주문 아이템을 준비할 때,")
    @Nested
    class PrepareOrderItems {

        @Test
        @DisplayName("유효한 상품이면, 재고를 차감하고 스냅샷을 생성한다.")
        void preparesOrderItems_withStockDeductionAndSnapshot() {
            // given
            Product product = createProductWithStock("나이키", "에어맥스", 100000, 50);
            Long optionId = product.getOptions().get(0).getId();

            List<OrderService.OrderItemRequest> requests = List.of(
                    new OrderService.OrderItemRequest(product.getId(), optionId, 2)
            );

            // when
            List<OrderItem> orderItems = orderService.prepareOrderItems(requests);

            // then
            assertAll(
                    () -> assertThat(orderItems).hasSize(1),
                    () -> assertThat(orderItems.get(0).getProductId()).isEqualTo(product.getId()),
                    () -> assertThat(orderItems.get(0).getProductName()).isEqualTo("에어맥스"),
                    () -> assertThat(orderItems.get(0).getOptionName()).isEqualTo("에어맥스 옵션"),
                    () -> assertThat(orderItems.get(0).getBrandName()).isEqualTo("나이키"),
                    () -> assertThat(orderItems.get(0).getPrice()).isEqualTo(100000),
                    () -> assertThat(orderItems.get(0).getQuantity()).isEqualTo(2)
            );

            // 재고 차감 검증
            ProductOption option = productService.findOptionById(optionId);
            assertThat(option.getStockQuantity()).isEqualTo(48);
        }

        @Test
        @DisplayName("존재하지 않는 상품이면, NOT_FOUND 예외가 발생한다.")
        void throwsException_whenProductNotFound() {
            // given
            List<OrderService.OrderItemRequest> requests = List.of(
                    new OrderService.OrderItemRequest(999L, 999L, 1)
            );

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> orderService.prepareOrderItems(requests));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @Test
        @DisplayName("재고가 부족하면, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNotEnoughStock() {
            // given
            Product product = createProductWithStock("나이키", "에어맥스", 100000, 5);
            Long optionId = product.getOptions().get(0).getId();

            List<OrderService.OrderItemRequest> requests = List.of(
                    new OrderService.OrderItemRequest(product.getId(), optionId, 10)
            );

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> orderService.prepareOrderItems(requests));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("재고가 부족")
            );
        }
    }

    @DisplayName("주문을 생성할 때,")
    @Nested
    class CreateOrder {

        @Test
        @DisplayName("주문 아이템으로 주문을 생성하면, 주문이 저장된다.")
        void createsOrder_whenValidItems() {
            // given
            Product product = createProductWithStock("나이키", "에어맥스", 100000, 50);
            Long optionId = product.getOptions().get(0).getId();

            List<OrderService.OrderItemRequest> requests = List.of(
                    new OrderService.OrderItemRequest(product.getId(), optionId, 2)
            );
            List<OrderItem> orderItems = orderService.prepareOrderItems(requests);
            Long memberId = 1L;

            // when
            Order order = orderService.createOrder(memberId, orderItems, 5000, null, 0);

            // then
            assertAll(
                    () -> assertThat(order.getId()).isNotNull(),
                    () -> assertThat(order.getMemberId()).isEqualTo(memberId),
                    () -> assertThat(order.getTotalAmount()).isEqualTo(200000), // 100000 * 2
                    () -> assertThat(order.getDiscountAmount()).isEqualTo(5000),
                    () -> assertThat(order.getPaymentAmount()).isEqualTo(195000), // 200000 - 5000 - 0
                    () -> assertThat(order.getOrderItems()).hasSize(1)
            );
        }
    }

    @DisplayName("주문 목록을 조회할 때,")
    @Nested
    class FindOrders {

        @Test
        @DisplayName("기간별 주문 목록을 조회하면, 해당 기간의 주문을 반환한다.")
        void returnsOrders_withinPeriod() {
            // given
            Product product = createProductWithStock("나이키", "에어맥스", 100000, 50);
            Long optionId = product.getOptions().get(0).getId();
            Long memberId = 1L;

            List<OrderService.OrderItemRequest> requests = List.of(
                    new OrderService.OrderItemRequest(product.getId(), optionId, 1)
            );
            List<OrderItem> orderItems = orderService.prepareOrderItems(requests);
            orderService.createOrder(memberId, orderItems, 0, null, 0);

            ZonedDateTime startAt = ZonedDateTime.now().minusDays(1);
            ZonedDateTime endAt = ZonedDateTime.now().plusDays(1);

            // when
            Page<Order> orders = orderService.findOrders(memberId, startAt, endAt, PageRequest.of(0, 10));

            // then
            assertThat(orders.getTotalElements()).isEqualTo(1);
        }
    }

    @DisplayName("주문 상세를 조회할 때,")
    @Nested
    class FindOrderDetail {

        @Test
        @DisplayName("본인의 주문을 조회하면, 주문 상세가 반환된다.")
        void returnsOrderDetail_whenOwner() {
            // given
            Product product = createProductWithStock("나이키", "에어맥스", 100000, 50);
            Long optionId = product.getOptions().get(0).getId();
            Long memberId = 1L;

            List<OrderService.OrderItemRequest> requests = List.of(
                    new OrderService.OrderItemRequest(product.getId(), optionId, 2)
            );
            List<OrderItem> orderItems = orderService.prepareOrderItems(requests);
            Order savedOrder = orderService.createOrder(memberId, orderItems, 0, null, 0);

            // when
            Order order = orderService.findOrderDetail(savedOrder.getId(), memberId);

            // then
            assertAll(
                    () -> assertThat(order.getId()).isEqualTo(savedOrder.getId()),
                    () -> assertThat(order.getMemberId()).isEqualTo(memberId),
                    () -> assertThat(order.getOrderItems()).hasSize(1),
                    () -> assertThat(order.getOrderItems().get(0).getProductName()).isEqualTo("에어맥스")
            );
        }
    }
}
