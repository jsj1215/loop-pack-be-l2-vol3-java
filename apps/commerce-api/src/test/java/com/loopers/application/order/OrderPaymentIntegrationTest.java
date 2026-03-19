package com.loopers.application.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderService.OrderItemRequest;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductService;
import com.loopers.infrastructure.payment.ManualPgApiClient;
import com.loopers.infrastructure.payment.PgFeignClient;
import com.loopers.infrastructure.payment.PgPaymentRequest;
import com.loopers.infrastructure.payment.PgPaymentResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [통합 테스트 - 주문 + 결제 연동]
 *
 * 테스트 대상: OrderPaymentFacade.createOrder() → OrderPlacementService + PaymentGateway
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: 주문 생성(트랜잭션 1) → PG 결제 요청(트랜잭션 밖) → 결제 상태 업데이트(트랜잭션 2)
 *
 * PG 외부 호출(PgFeignClient)만 Mock 처리하고, 나머지는 실제 DB로 검증한다.
 */
@SpringBootTest
@DisplayName("주문 + 결제 연동 통합 테스트")
class OrderPaymentIntegrationTest {

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private BrandService brandService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @MockitoBean
    private PgFeignClient pgFeignClient;

    @MockitoBean
    private ManualPgApiClient manualPgApiClient;

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

    @Nested
    @DisplayName("주문 생성 + 결제 요청 시,")
    class CreateOrderWithPayment {

        @Test
        @DisplayName("PG 접수 성공: 주문은 PENDING, 결제는 PENDING 상태가 된다.")
        void orderPendingAndPaymentPending_whenPgAccepted() {
            // given
            Product product = createProductWithStock("나이키", "에어맥스", 100000, 50);
            Long optionId = product.getOptions().get(0).getId();
            Long memberId = 1L;

            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(product.getId(), optionId, 2)
            );

            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(new PgPaymentResponse("tx-abc123", null, "ACCEPTED", null));

            // when
            OrderDetailInfo result = orderPaymentFacade.createOrder(
                    memberId, itemRequests, null, 0, null, "SAMSUNG", "1234-5678-9012-3456");

            // then
            Order order = orderRepository.findById(result.id()).orElseThrow();
            Optional<Payment> payment = paymentRepository.findByOrderId(result.id());

            assertAll(
                    () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING),
                    () -> assertThat(order.getTotalAmount()).isEqualTo(200000),
                    () -> assertThat(order.getPaymentAmount()).isEqualTo(200000),
                    () -> assertThat(payment).isPresent(),
                    () -> assertThat(payment.get().getStatus()).isEqualTo(PaymentStatus.PENDING),
                    () -> assertThat(payment.get().getPgTransactionId()).isEqualTo("tx-abc123"),
                    () -> assertThat(payment.get().getAmount()).isEqualTo(200000)
            );
        }

        @Test
        @DisplayName("PG 접수 실패: 주문은 FAILED(보상 처리), 결제는 FAILED 상태가 된다.")
        void orderFailedAndPaymentFailed_whenPgRejected() {
            // given
            Product product = createProductWithStock("아디다스", "울트라부스트", 180000, 30);
            Long optionId = product.getOptions().get(0).getId();
            Long memberId = 2L;

            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(product.getId(), optionId, 1)
            );

            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(PgPaymentResponse.failed("카드 한도 초과"));

            // when
            OrderDetailInfo result = orderPaymentFacade.createOrder(
                    memberId, itemRequests, null, 0, null, "HYUNDAI", "9876-5432-1098-7654");

            // then
            Order order = orderRepository.findById(result.id()).orElseThrow();
            Optional<Payment> payment = paymentRepository.findByOrderId(result.id());
            ProductOption option = productService.findOptionById(optionId);

            assertAll(
                    () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED),
                    () -> assertThat(payment).isPresent(),
                    () -> assertThat(payment.get().getStatus()).isEqualTo(PaymentStatus.FAILED),
                    () -> assertThat(payment.get().getFailureReason()).isEqualTo("카드 한도 초과"),
                    () -> assertThat(option.getStockQuantity()).isEqualTo(30) // 재고 복원 확인
            );
        }

        @Test
        @DisplayName("PG 타임아웃: 주문은 PENDING, 결제는 UNKNOWN 상태가 된다.")
        void orderPendingAndPaymentUnknown_whenPgTimeout() {
            // given
            Product product = createProductWithStock("뉴발란스", "993", 250000, 20);
            Long optionId = product.getOptions().get(0).getId();
            Long memberId = 3L;

            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(product.getId(), optionId, 1)
            );

            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(PgPaymentResponse.timeout());

            // when
            OrderDetailInfo result = orderPaymentFacade.createOrder(
                    memberId, itemRequests, null, 0, null, "SAMSUNG", "1111-2222-3333-4444");

            // then
            Order order = orderRepository.findById(result.id()).orElseThrow();
            Optional<Payment> payment = paymentRepository.findByOrderId(result.id());

            assertAll(
                    () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING),
                    () -> assertThat(payment).isPresent(),
                    () -> assertThat(payment.get().getStatus()).isEqualTo(PaymentStatus.UNKNOWN)
            );
        }

        @Test
        @DisplayName("재고가 정상적으로 차감된다.")
        void deductsStock_whenOrderCreated() {
            // given
            Product product = createProductWithStock("나이키", "덩크로우", 120000, 10);
            Long optionId = product.getOptions().get(0).getId();
            Long memberId = 4L;

            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(product.getId(), optionId, 3)
            );

            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenReturn(new PgPaymentResponse("tx-stock-test", null, "ACCEPTED", null));

            // when
            orderPaymentFacade.createOrder(memberId, itemRequests, null, 0, null, "SAMSUNG", "1234-5678-9012-3456");

            // then
            ProductOption option = productService.findOptionById(optionId);
            assertThat(option.getStockQuantity()).isEqualTo(7);
        }

        @Test
        @DisplayName("PG 호출 중 예외 발생 시 주문은 FAILED(보상 처리), 결제는 FAILED 상태가 된다.")
        void orderFailed_whenPgThrowsException() {
            // given
            Product product = createProductWithStock("푸마", "스웨이드", 90000, 15);
            Long optionId = product.getOptions().get(0).getId();
            Long memberId = 5L;

            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(product.getId(), optionId, 1)
            );

            when(pgFeignClient.requestPayment(anyString(), any(PgPaymentRequest.class)))
                    .thenThrow(new RuntimeException("PG 서버 연결 불가"));

            // when
            OrderDetailInfo result = orderPaymentFacade.createOrder(
                    memberId, itemRequests, null, 0, null, "SAMSUNG", "5555-6666-7777-8888");

            // then
            Order order = orderRepository.findById(result.id()).orElseThrow();
            Optional<Payment> payment = paymentRepository.findByOrderId(result.id());
            ProductOption option = productService.findOptionById(optionId);

            assertAll(
                    () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED),
                    () -> assertThat(payment).isPresent(),
                    () -> assertThat(payment.get().getStatus()).isEqualTo(PaymentStatus.FAILED),
                    () -> assertThat(option.getStockQuantity()).isEqualTo(15) // 재고 복원 확인
            );
        }
    }
}
