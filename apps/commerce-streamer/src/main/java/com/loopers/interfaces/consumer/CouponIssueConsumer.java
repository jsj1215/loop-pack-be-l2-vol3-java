package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.CouponIssueEventService;
import com.loopers.config.MetricsKafkaConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * coupon-issue-requests 토픽의 Consumer.
 *
 * 선착순 쿠폰 발급 요청을 수신하여 수량 체크 후 승인/거부를 결정한다.
 * Partition Key = couponId → 같은 쿠폰의 발급 요청은 순차 처리되어 race condition이 없다.
 *
 * 멱등 처리는 {@link CouponIssueEventService} 내부에서 수행 (TOCTOU 방지).
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class CouponIssueConsumer {

    private final CouponIssueEventService couponIssueEventService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "coupon-issue-requests",
            groupId = "coupon-issue-group",
            containerFactory = MetricsKafkaConfig.SINGLE_LISTENER
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            JsonNode payload = objectMapper.readTree(record.value());

            String eventId = payload.path("eventId").asText(null);
            String eventType = payload.path("eventType").asText(null);

            if (eventId == null || eventType == null) {
                log.warn("coupon-issue-requests: eventId 또는 eventType 누락 — skip. value={}", record.value());
                acknowledgment.acknowledge();
                return;
            }

            if (!"COUPON_ISSUE_REQUESTED".equals(eventType)) {
                log.warn("coupon-issue-requests: 알 수 없는 eventType={} — skip", eventType);
                acknowledgment.acknowledge();
                return;
            }

            JsonNode data = payload.path("data");
            if (data.path("couponId").isMissingNode()
                    || data.path("memberId").isMissingNode()
                    || data.path("memberCouponId").isMissingNode()) {
                log.warn("coupon-issue-requests: 필수 필드 누락 — skip. value={}", record.value());
                acknowledgment.acknowledge();
                return;
            }

            Long couponId = data.path("couponId").asLong();
            Long memberId = data.path("memberId").asLong();
            Long memberCouponId = data.path("memberCouponId").asLong();

            couponIssueEventService.processIssueRequest(eventId, couponId, memberId, memberCouponId);

            acknowledgment.acknowledge();
            log.info("coupon-issue-requests 처리 완료: eventId={}, couponId={}, memberId={}",
                    eventId, couponId, memberId);
        } catch (JsonProcessingException e) {
            log.error("coupon-issue-requests: JSON 파싱 실패 — skip. value={}", record.value(), e);
            acknowledgment.acknowledge();
        } catch (DataIntegrityViolationException e) {
            log.info("coupon-issue-requests: 중복 이벤트 감지 — ACK 전송. key={}", record.key());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("coupon-issue-requests: 처리 중 예외 — ACK 미전송 (재수신 예정). key={}", record.key(), e);
        }
    }
}
