package com.loopers.application.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.cart.CartService;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.CouponService;
import com.loopers.domain.coupon.DiscountType;
import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderService.OrderItemRequest;
import com.loopers.domain.point.PointService;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderPlacementService 단위 테스트")
class OrderPlacementServiceTest {

    @Mock
    private OrderService orderService;

    @Mock
    private CouponService couponService;

    @Mock
    private PointService pointService;

    @Mock
    private CartService cartService;

    @Mock
    private ProductService productService;

    @InjectMocks
    private OrderPlacementService orderPlacementService;

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

    private OrderItem createOrderItemWithId(Long id, Long productId, Long brandId,
                                             String productName, String optionName, String brandName,
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

    private Coupon createCouponWithId(Long id, String name, CouponScope scope, Long targetId,
                                      DiscountType discountType, int discountValue,
                                      int minOrderAmount, int maxDiscountAmount,
                                      ZonedDateTime validFrom, ZonedDateTime validTo) {
        Coupon coupon = new Coupon(name, scope, targetId, discountType, discountValue,
                minOrderAmount, maxDiscountAmount, validFrom, validTo);
        ReflectionTestUtils.setField(coupon, "id", id);
        return coupon;
    }

    private Product createProductWithId(Long id, Long brandId, String brandName, String name, int price) {
        Brand brand = createBrandWithId(brandId, brandName);
        Product product = new Product(brand, name, price, price - 10000, 0, 3000,
                "", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y", List.of());
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private MemberCoupon createMemberCouponWithId(Long id, Long memberId, Long couponId) {
        MemberCoupon memberCoupon = new MemberCoupon(memberId, couponId);
        ReflectionTestUtils.setField(memberCoupon, "id", id);
        return memberCoupon;
    }

    @Nested
    @DisplayName("주문을 생성할 때,")
    class PlaceOrder {

        @Test
        @DisplayName("쿠폰 없음, 포인트 없음: 기본 주문이 PENDING 상태로 생성된다.")
        void createsBasicOrder_whenNoCouponAndNoPoints() {
            // given
            Long memberId = 1L;
            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(1L, 1L, 2)
            );

            OrderItem orderItem = createOrderItemWithId(1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 2);
            List<OrderItem> orderItems = List.of(orderItem);

            Order savedOrder = createOrderWithId(1L, memberId, orderItems,
                    100000, 0, null, 0);

            when(orderService.prepareOrderItems(itemRequests)).thenReturn(orderItems);
            when(orderService.createOrder(eq(memberId), eq(orderItems), eq(0), eq(null), eq(0)))
                    .thenReturn(savedOrder);

            // when
            OrderDetailInfo result = orderPlacementService.placeOrder(memberId, itemRequests, null, 0, null);

            // then
            assertAll(
                    () -> assertThat(result.id()).isEqualTo(1L),
                    () -> assertThat(result.totalAmount()).isEqualTo(100000),
                    () -> assertThat(result.discountAmount()).isEqualTo(0),
                    () -> assertThat(result.usedPoints()).isEqualTo(0),
                    () -> verify(couponService, never()).getMemberCoupon(anyLong()),
                    () -> verify(pointService, never()).usePoint(anyLong(), anyInt(), anyString(), anyLong()),
                    () -> verify(cartService, never()).deleteByMemberIdAndProductOptionIds(anyLong(), any())
            );
        }

        @Test
        @DisplayName("CART scope 쿠폰 사용: 총 금액 기준으로 할인이 적용된다.")
        void appliesCartScopeCouponDiscount() {
            // given
            Long memberId = 1L;
            Long memberCouponId = 10L;
            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(1L, 1L, 2)
            );

            Product product = createProductWithId(1L, 1L, "나이키", "운동화", 50000);

            OrderItem orderItem = createOrderItemWithId(1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 2);
            List<OrderItem> orderItems = List.of(orderItem);

            MemberCoupon memberCoupon = createMemberCouponWithId(memberCouponId, memberId, 100L);

            Coupon coupon = createCouponWithId(100L, "장바구니 쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 5000, 0, 0,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));

            Order savedOrder = createOrderWithId(1L, memberId, orderItems,
                    100000, 5000, memberCouponId, 0);

            when(productService.findProductOnly(1L)).thenReturn(product);
            when(couponService.getMemberCoupon(memberCouponId)).thenReturn(memberCoupon);
            when(couponService.findById(100L)).thenReturn(coupon);
            when(couponService.calculateCouponDiscount(eq(memberId), eq(memberCouponId), eq(100000)))
                    .thenReturn(5000);
            when(orderService.prepareOrderItems(itemRequests)).thenReturn(orderItems);
            when(orderService.createOrder(eq(memberId), eq(orderItems), eq(5000), eq(memberCouponId), eq(0)))
                    .thenReturn(savedOrder);

            // when
            OrderDetailInfo result = orderPlacementService.placeOrder(memberId, itemRequests, memberCouponId, 0, null);

            // then
            assertAll(
                    () -> assertThat(result.discountAmount()).isEqualTo(5000),
                    () -> verify(couponService, times(1)).calculateCouponDiscount(memberId, memberCouponId, 100000),
                    () -> verify(couponService, times(1)).useCoupon(memberId, memberCouponId, 1L)
            );
        }

        @Test
        @DisplayName("포인트 사용: pointService.usePoint가 호출된다.")
        void callsUsePoint_whenPointsUsed() {
            // given
            Long memberId = 1L;
            int usedPoints = 5000;
            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(1L, 1L, 1)
            );

            OrderItem orderItem = createOrderItemWithId(1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 1);
            List<OrderItem> orderItems = List.of(orderItem);

            Order savedOrder = createOrderWithId(1L, memberId, orderItems,
                    50000, 0, null, usedPoints);

            when(orderService.prepareOrderItems(itemRequests)).thenReturn(orderItems);
            when(orderService.createOrder(eq(memberId), eq(orderItems), eq(0), eq(null), eq(usedPoints)))
                    .thenReturn(savedOrder);

            // when
            orderPlacementService.placeOrder(memberId, itemRequests, null, usedPoints, null);

            // then
            verify(pointService, times(1)).usePoint(memberId, usedPoints, "주문 사용", 1L);
        }

        @Test
        @DisplayName("장바구니에서 주문: cartService.deleteByMemberIdAndProductOptionIds가 호출된다.")
        void deletesCartItems_whenOrderedFromCart() {
            // given
            Long memberId = 1L;
            List<Long> cartOptionIds = List.of(1L, 2L);
            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(1L, 1L, 2)
            );

            OrderItem orderItem = createOrderItemWithId(1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 2);
            List<OrderItem> orderItems = List.of(orderItem);

            Order savedOrder = createOrderWithId(1L, memberId, orderItems,
                    100000, 0, null, 0);

            when(orderService.prepareOrderItems(itemRequests)).thenReturn(orderItems);
            when(orderService.createOrder(eq(memberId), eq(orderItems), eq(0), eq(null), eq(0)))
                    .thenReturn(savedOrder);

            // when
            orderPlacementService.placeOrder(memberId, itemRequests, null, 0, cartOptionIds);

            // then
            verify(cartService, times(1)).deleteByMemberIdAndProductOptionIds(memberId, cartOptionIds);
        }

        @Test
        @DisplayName("쿠폰 서비스 오류가 전파된다.")
        void propagatesCouponServiceError() {
            // given
            Long memberId = 1L;
            Long memberCouponId = 10L;
            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(1L, 1L, 2)
            );

            when(couponService.getMemberCoupon(memberCouponId))
                    .thenThrow(new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> orderPlacementService.placeOrder(memberId, itemRequests, memberCouponId, 0, null));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND),
                    () -> verify(orderService, never()).prepareOrderItems(any())
            );
        }

        @Test
        @DisplayName("포인트 부족 오류가 전파된다.")
        void propagatesPointServiceError() {
            // given
            Long memberId = 1L;
            int usedPoints = 30000; // 총액(50000) 이하이지만 실제 보유 포인트보다 큰 경우
            List<OrderItemRequest> itemRequests = List.of(
                    new OrderItemRequest(1L, 1L, 1)
            );

            OrderItem orderItem = createOrderItemWithId(1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 1);
            List<OrderItem> orderItems = List.of(orderItem);

            Order savedOrder = createOrderWithId(1L, memberId, orderItems,
                    50000, 0, null, usedPoints);

            when(orderService.prepareOrderItems(itemRequests)).thenReturn(orderItems);
            when(orderService.createOrder(eq(memberId), eq(orderItems), eq(0), eq(null), eq(usedPoints)))
                    .thenReturn(savedOrder);
            doThrow(new CoreException(ErrorType.BAD_REQUEST, "포인트가 부족합니다."))
                    .when(pointService).usePoint(memberId, usedPoints, "주문 사용", 1L);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> orderPlacementService.placeOrder(memberId, itemRequests, null, usedPoints, null));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}
