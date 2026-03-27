package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.coupon.MemberCouponRepository;
import com.loopers.domain.coupon.MemberCouponStatus;
import com.loopers.domain.event.EventHandled;
import com.loopers.domain.event.EventHandledRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

/**
 * 선착순 쿠폰 발급 Consumer에서 호출하는 서비스.
 *
 * 동시성 제어 전략: Kafka partition key=couponId → 같은 쿠폰 요청은 순차 처리될 수 있도록, 같은 파티션에 배치
 * 멱등 처리: event_handled 테이블 + UNIQUE 제약 방어 (MetricsEventService와 동일 패턴).
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class CouponIssueEventService {

    // REQUESTED는 카운트에서 제외한다.
    // Kafka partition key=couponId로 같은 쿠폰 요청이 순차 처리되므로,
    // REQUESTED 상태의 레코드는 현재 처리 중인 건 이전에 이미 승인/거부가 완료된다.
    // Consumer를 여러 인스턴스로 스케일 아웃할 경우 REQUESTED도 카운트에 포함해야 한다.
    private static final Set<MemberCouponStatus> ISSUED_STATUSES =
            Set.of(MemberCouponStatus.AVAILABLE, MemberCouponStatus.USED);

    private final EventHandledRepository eventHandledRepository;
    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;

    /**
     * 선착순 쿠폰 발급 요청을 처리한다.
     *
     * 처리 흐름:
     *  - event_handled 확인 → 중복이면 skip
     *  - member_coupon 레코드 조회 (REQUESTED 상태)
     *  - Coupon 조회 → maxIssueCount 확인
     *  - 발급 수량 체크 → 초과면 FAILED, 이내면 AVAILABLE
     *  - event_handled INSERT (멱등 처리)
     *
     * @return true: 비즈니스 로직(승인/거부) 실행됨, false: skip(중복 또는 비정상 상태).
     *         skip의 경우에도 event_handled는 저장하여 재처리를 방지한다.
     */
    @Transactional
    public boolean processIssueRequest(String eventId, Long couponId, Long memberId, Long memberCouponId) {
        if (eventHandledRepository.existsByEventId(eventId)) {
            log.debug("이미 처리된 쿠폰 발급 이벤트 — skip: eventId={}", eventId);
            return false;
        }

        // member_coupon 레코드 조회 — 없으면 DB 커밋 실패 케이스, skip
        Optional<MemberCoupon> optMemberCoupon = memberCouponRepository.findById(memberCouponId);
        if (optMemberCoupon.isEmpty()) {
            log.warn("member_coupon 레코드 없음 — DB 커밋 실패 케이스, skip: memberCouponId={}", memberCouponId);
            saveEventHandled(eventId);
            return false;
        }

        MemberCoupon memberCoupon = optMemberCoupon.get();
        if (memberCoupon.getStatus() != MemberCouponStatus.REQUESTED) {
            log.warn("REQUESTED 상태가 아닌 member_coupon — skip: memberCouponId={}, status={}",
                    memberCouponId, memberCoupon.getStatus());
            saveEventHandled(eventId);
            return false;
        }

        // Coupon 조회 — maxIssueCount 확인
        Optional<Coupon> optCoupon = couponRepository.findById(couponId);
        if (optCoupon.isEmpty()) {
            memberCoupon.reject("쿠폰을 찾을 수 없습니다.");
            memberCouponRepository.save(memberCoupon);
            saveEventHandled(eventId);
            return true;
        }

        Coupon coupon = optCoupon.get();
        long issuedCount = memberCouponRepository.countByCouponIdAndStatusIn(couponId, ISSUED_STATUSES);

        if (issuedCount >= coupon.getMaxIssueCount()) {
            memberCoupon.reject("수량 초과");
            memberCouponRepository.save(memberCoupon);
            log.info("선착순 쿠폰 수량 초과 — FAILED: couponId={}, memberId={}, issuedCount={}/{}",
                    couponId, memberId, issuedCount, coupon.getMaxIssueCount());
        } else {
            memberCoupon.approve();
            memberCouponRepository.save(memberCoupon);
            log.info("선착순 쿠폰 발급 성공 — AVAILABLE: couponId={}, memberId={}, issuedCount={}/{}",
                    couponId, memberId, issuedCount + 1, coupon.getMaxIssueCount());
        }

        saveEventHandled(eventId);
        return true;
    }

    private void saveEventHandled(String eventId) {
        try {
            eventHandledRepository.save(new EventHandled(eventId));
        } catch (DataIntegrityViolationException e) {
            log.info("event_handled UNIQUE 제약 위반 — 동시 중복 요청 방어: eventId={}", eventId);
            throw e;
        }
    }
}
