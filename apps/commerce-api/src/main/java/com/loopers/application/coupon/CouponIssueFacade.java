package com.loopers.application.coupon;

import com.loopers.application.event.KafkaPayloadSerializer;
import com.loopers.domain.coupon.CouponIssueService;
import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.member.Member;
import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

// 선착순 쿠폰 발급 요청 유스케이스.
//
// Outbox Pattern을 사용하여 MemberCoupon INSERT(REQUESTED)와 이벤트 저장의 원자성을 보장한다.
// - MemberCoupon INSERT + OutboxEvent INSERT → 같은 TX로 원자적 커밋
// - TX 커밋 후 즉시 Kafka 발행 시도 (빠른 응답)
// - 발행 실패 시 OutboxScheduler가 10초 폴링으로 재시도 (안전망)
//
// 쿠폰 발급은 사용자 대면 기능이므로 유실 시 REQUESTED 상태에서 영원히 멈추게 된다.
// 통계성 이벤트(좋아요, 조회수)와 달리 Outbox Pattern이 필요한 이유이다.
@Slf4j
@RequiredArgsConstructor
@Component
public class CouponIssueFacade {

    private static final String TOPIC = "coupon-issue-requests";
    private static final String AGGREGATE_TYPE_COUPON = "COUPON";
    private static final String EVENT_TYPE = "COUPON_ISSUE_REQUESTED";

    private final CouponIssueService couponIssueService;
    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final KafkaPayloadSerializer payloadSerializer;

    // 선착순 쿠폰 발급 요청.
    // MemberCoupon(REQUESTED) + OutboxEvent를 같은 TX에서 저장한 후,
    // 커밋 후 즉시 Kafka 발행을 시도한다.
    @Transactional
    public CouponIssueStatusInfo requestIssue(Member member, Long couponId) {
        MemberCoupon memberCoupon = couponIssueService.createIssueRequest(member.getId(), couponId);

        OutboxEvent outboxEvent = createCouponIssueOutbox(couponId, member.getId(), memberCoupon.getId());
        outboxEventRepository.save(outboxEvent);

        // TX 커밋 후 즉시 Kafka 발행 시도 (OutboxEventListener가 AFTER_COMMIT에서 처리)
        eventPublisher.publishEvent(new OutboxEvent.Published(outboxEvent));

        log.info("쿠폰 발급 요청 Outbox 저장: couponId={}, memberId={}, eventId={}",
                couponId, member.getId(), outboxEvent.getEventId());

        return CouponIssueStatusInfo.from(memberCoupon);
    }

    // TX 커밋 후 즉시 Kafka 발행을 시도한다.
    // 이 메서드는 TransactionSynchronization.afterCommit()에서 호출되도록 설계되어 있지 않고,
    // 별도 이벤트 리스너(OutboxEventListener)에서 AFTER_COMMIT 시점에 처리한다.
    // 실패 시 OutboxScheduler가 재시도한다.

    @Transactional(readOnly = true)
    public CouponIssueStatusInfo getIssueStatus(Member member, Long couponId) {
        MemberCoupon memberCoupon = couponIssueService.findIssueStatus(member.getId(), couponId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "발급 요청을 찾을 수 없습니다."));
        return CouponIssueStatusInfo.from(memberCoupon);
    }

    private OutboxEvent createCouponIssueOutbox(Long couponId, Long memberId, Long memberCouponId) {
        OutboxEvent outboxEvent = OutboxEvent.create(
                AGGREGATE_TYPE_COUPON,
                String.valueOf(couponId),
                EVENT_TYPE,
                TOPIC,
                ""
        );

        Map<String, Object> data = Map.of(
                "couponId", couponId,
                "memberId", memberId,
                "memberCouponId", memberCouponId
        );
        outboxEvent.updatePayload(
                payloadSerializer.serialize(payloadSerializer.buildEnvelope(outboxEvent.getEventId(), EVENT_TYPE, data)));
        return outboxEvent;
    }
}
