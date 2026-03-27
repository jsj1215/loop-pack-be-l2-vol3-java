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

@DisplayName("CatalogEventConsumer 테스트")
@ExtendWith(MockitoExtension.class)
class CatalogEventConsumerTest {

    @Mock
    private MetricsEventService metricsEventService;

    @Mock
    private Acknowledgment acknowledgment;

    private CatalogEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new CatalogEventConsumer(metricsEventService, new ObjectMapper());
    }

    @Test
    @DisplayName("PRODUCT_LIKED 이벤트 수신 시 서비스에 위임하고 ACK를 보낸다")
    void handleProductLiked_delegatesToService() {
        // given
        String payload = """
                {"eventId":"uuid-1","eventType":"PRODUCT_LIKED","data":{"productId":10,"memberId":100,"liked":true}}
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>("catalog-events", 0, 0, "10", payload);

        // when
        consumer.consume(record, acknowledgment);

        // then
        verify(metricsEventService).processProductLiked(eq("uuid-1"), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("PRODUCT_VIEWED 이벤트 수신 시 서비스에 위임하고 ACK를 보낸다")
    void handleProductViewed_delegatesToService() {
        // given
        String payload = """
                {"eventId":"uuid-3","eventType":"PRODUCT_VIEWED","data":{"productId":10}}
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>("catalog-events", 0, 0, "10", payload);

        // when
        consumer.consume(record, acknowledgment);

        // then
        verify(metricsEventService).processProductViewed(eq("uuid-3"), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("서비스 처리 중 예외 발생 시 ACK를 보내지 않는다 (재수신 예정)")
    void doesNotAck_whenServiceFails() {
        // given
        String payload = """
                {"eventId":"uuid-fail","eventType":"PRODUCT_LIKED","data":{"productId":10,"memberId":100,"liked":true}}
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>("catalog-events", 0, 0, "10", payload);
        doThrow(new RuntimeException("DB error")).when(metricsEventService).processProductLiked(any(), any());

        // when
        consumer.consume(record, acknowledgment);

        // then
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("eventId가 누락된 메시지는 skip하고 ACK를 보낸다")
    void skipMessage_whenEventIdMissing() {
        // given
        String payload = """
                {"eventType":"PRODUCT_LIKED","data":{"productId":10}}
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>("catalog-events", 0, 0, "10", payload);

        // when
        consumer.consume(record, acknowledgment);

        // then
        verify(metricsEventService, never()).processProductLiked(any(), any());
        verify(acknowledgment).acknowledge();
    }
}
