package com.loopers.application.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.event.KafkaPayloadSerializer;
import com.loopers.domain.coupon.CouponIssueService;
import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.coupon.MemberCouponStatus;
import com.loopers.domain.member.Member;
import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// CouponIssueFacade 단위 테스트.
//
// Outbox Pattern을 사용하여 MemberCoupon INSERT(REQUESTED)와 이벤트 저장의 원자성을 보장.
// - 같은 TX에서 MemberCoupon INSERT + OutboxEvent INSERT
// - TX 커밋 후 OutboxEvent.Published 이벤트로 즉시 Kafka 발행 시도
// - 실패 시 OutboxScheduler가 재시도
@DisplayName("CouponIssueFacade 단위 테스트")
@ExtendWith(MockitoExtension.class)
class CouponIssueFacadeTest {

    @Mock
    private CouponIssueService couponIssueService;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private CouponIssueFacade couponIssueFacade;

    @BeforeEach
    void setUp() {
        couponIssueFacade = new CouponIssueFacade(
                couponIssueService, outboxEventRepository, eventPublisher, new KafkaPayloadSerializer(new ObjectMapper()));
    }

    private Member mockMember(Long id) {
        Member member = mock(Member.class);
        when(member.getId()).thenReturn(id);
        return member;
    }

    @Nested
    @DisplayName("발급 요청 — Outbox Pattern")
    class RequestIssue {

        @Test
        @DisplayName("MemberCoupon(REQUESTED)과 OutboxEvent를 같은 TX에서 저장한다")
        void requestsIssueAndSavesOutbox() {
            // given
            Member member = mockMember(1L);
            Long couponId = 100L;

            MemberCoupon memberCoupon = MemberCoupon.createRequested(1L, couponId);
            ReflectionTestUtils.setField(memberCoupon, "id", 10L);
            when(couponIssueService.createIssueRequest(1L, couponId)).thenReturn(memberCoupon);

            // when
            CouponIssueStatusInfo result = couponIssueFacade.requestIssue(member, couponId);

            // then
            assertThat(result.status()).isEqualTo(MemberCouponStatus.REQUESTED);
            assertThat(result.failReason()).isNull();
            verify(couponIssueService).createIssueRequest(1L, couponId);

            // Outbox 저장 검증
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(captor.capture());
            OutboxEvent saved = captor.getValue();
            assertThat(saved.getAggregateType()).isEqualTo("COUPON");
            assertThat(saved.getAggregateId()).isEqualTo("100");
            assertThat(saved.getEventType()).isEqualTo("COUPON_ISSUE_REQUESTED");
            assertThat(saved.getTopic()).isEqualTo("coupon-issue-requests");
            assertThat(saved.isPublished()).isFalse();
            assertThat(saved.getPayload()).contains("\"couponId\"");
            assertThat(saved.getPayload()).contains("\"memberId\"");
            assertThat(saved.getPayload()).contains("\"memberCouponId\"");

            // TX 커밋 후 즉시 Kafka 발행을 위한 이벤트 발행 검증
            verify(eventPublisher).publishEvent(any(OutboxEvent.Published.class));
        }

        @Test
        @DisplayName("Service 예외 시 Outbox 저장 없이 실패한다")
        void propagatesServiceException() {
            // given
            Member member = mockMember(1L);
            when(couponIssueService.createIssueRequest(1L, 100L))
                    .thenThrow(new CoreException(ErrorType.CONFLICT, "이미 발급 요청한 쿠폰입니다."));

            // when & then
            assertThatThrownBy(() -> couponIssueFacade.requestIssue(member, 100L))
                    .isInstanceOf(CoreException.class)
                    .satisfies(ex -> assertThat(((CoreException) ex).getErrorType()).isEqualTo(ErrorType.CONFLICT));

            verify(outboxEventRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("발급 상태 조회")
    class GetIssueStatus {

        @Test
        @DisplayName("REQUESTED 상태를 반환한다")
        void returnsRequestedStatus() {
            // given
            Member member = mockMember(1L);
            Long couponId = 100L;
            MemberCoupon memberCoupon = MemberCoupon.createRequested(1L, couponId);
            when(couponIssueService.findIssueStatus(1L, couponId))
                    .thenReturn(Optional.of(memberCoupon));

            // when
            CouponIssueStatusInfo result = couponIssueFacade.getIssueStatus(member, couponId);

            // then
            assertThat(result.status()).isEqualTo(MemberCouponStatus.REQUESTED);
        }

        @Test
        @DisplayName("FAILED 상태와 실패 사유를 반환한다")
        void returnsFailedStatusWithReason() {
            // given
            Member member = mockMember(1L);
            Long couponId = 100L;
            MemberCoupon memberCoupon = MemberCoupon.createRequested(1L, couponId);
            memberCoupon.reject("수량 초과");
            when(couponIssueService.findIssueStatus(1L, couponId))
                    .thenReturn(Optional.of(memberCoupon));

            // when
            CouponIssueStatusInfo result = couponIssueFacade.getIssueStatus(member, couponId);

            // then
            assertThat(result.status()).isEqualTo(MemberCouponStatus.FAILED);
            assertThat(result.failReason()).isEqualTo("수량 초과");
        }

        @Test
        @DisplayName("발급 요청이 없으면 NOT_FOUND 예외가 발생한다")
        void throwsWhenNotFound() {
            // given
            Member member = mockMember(1L);
            when(couponIssueService.findIssueStatus(1L, 100L))
                    .thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> couponIssueFacade.getIssueStatus(member, 100L))
                    .isInstanceOf(CoreException.class)
                    .satisfies(ex -> assertThat(((CoreException) ex).getErrorType()).isEqualTo(ErrorType.NOT_FOUND));
        }
    }
}
