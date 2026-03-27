package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.metrics.MetricsEventService;
import com.loopers.config.MetricsKafkaConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * order-events 토픽의 Consumer.
 *
 * {@code OrderPaidEvent}를 수신하여 {@code product_metrics} 테이블의
 * {@code order_count}를 상품별로 집계한다.
 *
 * 멱등 체크는 {@link MetricsEventService} 내부에서 수행 (TOCTOU 방지).
 * ACK는 서비스 메서드 리턴(TX 커밋 완료) 후 전송.
 *
 * @see MetricsEventService
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OrderEventConsumer {

    private final MetricsEventService metricsEventService;
    private final ObjectMapper objectMapper;

    /**
     * order-events 토픽의 메시지를 단건으로 수신하여 처리한다.
     *
     * @param record         Kafka 메시지 (key=orderId, value=JSON payload)
     * @param acknowledgment Manual ACK
     */
    @KafkaListener(
            topics = "order-events",
            groupId = "metrics-group",
            containerFactory = MetricsKafkaConfig.SINGLE_LISTENER
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            JsonNode payload = objectMapper.readTree(record.value());

            String eventId = payload.path("eventId").asText(null);
            String eventType = payload.path("eventType").asText(null);

            if (eventId == null || eventType == null) {
                log.warn("order-events: eventId 또는 eventType 누락 — skip. value={}", record.value());
                acknowledgment.acknowledge();
                return;
            }

            // 멱등 체크 + TX 관리 모두 서비스 내부에서 수행
            if ("ORDER_PAID".equals(eventType)) {
                metricsEventService.processOrderPaid(eventId, payload.path("data"));
            } else {
                log.warn("order-events: 알 수 없는 eventType={} — skip", eventType);
            }

            // TX 커밋 완료 후 ACK 전송
            acknowledgment.acknowledge();
            log.info("order-events 처리 완료: eventId={}, eventType={}", eventId, eventType);
        } catch (JsonProcessingException e) {
            log.error("order-events: JSON 파싱 실패 — skip. value={}", record.value(), e);
            acknowledgment.acknowledge();
        } catch (DataIntegrityViolationException e) {
            log.info("order-events: 중복 이벤트 감지 — ACK 전송. key={}", record.key());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("order-events: 처리 중 예외 — ACK 미전송 (재수신 예정). key={}", record.key(), e);
        }
    }
}
