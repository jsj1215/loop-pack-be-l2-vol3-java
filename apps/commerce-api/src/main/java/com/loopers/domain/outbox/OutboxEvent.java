package com.loopers.domain.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Transactional Outbox Pattern의 핵심 엔티티.
 *
 * 비즈니스 트랜잭션과 같은 TX에서 저장되어 원자성을 보장하고,
 * 별도 스케줄러({@link com.loopers.application.outbox.OutboxScheduler})가
 * 미발행 이벤트를 폴링하여 Kafka로 발행한다.
 *
 * 테이블 설계 의도:
 * - {@code eventId} — UUID로 생성. Consumer 쪽 {@code event_handled} 테이블의 PK로 사용되어 멱등 처리의 키 역할
 * - {@code topic} — 저장 시점에 토픽을 확정하여, Publisher가 라우팅 로직 없이 발행만 하도록 함
 * - {@code aggregateId} — Kafka Partition Key로 사용되어 같은 aggregate의 이벤트 순서를 보장
 * - {@code published} — 발행 완료 여부. false인 이벤트만 스케줄러가 폴링하여 재시도
 *
 * 발행 보장 수준:
 * At Least Once — 중복 발행 가능, Consumer 쪽 {@code event_handled}로 멱등 처리
 */
@Entity
@Table(name = "outbox_event", indexes = {
        @Index(name = "idx_outbox_published_created", columnList = "published, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Consumer 멱등 처리 키. UUID v4로 생성되어 분산 환경에서도 고유성 보장 */
    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    /** 이벤트 소속 도메인 구분자 (예: ORDER, PRODUCT) */
    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    /** Kafka Partition Key로 사용. 같은 aggregate의 이벤트 순서를 보장 */
    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    /** 이벤트 유형 (예: ORDER_PAID, PRODUCT_LIKED, PRODUCT_VIEWED) */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /** Kafka 토픽명. 저장 시점에 확정하여 Publisher의 라우팅 책임을 제거 */
    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    /** JSON 직렬화된 이벤트 데이터 */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** 발행 완료 여부. false인 이벤트만 스케줄러가 폴링 */
    @Column(name = "published", nullable = false)
    private boolean published;

    @Column(name = "created_at", nullable = false, updatable = false)
    private ZonedDateTime createdAt;

    /** Kafka 발행 완료 시각 */
    @Column(name = "published_at")
    private ZonedDateTime publishedAt;

    @PrePersist
    private void prePersist() {
        this.createdAt = ZonedDateTime.now();
    }

    private OutboxEvent(String aggregateType, String aggregateId,
                        String eventType, String topic, String payload) {
        this.eventId = UUID.randomUUID().toString();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.published = false;
    }

    /**
     * Outbox 이벤트를 생성한다.
     *
     * @param aggregateType 도메인 구분자 (예: "ORDER", "PRODUCT")
     * @param aggregateId   Kafka Partition Key (예: orderId, productId)
     * @param eventType     이벤트 유형 (예: "ORDER_PAID")
     * @param topic         Kafka 토픽명 (예: "order-events")
     * @param payload       JSON 직렬화된 이벤트 데이터
     * @return 미발행 상태의 OutboxEvent
     */
    public static OutboxEvent create(String aggregateType, String aggregateId,
                                     String eventType, String topic, String payload) {
        return new OutboxEvent(aggregateType, aggregateId, eventType, topic, payload);
    }

    /**
     * payload를 갱신한다.
     *
     * 엔벨로프 구조(eventId, eventType, data)를 포함한 payload로 업데이트할 때 사용.
     * eventId는 OutboxEvent 생성 후에 확정되므로, 생성 후 payload를 갱신해야 한다.
     */
    public void updatePayload(String payload) {
        this.payload = payload;
    }

    /**
     * 발행 완료로 상태를 변경한다.
     * JPA 변경 감지(dirty checking)를 통해 TX 커밋 시 UPDATE 반영.
     */
    public void markPublished() {
        this.published = true;
        this.publishedAt = ZonedDateTime.now();
    }

    // Spring ApplicationEvent 래퍼.
    // TX 커밋 후 즉시 Kafka 발행을 시도하기 위해 사용.
    // OutboxEventListener가 AFTER_COMMIT에서 수신하여 Kafka 발행을 시도하고,
    // 실패 시 OutboxScheduler가 재시도한다.
    public record Published(OutboxEvent outboxEvent) {}
}
