package com.loopers.application.outbox;

import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventPublisher;
import com.loopers.domain.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 미발행 Outbox 이벤트를 주기적으로 폴링하여 Kafka로 발행하는 스케줄러.
 *
 * 역할:
 * - Auto 모드에서 AFTER_COMMIT Kafka 발행이 실패한 경우 재시도
 * - Manual 모드에서 Kafka 발행이 실패한 경우 재시도
 * - TX 래핑 모드의 유일한 Kafka 발행 경로 (즉시 발행 없음)
 *
 * @Transactional이 필요한 이유:
 * {@code event.markPublished()}는 JPA 변경 감지(dirty checking)를 통해 UPDATE된다.
 * {@code @Transactional}이 없으면 영속성 컨텍스트가 없어 변경 감지가 동작하지 않는다.
 *
 * Kafka I/O와 트랜잭션 범위:
 * 하나의 {@code @Transactional} 안에서 Kafka 발행(외부 I/O)이 포함되므로,
 * 대량 발행 시 트랜잭션 수명이 길어질 수 있다. {@code LIMIT 100}으로 1회 처리량을 제한하여
 * 트랜잭션 시간을 관리한다.
 *
 * 발행 완료 후 처리 전략에 대한 고민 — 삭제 vs published 업데이트
 *
 * 접근 A — 삭제 (outboxRepository.delete):
 * <pre>
 * eventPublisher.publish(event);
 * outboxRepository.delete(event);  // 발행 완료 → 레코드 삭제
 * </pre>
 * 장점: Outbox 테이블이 항상 깨끗하게 유지. 미발행 이벤트만 남아 있어 쿼리 성능 좋음.
 * 단점: 발행 이력이 사라져 디버깅/감사 추적 불가. "이 이벤트가 언제 발행됐는지" 확인 불가.
 * 장애 상황에서 "이 이벤트가 정말 발행됐는지" 검증 불가.
 *
 * 접근 B — published 업데이트 (markPublished):
 * <pre>
 * eventPublisher.publish(event);
 * event.markPublished();  // published = true, publishedAt = now()
 * </pre>
 * 장점: 발행 이력을 추적 가능. "이 이벤트가 언제 발행됐는지" 확인 가능.
 * 장애 분석 시 published/unpublished 이벤트를 비교하여 문제를 파악 가능.
 * 단점: 테이블이 계속 커짐. 주기적 정리(배치 삭제)가 필요.
 *
 * 결정: 접근 B (published 업데이트)
 *
 * 이유:
 * - Outbox Pattern 도입 초기에는 이벤트 흐름을 추적할 수 있어야 한다.
 *   "이 이벤트가 발행됐는지"를 DB에서 즉시 확인 가능한 것이 운영에 유리
 * - 테이블 비대화는 별도 배치로 오래된(예: 7일 이상) published 레코드를 삭제하여 관리 가능
 * - 삭제 방식은 되돌릴 수 없지만, published 방식은 필요 시 삭제로 전환 가능 (반대는 불가)
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OutboxScheduler {

    private static final int BATCH_LIMIT = 100;

    private final OutboxEventRepository outboxRepository;
    private final OutboxEventPublisher eventPublisher;

    /**
     * 10초마다 미발행 이벤트를 폴링하여 Kafka로 발행한다.
     *
     * 건별 try-catch로 감싸서 하나의 발행 실패가 나머지 이벤트의 발행을 막지 않도록 한다.
     * 실패한 이벤트는 published = false를 유지하여 다음 스케줄에서 재시도된다.
     *
     * 발행 성공 시 {@code markPublished()}로 상태를 변경한다 (삭제하지 않음 — 위 Javadoc 참고).
     */
    @Transactional
    @Scheduled(fixedDelay = 10000)
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxRepository.findUnpublishedBefore(BATCH_LIMIT);

        if (events.isEmpty()) {
            return;
        }

        log.info("Outbox 미발행 이벤트 {} 건 발행 시작", events.size());

        int successCount = 0;
        for (OutboxEvent event : events) {
            try {
                eventPublisher.publish(event);
                event.markPublished(); // published = true (삭제 대신 상태 업데이트)
                successCount++;
            } catch (Exception e) {
                log.error("Outbox 발행 실패 — 다음 스케줄에서 재시도: eventId={}, eventType={}",
                        event.getEventId(), event.getEventType(), e);
            }
        }

        log.info("Outbox 발행 완료: 성공={}, 실패={}", successCount, events.size() - successCount);
    }
}
