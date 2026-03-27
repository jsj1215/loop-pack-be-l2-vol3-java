package com.loopers.application.event;

import com.loopers.domain.event.OrderPaidEvent;
import com.loopers.domain.event.ProductLikedEvent;
import com.loopers.domain.event.ProductViewedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// 도메인 이벤트를 Kafka로 직접 발행하는 리스너.
//
// 통계성 이벤트(좋아요 수, 조회수, 판매량)는 유실되어도 배치 보정이 가능하므로
// Outbox Pattern 없이 AFTER_COMMIT에서 직접 Kafka로 발행한다.
// 발행 실패 시 이벤트가 유실될 수 있으나, product_metrics 집계용이므로 허용한다.
@Slf4j
@RequiredArgsConstructor
@Component
public class KafkaEventPublishListener {

    private static final String TOPIC_CATALOG_EVENTS = "catalog-events";
    private static final String TOPIC_ORDER_EVENTS = "order-events";

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final KafkaPayloadSerializer payloadSerializer;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishProductLiked(ProductLikedEvent event) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> data = new HashMap<>();
        data.put("productId", event.productId());
        data.put("liked", event.liked());
        data.put("occurredAt", ZonedDateTime.now().toString());
        Optional.ofNullable(event.memberId()).ifPresent(id -> data.put("memberId", id));
        String payload = payloadSerializer.serialize(payloadSerializer.buildEnvelope(eventId, "PRODUCT_LIKED", data));

        try {
            kafkaTemplate.send(TOPIC_CATALOG_EVENTS, String.valueOf(event.productId()), payload);
            log.info("ProductLikedEvent Kafka 발행: eventId={}, productId={}", eventId, event.productId());
        } catch (Exception e) {
            log.warn("ProductLikedEvent Kafka 발행 실패 (통계성 이벤트, 유실 허용): eventId={}", eventId, e);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publishProductViewed(ProductViewedEvent event) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> data = new HashMap<>();
        data.put("productId", event.productId());
        data.put("occurredAt", ZonedDateTime.now().toString());
        Optional.ofNullable(event.memberId()).ifPresent(id -> data.put("memberId", id));
        String payload = payloadSerializer.serialize(payloadSerializer.buildEnvelope(eventId, "PRODUCT_VIEWED", data));

        try {
            kafkaTemplate.send(TOPIC_CATALOG_EVENTS, String.valueOf(event.productId()), payload);
            log.debug("ProductViewedEvent Kafka 발행: eventId={}, productId={}", eventId, event.productId());
        } catch (Exception e) {
            log.warn("ProductViewedEvent Kafka 발행 실패 (통계성 이벤트, 유실 허용): eventId={}", eventId, e);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publishOrderPaid(OrderPaidEvent event) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> data = Map.of(
                "orderId", event.orderId(),
                "memberId", event.memberId(),
                "totalAmount", event.totalAmount(),
                "orderedProducts", event.orderedProducts(),
                "occurredAt", ZonedDateTime.now().toString()
        );
        String payload = payloadSerializer.serialize(payloadSerializer.buildEnvelope(eventId, "ORDER_PAID", data));

        try {
            kafkaTemplate.send(TOPIC_ORDER_EVENTS, String.valueOf(event.orderId()), payload);
            log.info("OrderPaidEvent Kafka 발행: eventId={}, orderId={}", eventId, event.orderId());
        } catch (Exception e) {
            log.warn("OrderPaidEvent Kafka 발행 실패 (통계성 이벤트, 유실 허용): eventId={}", eventId, e);
        }
    }
}
