package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.coupon.MemberCouponStatus;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.infrastructure.coupon.MemberCouponJpaRepository;
import com.loopers.infrastructure.event.EventHandledJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * [통합 테스트 - Service Integration]
 *
 * 테스트 대상: CouponIssueEventService (Application Layer - Streamer)
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: Service → Repository → Database (Testcontainers)
 *
 * 검증 목적:
 * - 선착순 쿠폰 발급 로직이 실제 DB에서 올바르게 동작하는지
 * - 멱등 처리(event_handled)가 실제 DB에서 보장되는지
 * - 상태 전환(REQUESTED → AVAILABLE/FAILED)이 정확하게 반영되는지
 */
@SpringBootTest
@DisplayName("CouponIssueEventService 통합 테스트")
class CouponIssueEventServiceIntegrationTest {

    @Autowired
    private CouponIssueEventService couponIssueEventService;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private MemberCouponJpaRepository memberCouponJpaRepository;

    @Autowired
    private EventHandledJpaRepository eventHandledJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Coupon createLimitedCoupon(int maxIssueCount) {
        try {
            var constructor = Coupon.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Coupon coupon = constructor.newInstance();
            ReflectionTestUtils.setField(coupon, "name", "선착순 " + maxIssueCount + "장 쿠폰");
            ReflectionTestUtils.setField(coupon, "maxIssueCount", maxIssueCount);
            return couponJpaRepository.save(coupon);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private MemberCoupon createRequestedMemberCoupon(Long memberId, Long couponId) {
        try {
            var constructor = MemberCoupon.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            MemberCoupon mc = constructor.newInstance();
            ReflectionTestUtils.setField(mc, "memberId", memberId);
            ReflectionTestUtils.setField(mc, "couponId", couponId);
            ReflectionTestUtils.setField(mc, "status", MemberCouponStatus.REQUESTED);
            return memberCouponJpaRepository.save(mc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("발급 승인")
    class ApproveIssue {

        @Test
        @DisplayName("수량 이내면 AVAILABLE 상태로 전환된다")
        void approvesWhenWithinLimit() {
            // given
            Coupon coupon = createLimitedCoupon(10);
            MemberCoupon mc = createRequestedMemberCoupon(1L, coupon.getId());
            String eventId = UUID.randomUUID().toString();

            // when
            boolean result = couponIssueEventService.processIssueRequest(
                    eventId, coupon.getId(), 1L, mc.getId()
            );

            // then
            assertAll(
                    () -> assertThat(result).isTrue(),
                    () -> assertThat(memberCouponJpaRepository.findById(mc.getId()).get().getStatus())
                            .isEqualTo(MemberCouponStatus.AVAILABLE),
                    () -> assertThat(eventHandledJpaRepository.existsById(eventId)).isTrue()
            );
        }
    }

    @Nested
    @DisplayName("발급 거부")
    class RejectIssue {

        @Test
        @DisplayName("수량 초과 시 FAILED 상태와 실패 사유가 저장된다")
        void rejectsWhenExceedsLimit() {
            // given
            Coupon coupon = createLimitedCoupon(1);

            // 1장을 먼저 발급(AVAILABLE 상태로 만들기)
            MemberCoupon first = createRequestedMemberCoupon(1L, coupon.getId());
            couponIssueEventService.processIssueRequest(
                    UUID.randomUUID().toString(), coupon.getId(), 1L, first.getId()
            );

            // 2번째 요청
            MemberCoupon second = createRequestedMemberCoupon(2L, coupon.getId());
            String eventId = UUID.randomUUID().toString();

            // when
            boolean result = couponIssueEventService.processIssueRequest(
                    eventId, coupon.getId(), 2L, second.getId()
            );

            // then
            MemberCoupon updated = memberCouponJpaRepository.findById(second.getId()).get();
            assertAll(
                    () -> assertThat(result).isTrue(),
                    () -> assertThat(updated.getStatus()).isEqualTo(MemberCouponStatus.FAILED),
                    () -> assertThat(updated.getFailReason()).isEqualTo("수량 초과")
            );
        }
    }

    @Nested
    @DisplayName("멱등 처리")
    class Idempotency {

        @Test
        @DisplayName("같은 eventId로 중복 호출하면 두 번째는 skip한다")
        void skipsAlreadyHandledEvent() {
            // given
            Coupon coupon = createLimitedCoupon(10);
            MemberCoupon mc = createRequestedMemberCoupon(1L, coupon.getId());
            String eventId = UUID.randomUUID().toString();

            couponIssueEventService.processIssueRequest(
                    eventId, coupon.getId(), 1L, mc.getId()
            );

            // when
            boolean secondResult = couponIssueEventService.processIssueRequest(
                    eventId, coupon.getId(), 1L, mc.getId()
            );

            // then
            assertThat(secondResult).isFalse();
        }

        @Test
        @DisplayName("member_coupon 레코드가 없으면 skip하고 event_handled에 기록한다")
        void skipsWhenMemberCouponNotFound() {
            // given
            Coupon coupon = createLimitedCoupon(10);
            String eventId = UUID.randomUUID().toString();

            // when — 존재하지 않는 memberCouponId
            boolean result = couponIssueEventService.processIssueRequest(
                    eventId, coupon.getId(), 1L, 999L
            );

            // then
            assertThat(result).isFalse();
            assertThat(eventHandledJpaRepository.existsById(eventId)).isTrue();
        }
    }
}
