package com.loopers.application.event;

import com.loopers.domain.event.OrderFailedEvent;
import com.loopers.domain.event.OrderPaidEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * OrderEventListener 단위 테스트.
 *
 * 검증 대상:
 * - 주문 결제 성공/실패 이벤트를 수신하여 예외 없이 처리
 * - 로깅 전용 리스너이므로 부가 로직이 메인 흐름에 영향을 주지 않는지 검증
 */
@DisplayName("OrderEventListener 단위 테스트")
class OrderEventListenerTest {

    private final OrderEventListener listener = new OrderEventListener();

    @Nested
    @DisplayName("주문 결제 성공 이벤트")
    class HandleOrderPaid {

        @Test
        @DisplayName("OrderPaidEvent를 수신하면 예외 없이 처리한다")
        void handlesOrderPaidEvent() {
            // given
            OrderPaidEvent event = new OrderPaidEvent(
                    1L, 100L, 50000,
                    List.of(new OrderPaidEvent.OrderedProduct(10L, 2))
            );

            // when & then
            assertThatCode(() -> listener.handleOrderPaid(event))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("주문 결제 실패 이벤트")
    class HandleOrderFailed {

        @Test
        @DisplayName("OrderFailedEvent를 수신하면 예외 없이 처리한다")
        void handlesOrderFailedEvent() {
            // given
            OrderFailedEvent event = new OrderFailedEvent(1L, 100L, "PG 결제 실패");

            // when & then
            assertThatCode(() -> listener.handleOrderFailed(event))
                    .doesNotThrowAnyException();
        }
    }
}
