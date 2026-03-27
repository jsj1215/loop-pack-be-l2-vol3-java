package com.loopers.application.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.event.OrderFailedEvent;
import com.loopers.domain.event.OrderPaidEvent;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService.OrderItemRequest;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResponse;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderPaymentFacade 단위 테스트")
class OrderPaymentFacadeTest {

    @Mock
    private OrderPlacementService orderPlacementService;

    @Mock
    private OrderCompensationService orderCompensationService;

    @Mock
    private OrderTransactionService orderTransactionService;

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private OrderPaymentFacade orderPaymentFacade;

    @BeforeEach
    void setUp() {
        orderPaymentFacade = new OrderPaymentFacade(
                orderPlacementService, orderCompensationService, orderTransactionService,
                paymentService, paymentGateway, eventPublisher);
    }

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

    @Nested
    @DisplayName("주문을 생성할 때,")
    class CreateOrder {

        @Test
        @DisplayName("주문 생성 후 PG 결제가 호출되고, OrderTransactionService로 결제 상태를 업데이트한다.")
        void createsOrderAndProcessesPayment() {
            // given
            Long memberId = 1L;
            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(1L, 1L, 2)
            );
            String cardType = "SAMSUNG";
            String cardNo = "1234-5678-9012-3456";

            OrderItem orderItem = createOrderItemWithId(1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 2);
            OrderDetailInfo orderInfo = new OrderDetailInfo(
                    1L, 100000, 0, 0, 100000,
                    List.of(OrderItemInfo.from(orderItem)),
                    ZonedDateTime.now());

            when(orderPlacementService.placeOrder(eq(memberId), eq(itemRequests), eq(null), eq(0), eq(null)))
                    .thenReturn(orderInfo);

            Payment payment = Payment.create(memberId, 1L, 100000, cardType, cardNo);
            ReflectionTestUtils.setField(payment, "id", 1L);
            when(paymentService.initiatePayment(memberId, 1L, 100000, cardType, cardNo))
                    .thenReturn(payment);

            PaymentGatewayResponse pgResponse = new PaymentGatewayResponse("tx-123", true, false, null);
            when(paymentGateway.requestPayment(eq(memberId), any(Payment.class)))
                    .thenReturn(pgResponse);

            when(orderTransactionService.updatePaymentStatus(eq(1L), eq(pgResponse)))
                    .thenReturn(PaymentStatus.PENDING);

            // when
            OrderDetailInfo result = orderPaymentFacade.createOrder(memberId, itemRequests, null, 0, null, cardType, cardNo);

            // then
            assertAll(
                    () -> assertThat(result.id()).isEqualTo(1L),
                    () -> assertThat(result.totalAmount()).isEqualTo(100000),
                    () -> verify(orderPlacementService, times(1)).placeOrder(eq(memberId), eq(itemRequests), eq(null), eq(0), eq(null)),
                    () -> verify(paymentService, times(1)).initiatePayment(memberId, 1L, 100000, cardType, cardNo),
                    () -> verify(paymentGateway, times(1)).requestPayment(eq(memberId), any(Payment.class)),
                    () -> verify(orderTransactionService, times(1)).updatePaymentStatus(eq(1L), eq(pgResponse)),
                    () -> verify(orderCompensationService, never()).compensateFailedOrder(anyLong())
            );
        }

        @Test
        @DisplayName("전액 할인(결제 금액 0) 시 OrderTransactionService로 주문 완료 + OrderPaidEvent 발행이 수행된다.")
        void completesOrder_whenPaymentAmountIsZero() {
            // given
            Long memberId = 1L;
            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(1L, 1L, 1)
            );

            OrderItem orderItem = createOrderItemWithId(1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 1);
            OrderDetailInfo orderInfo = new OrderDetailInfo(
                    1L, 50000, 30000, 20000, 0,
                    List.of(OrderItemInfo.from(orderItem)),
                    ZonedDateTime.now());

            when(orderPlacementService.placeOrder(eq(memberId), eq(itemRequests), eq(10L), eq(20000), eq(null)))
                    .thenReturn(orderInfo);

            // when
            OrderDetailInfo result = orderPaymentFacade.createOrder(memberId, itemRequests, 10L, 20000, null, "SAMSUNG", "1234");

