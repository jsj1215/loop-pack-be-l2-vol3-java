package com.loopers.application.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.event.KafkaPayloadSerializer;
import com.loopers.domain.event.OrderPaidEvent;
import com.loopers.domain.event.ProductLikedEvent;
import com.loopers.domain.event.ProductViewedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("KafkaEventPublishListener 테스트")
@ExtendWith(MockitoExtension.class)
class KafkaEventPublishListenerTest {

    @Mock
    private KafkaTemplate<Object, Object> kafkaTemplate;

    private KafkaEventPublishListener listener;

    @BeforeEach
    void setUp() {
        listener = new KafkaEventPublishListener(kafkaTemplate, new KafkaPayloadSerializer(new ObjectMapper()));
    }

    @Nested
    @DisplayName("ProductLikedEvent 발행")
    class PublishProductLiked {

        @Test
        @DisplayName("catalog-events 토픽에 productId를 key로 발행한다")
        void publishesToCatalogEvents() {
            // given
            ProductLikedEvent event = new ProductLikedEvent(10L, 1L, true);

            // when
            listener.publishProductLiked(event);

            // then
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(kafkaTemplate).send(eq("catalog-events"), eq("10"), payloadCaptor.capture());

            String payload = payloadCaptor.getValue();
            assertThat(payload).contains("\"eventType\":\"PRODUCT_LIKED\"");
            assertThat(payload).contains("\"productId\":10");
            assertThat(payload).contains("\"memberId\":1");
            assertThat(payload).contains("\"liked\":true");
            assertThat(payload).contains("\"eventId\"");
        }

        @Test
        @DisplayName("Kafka 발행 실패 시 예외가 전파되지 않는다 (통계성 이벤트, 유실 허용)")
        void swallowsException_whenKafkaFails() {
            // given
            ProductLikedEvent event = new ProductLikedEvent(10L, 1L, true);
            doThrow(new RuntimeException("Kafka unavailable"))
                    .when(kafkaTemplate).send(anyString(), anyString(), any());

            // when — 예외가 전파되지 않아야 한다
            listener.publishProductLiked(event);

            // then
            verify(kafkaTemplate).send(anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("ProductViewedEvent 발행")
    class PublishProductViewed {

        @Test
        @DisplayName("catalog-events 토픽에 productId를 key로 발행한다")
        void publishesToCatalogEvents() {
            // given
            ProductViewedEvent event = new ProductViewedEvent(10L, null);

            // when
            listener.publishProductViewed(event);

            // then
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(kafkaTemplate).send(eq("catalog-events"), eq("10"), payloadCaptor.capture());

            String payload = payloadCaptor.getValue();
            assertThat(payload).contains("\"eventType\":\"PRODUCT_VIEWED\"");
            assertThat(payload).contains("\"productId\":10");
        }

        @Test
        @DisplayName("Kafka 발행 실패 시 예외가 전파되지 않는다")
        void swallowsException_whenKafkaFails() {
            // given
            ProductViewedEvent event = new ProductViewedEvent(10L, null);
            doThrow(new RuntimeException("Kafka unavailable"))
                    .when(kafkaTemplate).send(anyString(), anyString(), any());

            // when
            listener.publishProductViewed(event);

            // then
            verify(kafkaTemplate).send(anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("OrderPaidEvent 발행")
    class PublishOrderPaid {

        @Test
        @DisplayName("order-events 토픽에 orderId를 key로 발행한다")
        void publishesToOrderEvents() {
            // given
            OrderPaidEvent event = new OrderPaidEvent(1L, 100L, 50000,
                    List.of(new OrderPaidEvent.OrderedProduct(10L, 2)));

            // when
            listener.publishOrderPaid(event);

            // then
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(kafkaTemplate).send(eq("order-events"), eq("1"), payloadCaptor.capture());

            String payload = payloadCaptor.getValue();
            assertThat(payload).contains("\"eventType\":\"ORDER_PAID\"");
            assertThat(payload).contains("\"orderId\":1");
            assertThat(payload).contains("\"memberId\":100");
            assertThat(payload).contains("\"totalAmount\":50000");
            assertThat(payload).contains("\"orderedProducts\"");
        }

        @Test
        @DisplayName("Kafka 발행 실패 시 예외가 전파되지 않는다")
        void swallowsException_whenKafkaFails() {
            // given
            OrderPaidEvent event = new OrderPaidEvent(1L, 100L, 50000, List.of());
            doThrow(new RuntimeException("Kafka unavailable"))
                    .when(kafkaTemplate).send(anyString(), anyString(), any());

            // when
            listener.publishOrderPaid(event);

            // then
            verify(kafkaTemplate).send(anyString(), anyString(), any());
        }
    }
}
