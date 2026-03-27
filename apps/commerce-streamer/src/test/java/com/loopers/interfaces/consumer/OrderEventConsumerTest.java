package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.metrics.MetricsEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("OrderEventConsumer 테스트")
@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock
    private MetricsEventService metricsEventService;

    @Mock
    private Acknowledgment acknowledgment;

    private OrderEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new OrderEventConsumer(metricsEventService, new ObjectMapper());
    }

    @Test
    @DisplayName("ORDER_PAID 이벤트 수신 시 서비스에 위임하고 ACK를 보낸다")
    void handleOrderPaid_delegatesToService() {
        // given
        String payload = """
                {
                    "eventId":"uuid-order-1",
                    "eventType":"ORDER_PAID",
                    "data":{
                        "orderId":1,"memberId":100,"totalAmount":50000,
                        "orderedProducts":[{"productId":10,"quantity":2},{"productId":20,"quantity":1}]
                    }
                }
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order-events", 0, 0, "1", payload);

        // when
        consumer.consume(record, acknowledgment);

        // then
        verify(metricsEventService).processOrderPaid(eq("uuid-order-1"), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("서비스 처리 중 예외 발생 시 ACK를 보내지 않는다 (재수신 예정)")
    void doesNotAck_whenServiceFails() {
        // given
        String payload = """
                {"eventId":"uuid-fail","eventType":"ORDER_PAID","data":{"orderId":1,"orderedProducts":[{"productId":10,"quantity":1}]}}
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order-events", 0, 0, "1", payload);
        doThrow(new RuntimeException("DB error")).when(metricsEventService).processOrderPaid(any(), any());

        // when
        consumer.consume(record, acknowledgment);

        // then
        verify(acknowledgment, never()).acknowledge();
    }
}
