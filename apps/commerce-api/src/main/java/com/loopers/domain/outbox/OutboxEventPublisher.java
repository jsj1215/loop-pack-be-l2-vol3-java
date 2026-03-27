package com.loopers.domain.outbox;

/**
 * Outbox 이벤트를 외부 메시지 브로커로 발행하는 인터페이스 (DIP).
 *
 * Domain 레이어에 인터페이스를 정의하여 Application 레이어가 인프라 구체 클래스(KafkaTemplate 등)에
 * 직접 의존하지 않도록 한다. Infrastructure 레이어에서 Kafka 구현체를 제공한다.
 *
 * @see com.loopers.infrastructure.outbox.KafkaOutboxEventPublisher
 */
public interface OutboxEventPublisher {

    /**
     * Outbox 이벤트를 Kafka로 발행한다.
     *
     * 발행 시 {@link OutboxEvent#getTopic()}을 토픽으로,
     * {@link OutboxEvent#getAggregateId()}를 Partition Key로 사용하여
     * 같은 aggregate의 이벤트 순서를 보장한다.
     *
     * @param outboxEvent 발행할 이벤트
     * @throws RuntimeException Kafka 발행 실패 시
     */
    void publish(OutboxEvent outboxEvent);
}
