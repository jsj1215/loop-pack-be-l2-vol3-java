package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox 이벤트를 Kafka로 발행하는 구현체.
 *
 * 동기 vs 비동기 발행에 대한 고민
 *
 * 접근 A — 비동기 발행 (whenComplete 콜백):
 * <pre>
 * kafkaTemplate.send(topic, key, payload)
 *     .whenComplete((result, ex) -> {
 *         if (ex != null) log.error("발행 실패", ex);
 *     });
 * </pre>
 * 장점: 호출 스레드가 블로킹되지 않아 스케줄러가 100건을 거의 즉시 발행 가능.
 * 단점: 발행 성공/실패를 호출자에게 즉시 알릴 수 없어, {@code markPublished()} 호출 타이밍이 복잡해진다.
 * 콜백에서 {@code markPublished()}를 호출하려면 별도 TX를 열어야 하고, 콜백 실행 스레드에서
 * JPA 영속성 컨텍스트를 관리해야 하는 복잡도가 추가된다.
 *
 * 접근 B — 동기 발행 (.get() 블로킹):
 * <pre>
 * kafkaTemplate.send(topic, key, payload).get(5, TimeUnit.SECONDS);
 * </pre>
 * 장점: 발행 성공/실패를 즉시 알 수 있어 {@code markPublished()} 결정이 명확.
 * 실패 시 예외가 호출자에게 전파되어 스케줄러의 try-catch에서 처리 가능.
 * 단점: 건별로 블로킹되어 100건 순차 발행 시 최대 500초 소요 가능.
 *
 * 결정: 접근 B (동기 발행)
 *
 * 이유:
 * - 스케줄러에서 {@code markPublished()}를 같은 TX 안에서 처리해야 하므로,
 *   발행 결과를 동기적으로 받는 것이 구조적으로 자연스럽다
 * - LIMIT 100으로 1회 처리량을 제한하고 있어, 최악의 경우에도 영향이 제한적
 * - 비동기 콜백에서 TX를 별도로 열어 {@code markPublished()}를 처리하는 복잡도 대비
 *   얻는 이점(처리 시간 단축)이 현재 규모에서는 크지 않다
 * - 대규모 트래픽에서 성능 이슈가 발생하면, 그때 비동기 + 배치 ACK 방식으로 전환 가능
 *
 * @see com.loopers.application.outbox.OutboxScheduler
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class KafkaOutboxEventPublisher implements OutboxEventPublisher {

    private static final int SEND_TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    /**
     * Outbox 이벤트를 Kafka에 동기적으로 발행한다.
     *
     * 동기 발행({@code .get()})을 사용하여 발행 성공/실패를 호출자에게 즉시 전달한다.
     * 스케줄러에서 발행 성공 시 {@code markPublished()}를 호출하고,
     * 실패 시 예외를 catch하여 다음 스케줄에서 재시도한다.
     *
     * @param outboxEvent 발행할 이벤트
     * @throws RuntimeException Kafka 발행 실패 또는 타임아웃 시
     */
    @Override
    public void publish(OutboxEvent outboxEvent) {
        try {
            kafkaTemplate.send(
                    outboxEvent.getTopic(),
                    outboxEvent.getAggregateId(),
                    outboxEvent.getPayload()
            ).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            log.info("Outbox Kafka 발행 성공: eventId={}, topic={}, aggregateId={}",
                    outboxEvent.getEventId(), outboxEvent.getTopic(), outboxEvent.getAggregateId());
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            log.error("Outbox Kafka 발행 실패: eventId={}, topic={}",
                    outboxEvent.getEventId(), outboxEvent.getTopic(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Kafka 발행 실패: eventId=" + outboxEvent.getEventId(), e);
        }
    }
}
