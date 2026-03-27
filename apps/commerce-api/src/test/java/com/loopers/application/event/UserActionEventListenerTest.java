package com.loopers.application.event;

import com.loopers.domain.event.OrderFailedEvent;
import com.loopers.domain.event.OrderPaidEvent;
import com.loopers.domain.event.ProductLikedEvent;
import com.loopers.domain.event.ProductViewedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * UserActionEventListener 단위 테스트.
 *
 * 검증 대상:
 * - 모든 유저 행동 이벤트(주문, 좋아요, 조회)를 수신하여 예외 없이 로깅 처리
 * - 각 이벤트 핸들러가 독립적으로 동작하는지 검증
 */
@DisplayName("UserActionEventListener 단위 테스트")
class UserActionEventListenerTest {

    private final UserActionEventListener listener = new UserActionEventListener();

    @Nested
    @DisplayName("주문 관련 행동 로깅")
    class OrderActions {

        @Test
        @DisplayName("ORDER_PAID 이벤트를 수신하면 예외 없이 로깅한다")
        void logsOrderPaid() {
            // given
            OrderPaidEvent event = new OrderPaidEvent(
                    1L, 100L, 50000,
                    List.of(new OrderPaidEvent.OrderedProduct(10L, 2))
            );

            // when & then
            assertThatCode(() -> listener.logOrderPaid(event))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ORDER_FAILED 이벤트를 수신하면 예외 없이 로깅한다")
        void logsOrderFailed() {
            // given
            OrderFailedEvent event = new OrderFailedEvent(1L, 100L, "잔액 부족");

            // when & then
            assertThatCode(() -> listener.logOrderFailed(event))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("상품 관련 행동 로깅")
    class ProductActions {

        @Test
        @DisplayName("PRODUCT_LIKED 이벤트를 수신하면 예외 없이 로깅한다")
        void logsProductLiked() {
            // given
            ProductLikedEvent event = new ProductLikedEvent(10L, 100L, true);

            // when & then
            assertThatCode(() -> listener.logProductLiked(event))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("PRODUCT_UNLIKED 이벤트를 수신하면 예외 없이 로깅한다")
        void logsProductUnliked() {
            // given
            ProductLikedEvent event = new ProductLikedEvent(10L, 100L, false);

            // when & then
            assertThatCode(() -> listener.logProductLiked(event))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("PRODUCT_VIEWED 이벤트를 수신하면 예외 없이 로깅한다")
        void logsProductViewed() {
            // given
            ProductViewedEvent event = new ProductViewedEvent(10L, 100L);

            // when & then
            assertThatCode(() -> listener.logProductViewed(event))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("비로그인 사용자의 상품 조회도 예외 없이 로깅한다")
        void logsProductViewed_withNullMemberId() {
            // given
            ProductViewedEvent event = new ProductViewedEvent(10L, null);

            // when & then
            assertThatCode(() -> listener.logProductViewed(event))
                    .doesNotThrowAnyException();
        }
    }
}