            // then
            assertAll(
                    () -> assertThat(result.id()).isEqualTo(1L),
                    () -> assertThat(result.paymentAmount()).isEqualTo(0),
                    () -> verify(orderTransactionService, times(1)).completeOrder(1L),
                    () -> verify(paymentService, never()).initiatePayment(anyLong(), anyLong(), anyInt(), any(), any()),
                    () -> verify(paymentGateway, never()).requestPayment(any(), any()),
                    () -> verify(eventPublisher).publishEvent(any(OrderPaidEvent.class))
            );
        }

        @Test
        @DisplayName("주문 생성 실패 시 결제가 호출되지 않는다.")
        void doesNotProcessPayment_whenOrderFails() {
            // given
            Long memberId = 1L;
            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(1L, 1L, 2)
            );

            when(orderPlacementService.placeOrder(eq(memberId), eq(itemRequests), eq(null), eq(0), eq(null)))
                    .thenThrow(new RuntimeException("재고 부족"));

            // when & then
            assertThrows(RuntimeException.class,
                    () -> orderPaymentFacade.createOrder(memberId, itemRequests, null, 0, null, "SAMSUNG", "1234"));

            verify(paymentService, never()).initiatePayment(anyLong(), anyLong(), anyInt(), any(), any());
            verify(paymentGateway, never()).requestPayment(any(), any());
        }

        @Test
        @DisplayName("PG 결제가 즉시 실패(FAILED)하면 보상 트랜잭션이 실행된다.")
        void compensatesOrder_whenPaymentFails() {
            // given
            Long memberId = 1L;
            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(1L, 1L, 2)
            );
            String cardType = "SAMSUNG";
            String cardNo = "1234-5678-9012-3456";

            OrderItem orderItem = createOrderItemWithId(1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 2);
            OrderDetailInfo orderInfo = new OrderDetailInfo(
                    1L, 100000, 0, 0, 100000,
                    List.of(OrderItemInfo.from(orderItem)),
                    ZonedDateTime.now());

            when(orderPlacementService.placeOrder(eq(memberId), eq(itemRequests), eq(null), eq(0), eq(null)))
                    .thenReturn(orderInfo);

            Payment payment = Payment.create(memberId, 1L, 100000, cardType, cardNo);
            ReflectionTestUtils.setField(payment, "id", 1L);
            when(paymentService.initiatePayment(memberId, 1L, 100000, cardType, cardNo))
                    .thenReturn(payment);

            PaymentGatewayResponse pgResponse = new PaymentGatewayResponse(null, false, false, "서킷브레이커 OPEN");
            when(paymentGateway.requestPayment(eq(memberId), any(Payment.class)))
                    .thenReturn(pgResponse);

            when(orderTransactionService.updatePaymentStatus(eq(1L), eq(pgResponse)))
                    .thenReturn(PaymentStatus.FAILED);

            Payment failedPayment = Payment.create(memberId, 1L, 100000, cardType, cardNo);
            ReflectionTestUtils.setField(failedPayment, "id", 1L);
            ReflectionTestUtils.setField(failedPayment, "status", PaymentStatus.FAILED);
            ReflectionTestUtils.setField(failedPayment, "failureReason", "서킷브레이커 OPEN");
            when(paymentService.findPayment(1L)).thenReturn(failedPayment);

            // when
            orderPaymentFacade.createOrder(memberId, itemRequests, null, 0, null, cardType, cardNo);

            // then
            assertAll(
                    () -> verify(orderTransactionService, times(1)).updatePaymentStatus(eq(1L), eq(pgResponse)),
                    () -> verify(orderCompensationService, times(1)).compensateFailedOrder(1L),
                    () -> verify(eventPublisher).publishEvent(any(OrderFailedEvent.class))
            );
        }

        @Test
        @DisplayName("주문 생성 성공 후 결제 시스템 예외 시 보상 트랜잭션이 실행되고 예외가 전파된다.")
        void compensatesAndThrowsException_whenPaymentSystemFails() {
            // given
            Long memberId = 1L;
            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(1L, 1L, 2)
            );

            OrderItem orderItem = createOrderItemWithId(1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 2);
            OrderDetailInfo orderInfo = new OrderDetailInfo(
                    1L, 100000, 0, 0, 100000,
                    List.of(OrderItemInfo.from(orderItem)),
                    ZonedDateTime.now());

            when(orderPlacementService.placeOrder(eq(memberId), eq(itemRequests), eq(null), eq(0), eq(null)))
                    .thenReturn(orderInfo);
            when(paymentService.initiatePayment(memberId, 1L, 100000, "SAMSUNG", "1234"))
                    .thenThrow(new RuntimeException("DB 연결 실패"));

            // when & then
            assertThrows(RuntimeException.class,
                    () -> orderPaymentFacade.createOrder(memberId, itemRequests, null, 0, null, "SAMSUNG", "1234"));

            verify(orderCompensationService, times(1)).compensateFailedOrder(1L);
            verify(eventPublisher).publishEvent(any(OrderFailedEvent.class));
        }

        @Test
        @DisplayName("PG 결제 성공(SUCCESS) 시 OrderPaidEvent가 발행된다.")
        void publishesOrderPaidEvent_whenPaymentSucceeds() {
            // given
            Long memberId = 1L;
            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(1L, 1L, 2)
            );
            String cardType = "SAMSUNG";
            String cardNo = "1234-5678-9012-3456";

            OrderItem orderItem = createOrderItemWithId(1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 2);
            OrderDetailInfo orderInfo = new OrderDetailInfo(
                    1L, 100000, 0, 0, 100000,
                    List.of(OrderItemInfo.from(orderItem)),
                    ZonedDateTime.now());

            when(orderPlacementService.placeOrder(eq(memberId), eq(itemRequests), eq(null), eq(0), eq(null)))
                    .thenReturn(orderInfo);

            Payment payment = Payment.create(memberId, 1L, 100000, cardType, cardNo);
            ReflectionTestUtils.setField(payment, "id", 1L);
            when(paymentService.initiatePayment(memberId, 1L, 100000, cardType, cardNo))
                    .thenReturn(payment);

            PaymentGatewayResponse pgResponse = new PaymentGatewayResponse("tx-123", true, false, null);
            when(paymentGateway.requestPayment(eq(memberId), any(Payment.class)))
                    .thenReturn(pgResponse);

            when(orderTransactionService.updatePaymentStatus(eq(1L), eq(pgResponse)))
                    .thenReturn(PaymentStatus.SUCCESS);

            // when
            orderPaymentFacade.createOrder(memberId, itemRequests, null, 0, null, cardType, cardNo);

            // then
            verify(eventPublisher).publishEvent(any(OrderPaidEvent.class));
            verify(orderCompensationService, never()).compensateFailedOrder(anyLong());
        }
    }
}
