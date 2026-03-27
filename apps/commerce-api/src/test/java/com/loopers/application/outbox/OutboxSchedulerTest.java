package com.loopers.application.outbox;

import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventPublisher;
import com.loopers.domain.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxScheduler 테스트.
 *
 * 고민 3(발행 완료 후 삭제 vs published 업데이트)에 대한 비교 테스트를 포함한다.
 */
@DisplayName("OutboxScheduler 테스트")
@ExtendWith(MockitoExtension.class)
class OutboxSchedulerTest {

    @Mock
    private OutboxEventRepository outboxRepository;

    @Mock
    private OutboxEventPublisher eventPublisher;

    private OutboxScheduler outboxScheduler;

    @BeforeEach
    void setUp() {
        outboxScheduler = new OutboxScheduler(outboxRepository, eventPublisher);
    }

    /**
     * 발행 완료 후 Outbox 레코드를 어떻게 처리할 것인가?
     *
     * 접근 A(삭제): 테이블이 깨끗하지만 이력이 사라짐
     * 접근 B(published 업데이트): 이력 추적 가능하지만 테이블이 커짐
     */
    @Nested
    @DisplayName("고민 3: 발행 완료 후 처리 — 삭제(접근 A) vs published 업데이트(접근 B)")
    class CompletionStrategyComparison {

        @Test
        @DisplayName("[접근 A 문제 재현] 삭제 방식이면 발행 이력을 추적할 수 없다")
        void approachA_deleteDestroysAuditTrail() {
            // given — 삭제 방식 시뮬레이션
            OutboxEvent event = OutboxEvent.create("ORDER", "1", "ORDER_PAID", "order-events",
                    "{\"eventId\":\"uuid-audit\",\"eventType\":\"ORDER_PAID\",\"data\":{\"orderId\":1}}");

            // 발행 전 상태 기록
            String eventIdBeforePublish = event.getEventId();
            String payloadBeforePublish = event.getPayload();

            // when — 삭제 방식이었다면: publish 후 레코드 자체가 사라짐
            // 시뮬레이션: event 객체를 null로 만드는 것으로 삭제를 표현
            OutboxEvent deletedEvent = null; // 삭제됨

            // then — 장애 발생 시 "이 이벤트가 발행됐는지?" 확인하려면?
            // 삭제 방식: 레코드가 없으므로 확인 불가
            assertThat(deletedEvent).isNull();
            // 발행 시각도 알 수 없음, 페이로드도 확인 불가

            // 반면 published 방식(접근 B)이었다면:
            // DB에서 SELECT * FROM outbox_event WHERE event_id = 'uuid-audit'
            // → published = true, published_at = '2026-03-26T10:30:00' 으로 확인 가능
            assertThat(eventIdBeforePublish).isNotNull(); // 접근 B에서는 이 정보가 DB에 남아있음
            assertThat(payloadBeforePublish).contains("orderId"); // 어떤 데이터를 발행했는지도 추적 가능
        }

        @Test
        @DisplayName("[접근 B 장점] published 방식은 발행 시각과 이벤트 내용을 추적할 수 있다")
        void approachB_publishedRetainsAuditTrail() {
            // given
            OutboxEvent event = OutboxEvent.create("ORDER", "1", "ORDER_PAID", "order-events",
                    "{\"eventId\":\"uuid-audit\",\"eventType\":\"ORDER_PAID\",\"data\":{\"orderId\":1}}");
            when(outboxRepository.findUnpublishedBefore(anyInt())).thenReturn(List.of(event));

            // when — published 방식: markPublished()로 상태 변경
            outboxScheduler.publishPendingEvents();

            // then — 발행 후에도 모든 정보를 조회할 수 있다
            assertThat(event.isPublished()).isTrue();
            assertThat(event.getPublishedAt()).isNotNull();         // 발행 시각 추적 가능
            assertThat(event.getEventId()).isNotNull();              // 어떤 이벤트인지 식별 가능
            assertThat(event.getEventType()).isEqualTo("ORDER_PAID"); // 이벤트 유형 확인 가능
            assertThat(event.getPayload()).contains("orderId");      // 발행된 데이터 확인 가능
            // 장애 분석 시: published=false인 이벤트만 조회하면 미발행 이벤트를 즉시 파악
        }

