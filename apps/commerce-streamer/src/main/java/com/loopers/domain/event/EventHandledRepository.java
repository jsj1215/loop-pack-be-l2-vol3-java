package com.loopers.domain.event;

/**
 * 이벤트 처리 기록 저장소 인터페이스 (DIP).
 *
 * Consumer의 멱등 처리에 사용된다.
 * {@code existsByEventId}로 중복 확인 후, {@code save}로 처리 기록을 남긴다.
 */
public interface EventHandledRepository {

    /**
     * 해당 eventId가 이미 처리되었는지 확인한다.
     *
     * @param eventId Outbox의 eventId (UUID)
     * @return 이미 처리된 경우 true
     */
    boolean existsByEventId(String eventId);

    /**
     * 이벤트 처리 기록을 저장한다.
     *
     * @param eventHandled 저장할 처리 기록
     * @return 저장된 처리 기록
     */
    EventHandled save(EventHandled eventHandled);
}
