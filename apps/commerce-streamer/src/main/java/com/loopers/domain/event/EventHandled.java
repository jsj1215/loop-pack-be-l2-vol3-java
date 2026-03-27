package com.loopers.domain.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * Consumer 멱등 처리를 위한 이벤트 처리 기록 엔티티.
 *
 * Outbox의 {@code eventId}(UUID)를 PK로 사용하여, 이미 처리된 이벤트를 식별한다.
 * At Least Once 발행 환경에서 중복 메시지를 수신했을 때
 * 이 테이블에 eventId가 있으면 skip, 없으면 처리 후 INSERT한다.
 *
 * 트랜잭션 원자성:
 * {@code event_handled} INSERT와 비즈니스 로직({@code product_metrics} upsert)은
 * 반드시 같은 트랜잭션에서 수행되어야 한다.
 * 비즈니스 로직 실패 시 handled 기록도 롤백되어
 * "처리 안 했는데 처리했다고 기록되는" 상황을 방지한다.
 */
@Entity
@Table(name = "event_handled")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventHandled {

    /** Outbox의 eventId (UUID) — Consumer 멱등 처리 키 */
    @Id
    @Column(name = "event_id", length = 36)
    private String eventId;

    /** 이벤트 처리 완료 시각 */
    @Column(name = "handled_at", nullable = false)
    private ZonedDateTime handledAt;

    @PrePersist
    private void prePersist() {
        this.handledAt = ZonedDateTime.now();
    }

    public EventHandled(String eventId) {
        this.eventId = eventId;
    }
}
