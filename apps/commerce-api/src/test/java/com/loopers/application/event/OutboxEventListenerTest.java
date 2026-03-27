package com.loopers.application.event;

import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventPublisher;
import com.loopers.domain.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("OutboxEventListener 테스트")
@ExtendWith(MockitoExtension.class)
class OutboxEventListenerTest {

    @Mock
    private OutboxEventPublisher eventPublisher;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private OutboxEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new OutboxEventListener(eventPublisher, outboxEventRepository);
    }

    @Test
    @DisplayName("AFTER_COMMIT에서 Kafka 발행 성공 시 markPublished로 중복 발행을 방지한다")
    void publishAfterCommit_success() {
        // given
        OutboxEvent outboxEvent = OutboxEvent.create("COUPON", "100", "COUPON_ISSUE_REQUESTED", "coupon-issue-requests", "{}");
        OutboxEvent.Published event = new OutboxEvent.Published(outboxEvent);

        // when
        listener.publishAfterCommit(event);

        // then
        verify(eventPublisher).publish(outboxEvent);
        assertThat(outboxEvent.isPublished()).isTrue();
        verify(outboxEventRepository).save(outboxEvent);
    }

    @Test
    @DisplayName("Kafka 발행 실패해도 예외가 전파되지 않고 markPublished되지 않는다 — 스케줄러가 재시도")
    void publishAfterCommit_failureSuppressed() {
        // given
        OutboxEvent outboxEvent = OutboxEvent.create("COUPON", "100", "COUPON_ISSUE_REQUESTED", "coupon-issue-requests", "{}");
        OutboxEvent.Published event = new OutboxEvent.Published(outboxEvent);
        doThrow(new RuntimeException("Kafka unavailable")).when(eventPublisher).publish(any());

        // when
        listener.publishAfterCommit(event);

        // then
        verify(eventPublisher).publish(outboxEvent);
        assertThat(outboxEvent.isPublished()).isFalse();
        verify(outboxEventRepository, never()).save(any());
    }
}
