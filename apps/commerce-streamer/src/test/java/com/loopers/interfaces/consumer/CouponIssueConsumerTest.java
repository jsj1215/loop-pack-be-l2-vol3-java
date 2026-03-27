package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.CouponIssueEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("CouponIssueConsumer 테스트")
@ExtendWith(MockitoExtension.class)
class CouponIssueConsumerTest {

    @Mock
    private CouponIssueEventService couponIssueEventService;

    @Mock
    private Acknowledgment acknowledgment;

    private CouponIssueConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new CouponIssueConsumer(couponIssueEventService, new ObjectMapper());
    }

    @Test
    @DisplayName("COUPON_ISSUE_REQUESTED 이벤트 수신 시 서비스에 위임하고 ACK를 보낸다")
    void handleCouponIssueRequested_delegatesToService() {
        // given
        String payload = """
                {"eventId":"uuid-1","eventType":"COUPON_ISSUE_REQUESTED","data":{"couponId":100,"memberId":1,"memberCouponId":10}}
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>("coupon-issue-requests", 0, 0, "100", payload);

        // when
        consumer.consume(record, acknowledgment);

        // then
        verify(couponIssueEventService).processIssueRequest(eq("uuid-1"), eq(100L), eq(1L), eq(10L));
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("서비스 처리 중 예외 발생 시 ACK를 보내지 않는다")
    void doesNotAck_whenServiceFails() {
        // given
        String payload = """
                {"eventId":"uuid-fail","eventType":"COUPON_ISSUE_REQUESTED","data":{"couponId":100,"memberId":1,"memberCouponId":10}}
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>("coupon-issue-requests", 0, 0, "100", payload);
        doThrow(new RuntimeException("DB error"))
                .when(couponIssueEventService).processIssueRequest(eq("uuid-fail"), eq(100L), eq(1L), eq(10L));

        // when
        consumer.consume(record, acknowledgment);

        // then
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("eventId가 누락된 메시지는 skip하고 ACK를 보낸다")
    void skipMessage_whenEventIdMissing() {
        // given
        String payload = """
                {"eventType":"COUPON_ISSUE_REQUESTED","data":{"couponId":100}}
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>("coupon-issue-requests", 0, 0, "100", payload);

        // when
        consumer.consume(record, acknowledgment);

        // then
        verify(couponIssueEventService, never()).processIssueRequest(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("알 수 없는 eventType은 skip하고 ACK를 보낸다")
    void skipMessage_whenUnknownEventType() {
        // given
        String payload = """
                {"eventId":"uuid-2","eventType":"UNKNOWN_TYPE","data":{}}
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>("coupon-issue-requests", 0, 0, "100", payload);

        // when
        consumer.consume(record, acknowledgment);

        // then
        verify(couponIssueEventService, never()).processIssueRequest(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("JSON 파싱 실패 시 skip하고 ACK를 보낸다")
    void skipMessage_whenJsonParsingFails() {
        // given
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "coupon-issue-requests", 0, 0, "100", "not-a-json");

        // when
        consumer.consume(record, acknowledgment);

        // then
        verify(couponIssueEventService, never()).processIssueRequest(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(acknowledgment).acknowledge();
    }
}
