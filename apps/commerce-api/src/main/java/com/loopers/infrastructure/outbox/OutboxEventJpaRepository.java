package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Outbox 이벤트 JPA Repository.
 *
 * Spring Data JPA가 제공하는 프록시 구현체를 사용한다.
 * Domain 레이어의 {@link com.loopers.domain.outbox.OutboxEventRepository}에 직접 노출하지 않고,
 * {@link OutboxEventRepositoryImpl}을 통해 위임한다.
 */
public interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 미발행 이벤트를 생성 시간 순으로 조회한다.
     *
     * 생성 후 10초 이상 지난 이벤트만 조회하여,
     * Auto/Manual 모드의 정상 발행 대기 시간을 확보한다.
     *
     * @param threshold 조회 기준 시각 (이 시각 이전에 생성된 이벤트만 조회)
     * @param limit     최대 조회 건수
     * @return 미발행 이벤트 목록 (created_at 오름차순)
     */
    @Query("SELECT o FROM OutboxEvent o WHERE o.published = false AND o.createdAt < :threshold ORDER BY o.createdAt ASC LIMIT :limit")
    List<OutboxEvent> findUnpublishedBefore(@Param("threshold") ZonedDateTime threshold,
                                            @Param("limit") int limit);
}
