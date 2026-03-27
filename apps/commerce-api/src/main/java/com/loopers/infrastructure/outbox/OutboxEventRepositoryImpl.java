package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Outbox 이벤트 저장소 구현체.
 *
 * Domain 레이어의 {@link OutboxEventRepository} 인터페이스를 구현하며,
 * 내부적으로 {@link OutboxEventJpaRepository}에 위임한다.
 */
@RequiredArgsConstructor
@Component
public class OutboxEventRepositoryImpl implements OutboxEventRepository {

    private final OutboxEventJpaRepository outboxEventJpaRepository;

    @Override
    public OutboxEvent save(OutboxEvent outboxEvent) {
        return outboxEventJpaRepository.save(outboxEvent);
    }

    /**
     * 생성 후 10초 이상 지난 미발행 이벤트를 조회한다.
     *
     * 10초 대기 시간은 Auto/Manual 모드에서 정상적으로 Kafka 발행이 이루어지는
     * 시간을 확보하기 위함이다. 이 시간이 지나도 미발행이면 발행 실패로 판단하고 재시도한다.
     */
    @Override
    public List<OutboxEvent> findUnpublishedBefore(int limit) {
        ZonedDateTime threshold = ZonedDateTime.now().minusSeconds(10);
        return outboxEventJpaRepository.findUnpublishedBefore(threshold, limit);
    }
}
