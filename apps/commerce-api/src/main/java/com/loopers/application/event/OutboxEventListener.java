package com.loopers.application.event;

import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventPublisher;
import com.loopers.domain.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// Outbox 이벤트의 커밋 후 즉시 Kafka 발행을 시도하는 리스너.
//
// CouponIssueFacade에서 OutboxEvent를 같은 TX에 INSERT한 후,
// OutboxEvent.Published 이벤트를 발행하면 이 리스너가 AFTER_COMMIT에서 즉시 발행을 시도한다.
// 발행 성공 시 markPublished()로 중복 발행을 방지하고,
// 실패 시 OutboxScheduler가 10초 폴링으로 재시도한다.
@Slf4j
@RequiredArgsConstructor
@Component
public class OutboxEventListener {

    private final OutboxEventPublisher eventPublisher;
    private final OutboxEventRepository outboxEventRepository;

    // TX 커밋 후 비동기로 즉시 Kafka 발행을 시도한다.
    // @Async로 별도 스레드에서 실행되므로 원래 TX가 없어 REQUIRES_NEW로 새 TX를 연다.
    // 발행 성공 시 markPublished()를 호출하여 스케줄러의 중복 발행을 방지한다.
    // 실패해도 OutboxScheduler가 재시도하므로 이벤트 유실은 없다.
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishAfterCommit(OutboxEvent.Published event) {
        OutboxEvent outboxEvent = event.outboxEvent();
        try {
            eventPublisher.publish(outboxEvent);
            outboxEvent.markPublished();
            outboxEventRepository.save(outboxEvent);
            log.info("Outbox 즉시 Kafka 발행 성공: eventId={}", outboxEvent.getEventId());
        } catch (Exception e) {
            log.warn("Outbox 즉시 Kafka 발행 실패 — 스케줄러가 재시도 예정: eventId={}",
                    outboxEvent.getEventId(), e);
        }
    }
}
