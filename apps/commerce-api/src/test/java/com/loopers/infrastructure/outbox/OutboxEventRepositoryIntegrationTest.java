package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [통합 테스트 - Repository Integration]
 *
 * 테스트 대상: OutboxEventRepository (Infrastructure Layer)
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: Repository → Database (Testcontainers)
 *
 * 검증 목적:
 * - findUnpublishedBefore 쿼리가 실제 DB에서 올바르게 동작하는지
 * - published=true인 이벤트는 조회에서 제외되는지
 * - 생성 후 일정 시간이 지난 이벤트만 조회되는지
 */
@SpringBootTest
@Transactional
@DisplayName("OutboxEventRepository 통합 테스트")
class OutboxEventRepositoryIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxEventJpaRepository outboxEventJpaRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private OutboxEvent saveOutboxEvent(String aggregateType, String aggregateId, String eventType) {
        OutboxEvent event = OutboxEvent.create(aggregateType, aggregateId, eventType, "test-topic", "{}");
        return outboxEventRepository.save(event);
    }

    /**
     * created_at 컬럼이 updatable=false이므로 native query로 과거 시간으로 변경.
     * JPA의 ZonedDateTime은 UTC로 저장되므로 UTC 기준으로 설정한다.
     */
    private void backdateCreatedAt(Long eventId) {
        entityManager.flush();
        entityManager.createNativeQuery(
                        "UPDATE outbox_event SET created_at = DATE_SUB(NOW(6), INTERVAL 11 SECOND) WHERE id = :id")
                .setParameter("id", eventId)
                .executeUpdate();
        entityManager.clear();
    }

    @Nested
    @DisplayName("미발행 이벤트 조회")
    class FindUnpublishedBefore {

        @Test
        @DisplayName("미발행 이벤트를 조회한다")
        void returnsUnpublishedEvents() {
            // given
            OutboxEvent event = saveOutboxEvent("ORDER", "1", "ORDER_PAID");
            backdateCreatedAt(event.getId());

            // when
            List<OutboxEvent> result = outboxEventRepository.findUnpublishedBefore(100);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getEventId()).isEqualTo(event.getEventId());
        }

        @Test
        @DisplayName("발행 완료된 이벤트는 조회에서 제외된다")
        void excludesPublishedEvents() {
            // given
            OutboxEvent event = saveOutboxEvent("ORDER", "1", "ORDER_PAID");
            event.markPublished();
            outboxEventJpaRepository.saveAndFlush(event);
            backdateCreatedAt(event.getId());

            // when
            List<OutboxEvent> result = outboxEventRepository.findUnpublishedBefore(100);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("최근 생성된 이벤트(10초 이내)는 조회에서 제외된다")
        void excludesRecentEvents() {
            // given — createdAt이 현재 시각(기본값)인 이벤트는 10초 이내이므로 제외
            saveOutboxEvent("ORDER", "1", "ORDER_PAID");

            // when
            List<OutboxEvent> result = outboxEventRepository.findUnpublishedBefore(100);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("limit 이하의 이벤트만 조회된다")
        void respectsLimit() {
            // given
            for (int i = 0; i < 5; i++) {
                OutboxEvent event = saveOutboxEvent("ORDER", String.valueOf(i), "ORDER_PAID");
                backdateCreatedAt(event.getId());
            }

            // when
            List<OutboxEvent> result = outboxEventRepository.findUnpublishedBefore(3);

            // then
            assertThat(result).hasSize(3);
        }
    }

    @Nested
    @DisplayName("Outbox 이벤트 저장")
    class Save {

        @Test
        @DisplayName("OutboxEvent를 저장하면 eventId와 createdAt이 설정된다")
        void savesWithEventIdAndCreatedAt() {
            // given
            OutboxEvent event = OutboxEvent.create("PRODUCT", "10", "PRODUCT_LIKED", "test-topic", "{}");

            // when
            OutboxEvent saved = outboxEventRepository.save(event);

            // then
            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getEventId()).isNotNull();
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.isPublished()).isFalse();
        }
    }
}
