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
 * catalog-events 토픽의 Consumer.
 *
 * {@code ProductLikedEvent}와 {@code ProductViewedEvent}를 수신하여
 * {@code product_metrics} 테이블의 {@code like_count}, {@code view_count}를 집계한다.
 *
 * 멱등 처리 위치:
 * 멱등 체크(eventId 중복 확인)는 이 Consumer가 아닌 {@link MetricsEventService}의
 * {@code @Transactional} 내부에서 수행한다.
 *
 * 초기에는 Consumer에서 {@code existsByEventId()}로 체크한 뒤 서비스를 호출했으나,
 * Consumer의 체크(TX 밖)와 서비스의 처리(TX 안) 사이에 TOCTOU 갭이 존재하여
 * 동시 수신 시 두 스레드 모두 체크를 통과해 중복 집계되는 문제가 있었다.
 * 이에 멱등 체크를 서비스 내부로 이동하고, {@code event_handled} PK UNIQUE 제약으로 최종 방어한다.
 *
 * @see MetricsEventService
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class CatalogEventConsumer {

    private final MetricsEventService metricsEventService;
    private final ObjectMapper objectMapper;

    /**
     * catalog-events 토픽의 메시지를 단건으로 수신하여 처리한다.
     *
     * Partition Key = productId → 같은 상품의 이벤트는 같은 파티션에서 순서대로 처리.
     * 멱등 체크와 비즈니스 로직은 {@link MetricsEventService}에 위임 (TX 내부 체크).
     * ACK는 서비스 메서드 리턴(TX 커밋 완료) 후 전송.
     */
    @KafkaListener(
            topics = "catalog-events",
            groupId = "metrics-group",
            containerFactory = MetricsKafkaConfig.SINGLE_LISTENER
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            JsonNode payload = objectMapper.readTree(record.value());

            String eventId = payload.path("eventId").asText(null);
            String eventType = payload.path("eventType").asText(null);

            if (eventId == null || eventType == null) {
                log.warn("catalog-events: eventId 또는 eventType 누락 — skip. value={}", record.value());
                acknowledgment.acknowledge();
                return;
            }

            // 이벤트 유형별 처리 — 멱등 체크 + TX 관리 모두 서비스 내부에서 수행
            JsonNode data = payload.path("data");
            switch (eventType) {
                case "PRODUCT_LIKED" -> metricsEventService.processProductLiked(eventId, data);
                case "PRODUCT_VIEWED" -> metricsEventService.processProductViewed(eventId, data);
                default -> log.warn("catalog-events: 알 수 없는 eventType={} — skip", eventType);
            }

            // TX 커밋 완료 후 ACK 전송
            acknowledgment.acknowledge();
            log.info("catalog-events 처리 완료: eventId={}, eventType={}", eventId, eventType);
        } catch (JsonProcessingException e) {
            log.error("catalog-events: JSON 파싱 실패 — skip. value={}", record.value(), e);
            acknowledgment.acknowledge();
        } catch (DataIntegrityViolationException e) {
            // event_handled UNIQUE 제약 위반 — 다른 스레드가 먼저 처리 완료, 이 TX는 롤백됨
            log.info("catalog-events: 중복 이벤트 감지 — ACK 전송. key={}", record.key());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("catalog-events: 처리 중 예외 — ACK 미전송 (재수신 예정). key={}", record.key(), e);
        }
    }
}