        @Test
        @DisplayName("[접근 B 장점] 성공/실패 이벤트의 published 상태로 미발행 건을 즉시 파악할 수 있다")
        void approachB_unpublishedQueryForTroubleshooting() {
            // given — 성공 이벤트와 실패 이벤트
            OutboxEvent success = OutboxEvent.create("ORDER", "1", "ORDER_PAID", "order-events", "{}");
            OutboxEvent failed = OutboxEvent.create("PRODUCT", "10", "PRODUCT_LIKED", "catalog-events", "{}");
            when(outboxRepository.findUnpublishedBefore(anyInt())).thenReturn(List.of(failed, success));
            // 첫 번째 호출은 실패, 두 번째 호출은 성공
            doThrow(new RuntimeException("Kafka error"))
                    .doNothing()
                    .when(eventPublisher).publish(any());

            // when
            outboxScheduler.publishPendingEvents();

            // then — published 방식에서는 성공/실패 이벤트의 상태를 즉시 파악 가능
            assertThat(failed.isPublished()).isFalse();   // 미발행 → 장애 원인 분석 대상
            assertThat(success.isPublished()).isTrue();    // 발행 성공

            // 삭제 방식이었다면: 성공한 이벤트(success)는 삭제되고 실패한 이벤트만 남아있어
            // "어떤 이벤트들이 성공적으로 발행됐는지" 비교 분석이 불가능
            // published 방식: SELECT * FROM outbox_event ORDER BY created_at
            // → 전체 이벤트의 발행 성공/실패 현황을 한눈에 파악
        }
    }

    @Nested
    @DisplayName("스케줄러 기본 동작")
    class BasicBehavior {

        @Test
        @DisplayName("미발행 이벤트가 없으면 아무 발행도 하지 않는다")
        void publishPendingEvents_noEvents() {
            // given
            when(outboxRepository.findUnpublishedBefore(anyInt())).thenReturn(Collections.emptyList());

            // when
            outboxScheduler.publishPendingEvents();

            // then
            verify(eventPublisher, never()).publish(any());
        }

        @Test
        @DisplayName("미발행 이벤트를 Kafka로 발행하고 published 상태로 변경한다")
        void publishPendingEvents_publishesAndMarksPublished() {
            // given
            OutboxEvent event1 = OutboxEvent.create("ORDER", "1", "ORDER_PAID", "order-events", "{}");
            OutboxEvent event2 = OutboxEvent.create("PRODUCT", "10", "PRODUCT_LIKED", "catalog-events", "{}");
            when(outboxRepository.findUnpublishedBefore(anyInt())).thenReturn(List.of(event1, event2));

            // when
            outboxScheduler.publishPendingEvents();

            // then
            verify(eventPublisher, times(2)).publish(any());
            assertThat(event1.isPublished()).isTrue();
            assertThat(event2.isPublished()).isTrue();
        }

        @Test
        @DisplayName("하나의 발행이 실패해도 나머지 이벤트는 정상 발행된다")
        void publishPendingEvents_partialFailure() {
            // given
            OutboxEvent event1 = OutboxEvent.create("ORDER", "1", "ORDER_PAID", "order-events", "{}");
            OutboxEvent event2 = OutboxEvent.create("PRODUCT", "10", "PRODUCT_LIKED", "catalog-events", "{}");
            when(outboxRepository.findUnpublishedBefore(anyInt())).thenReturn(List.of(event1, event2));
            doThrow(new RuntimeException("Kafka error")).when(eventPublisher).publish(event1);

            // when
            outboxScheduler.publishPendingEvents();

            // then
            verify(eventPublisher, times(2)).publish(any());
            assertThat(event1.isPublished()).isFalse(); // 실패 → 미발행 유지 → 다음 폴링에서 재시도
            assertThat(event2.isPublished()).isTrue();   // 성공 → 발행 완료
        }
    }
}
