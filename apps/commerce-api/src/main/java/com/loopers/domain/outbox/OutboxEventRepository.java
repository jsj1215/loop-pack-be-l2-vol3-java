package com.loopers.domain.outbox;

import java.util.List;

/**
 * Outbox 이벤트 저장소 인터페이스 (DIP).
 *
 * Domain 레이어에 인터페이스를 정의하고, Infrastructure 레이어에서 JPA로 구현한다.
 * Application 레이어({@link com.loopers.application.outbox.OutboxScheduler},
 * {@link com.loopers.application.event.OutboxEventListener})에서 이 인터페이스에만 의존한다.
 */
public interface OutboxEventRepository {

    /**
     * Outbox 이벤트를 저장한다.
     *
     * @param outboxEvent 저장할 이벤트
     * @return 저장된 이벤트 (ID 포함)
     */
    OutboxEvent save(OutboxEvent outboxEvent);

    /**
     * 미발행 이벤트 목록을 조회한다.
     *
     * 생성 후 일정 시간이 지난 이벤트만 조회하여,
     * Auto/Manual 모드의 정상 발행 대기 시간을 확보한다.
     *
     * @param limit 최대 조회 건수 (스케줄러 1회 처리량 제한)
     * @return 미발행 이벤트 목록 (created_at 오름차순)
     */
    List<OutboxEvent> findUnpublishedBefore(int limit);
}
