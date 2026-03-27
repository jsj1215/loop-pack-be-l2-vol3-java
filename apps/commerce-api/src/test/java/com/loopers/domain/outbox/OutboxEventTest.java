package com.loopers.domain.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutboxEvent 도메인 엔티티 테스트")
class OutboxEventTest {

    @Test
    @DisplayName("OutboxEvent 생성 시 UUID eventId가 발급되고 미발행 상태이다")
    void createOutboxEvent() {
        // given
        String aggregateType = "COUPON";
        String aggregateId = "100";
        String eventType = "COUPON_ISSUE_REQUESTED";
        String topic = "coupon-issue-requests";
        String payload = "{\"couponId\":100}";

        // when
        OutboxEvent event = OutboxEvent.create(aggregateType, aggregateId, eventType, topic, payload);

        // then
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getEventId()).hasSize(36);
        assertThat(event.getAggregateType()).isEqualTo("COUPON");
        assertThat(event.getAggregateId()).isEqualTo("100");
        assertThat(event.getEventType()).isEqualTo("COUPON_ISSUE_REQUESTED");
        assertThat(event.getTopic()).isEqualTo("coupon-issue-requests");
        assertThat(event.getPayload()).isEqualTo("{\"couponId\":100}");
        assertThat(event.isPublished()).isFalse();
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("두 번 생성하면 서로 다른 eventId가 발급된다")
    void createTwoEventsWithDifferentEventIds() {
        // given & when
        OutboxEvent event1 = OutboxEvent.create("COUPON", "1", "COUPON_ISSUE_REQUESTED", "coupon-issue-requests", "{}");
        OutboxEvent event2 = OutboxEvent.create("COUPON", "2", "COUPON_ISSUE_REQUESTED", "coupon-issue-requests", "{}");

        // then
        assertThat(event1.getEventId()).isNotEqualTo(event2.getEventId());
    }

    @Test
    @DisplayName("markPublished 호출 시 published가 true가 되고 publishedAt이 설정된다")
    void markPublished() {
        // given
        OutboxEvent event = OutboxEvent.create("COUPON", "100", "COUPON_ISSUE_REQUESTED", "coupon-issue-requests", "{}");

        // when
        event.markPublished();

        // then
        assertThat(event.isPublished()).isTrue();
        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("Published 래퍼는 OutboxEvent를 감싸서 전달한다")
    void publishedWrapper() {
        // given
        OutboxEvent event = OutboxEvent.create("COUPON", "100", "COUPON_ISSUE_REQUESTED", "coupon-issue-requests", "{}");

        // when
        OutboxEvent.Published published = new OutboxEvent.Published(event);

        // then
        assertThat(published.outboxEvent()).isSameAs(event);
    }

    @Test
    @DisplayName("updatePayload로 payload를 갱신할 수 있다")
    void updatePayload() {
        // given
        OutboxEvent event = OutboxEvent.create("COUPON", "100", "COUPON_ISSUE_REQUESTED", "coupon-issue-requests", "");

        // when
        String newPayload = "{\"eventId\":\"uuid\",\"eventType\":\"COUPON_ISSUE_REQUESTED\",\"data\":{}}";
        event.updatePayload(newPayload);

        // then
        assertThat(event.getPayload()).isEqualTo(newPayload);
    }
}
