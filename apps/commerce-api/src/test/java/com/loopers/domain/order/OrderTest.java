package com.loopers.domain.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Order 도메인 모델 단위 테스트")
class OrderTest {

    private Brand createBrandWithId(Long id, String name) {
        Brand brand = new Brand(name, "스포츠");
        brand.changeStatus(BrandStatus.ACTIVE);
        ReflectionTestUtils.setField(brand, "id", id);
        return brand;
    }

    private OrderItem createOrderItem(Long productId, Long brandId, String productName,
                                       String optionName, String brandName,
                                       int price, int supplyPrice, int shippingFee, int quantity) {
        Brand brand = createBrandWithId(brandId, brandName);
        Product product = new Product(brand, productName, price, supplyPrice, 0, shippingFee,
                "", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y", List.of());
        ReflectionTestUtils.setField(product, "id", productId);

        ProductOption option = new ProductOption(productId, optionName, 100);
        ReflectionTestUtils.setField(option, "id", 1L);

        return OrderItem.createSnapshot(product, option, quantity);
    }

    private OrderItem createOrderItemWithId(Long id, Long productId, Long brandId, String productName,
                                             String optionName, String brandName,
                                             int price, int supplyPrice, int shippingFee, int quantity) {
        OrderItem item = createOrderItem(productId, brandId, productName, optionName, brandName,
                price, supplyPrice, shippingFee, quantity);
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
    @DisplayName("주문을 생성할 때,")
    class Create {

        @Test
        @DisplayName("주문 항목들의 소계 합으로 총 금액이 계산된다.")
        void totalAmountIsCalculatedFromItems() {
            // given
            Long memberId = 1L;
            OrderItem item1 = createOrderItemWithId(1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 2);
            OrderItem item2 = createOrderItemWithId(2L, 2L, 1L, "티셔츠", "M", "나이키",
                    30000, 20000, 2500, 1);
            List<OrderItem> items = List.of(item1, item2);
            int discountAmount = 5000;
            Long memberCouponId = 10L;
            int usedPoints = 1000;

            // when
            Order order = Order.create(memberId, items, discountAmount, memberCouponId, usedPoints);

            // then
            assertAll(
                    () -> assertThat(order.getMemberId()).isEqualTo(memberId),
                    () -> assertThat(order.getTotalAmount()).isEqualTo(130000),
                    () -> assertThat(order.getDiscountAmount()).isEqualTo(5000),
                    () -> assertThat(order.getMemberCouponId()).isEqualTo(10L),
                    () -> assertThat(order.getUsedPoints()).isEqualTo(1000),
                    () -> assertThat(order.getOrderItems()).hasSize(2)
            );
        }
    }

    @Nested
    @DisplayName("총 금액을 계산할 때,")
    class CalculateTotalAmount {

        @Test
        @DisplayName("주문 항목들의 소계(가격 x 수량) 합을 반환한다.")
        void returnsSumOfItemSubtotals() {
            // given
            OrderItem item1 = createOrderItemWithId(1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 2);
            OrderItem item2 = createOrderItemWithId(2L, 2L, 1L, "티셔츠", "M", "나이키",
                    30000, 20000, 2500, 3);
            List<OrderItem> items = List.of(item1, item2);

            // when
            int totalAmount = Order.calculateTotalAmount(items);

            // then
            assertThat(totalAmount).isEqualTo(50000 * 2 + 30000 * 3);
        }
    }

    @Nested
    @DisplayName("결제 금액을 조회할 때,")
    class GetPaymentAmount {

        @Test
        @DisplayName("총 금액에서 할인 금액과 사용 포인트를 뺀 값을 반환한다.")
        void returnsTotalMinusDiscountMinusPoints() {
            // given
            OrderItem item = createOrderItemWithId(1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 2);
            Order order = createOrderWithId(1L, 1L, List.of(item),
                    100000, 5000, 10L, 3000);

            // when
            int paymentAmount = order.getPaymentAmount();

            // then
            assertThat(paymentAmount).isEqualTo(100000 - 5000 - 3000);
        }
    }

    @Nested
    @DisplayName("결제 가능 여부를 검증할 때,")
    class ValidatePayable {

        @Test
        @DisplayName("PENDING 상태면 예외가 발생하지 않는다.")
        void passes_whenPending() {
            // given
            Order order = createOrderWithId(1L, 1L,
                    List.of(createOrderItem(1L, 1L, "상품", "옵션", "브랜드", 10000, 8000, 0, 1)),
                    10000, 0, null, 0);

            // when & then
            order.validatePayable();
        }

        @Test
        @DisplayName("FAILED 상태면 CONFLICT 예외가 발생한다.")
        void throwsConflict_whenFailed() {
            // given
            Order order = createOrderWithId(1L, 1L,
                    List.of(createOrderItem(1L, 1L, "상품", "옵션", "브랜드", 10000, 8000, 0, 1)),
                    10000, 0, null, 0);
            order.markFailed();

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> order.validatePayable());

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }

        @Test
        @DisplayName("PAID 상태면 CONFLICT 예외가 발생한다.")
        void throwsConflict_whenPaid() {
            // given
            Order order = createOrderWithId(1L, 1L,
                    List.of(createOrderItem(1L, 1L, "상품", "옵션", "브랜드", 10000, 8000, 0, 1)),
                    10000, 0, null, 0);
            order.markPaid();

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> order.validatePayable());

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }

        @Test
        @DisplayName("CANCELLED 상태면 CONFLICT 예외가 발생한다.")
        void throwsConflict_whenCancelled() {
            // given
            Order order = createOrderWithId(1L, 1L,
                    List.of(createOrderItem(1L, 1L, "상품", "옵션", "브랜드", 10000, 8000, 0, 1)),
                    10000, 0, null, 0);
            order.cancel();

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> order.validatePayable());

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }
    }

    @Nested
    @DisplayName("주문 소유자를 검증할 때,")
    class ValidateOwner {

        @Test
        @DisplayName("일치하는 회원 ID면 예외가 발생하지 않는다.")
        void passes_whenMemberIdMatches() {
            // given
            Order order = createOrderWithId(1L, 1L,
                    List.of(createOrderItem(1L, 1L, "상품", "옵션", "브랜드", 100000, 80000, 0, 1)),
                    100000, 0, null, 0);

            // when & then
            order.validateOwner(1L);
        }

        @Test
        @DisplayName("일치하지 않는 회원 ID면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenMemberIdDoesNotMatch() {
            // given
            Order order = createOrderWithId(1L, 1L,
                    List.of(createOrderItem(1L, 1L, "상품", "옵션", "브랜드", 100000, 80000, 0, 1)),
                    100000, 0, null, 0);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> order.validateOwner(999L));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
