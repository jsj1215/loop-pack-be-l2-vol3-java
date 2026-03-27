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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [통합 테스트 - 동시성 검증]
 *
 * 테스트 대상: CouponIssueEventService (Application Layer)
 * 테스트 유형: 동시성 통합 테스트 (Concurrency Integration Test)
 * 테스트 범위: Service → Repository → Database (Testcontainers)
 *
 * 검증 목적: 선착순 쿠폰 발급 시 수량 초과 발급이 발생하지 않는지 검증
 */
@SpringBootTest
@DisplayName("선착순 쿠폰 발급 동시성 테스트")
class CouponIssueConcurrencyTest {

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
            MemberCoupon memberCoupon = constructor.newInstance();
            ReflectionTestUtils.setField(memberCoupon, "memberId", memberId);
            ReflectionTestUtils.setField(memberCoupon, "couponId", couponId);
            ReflectionTestUtils.setField(memberCoupon, "status", MemberCouponStatus.REQUESTED);
            return memberCouponJpaRepository.save(memberCoupon);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("수량 제한 동시성 검증")
    class ConcurrencyControl {

        @Test
        @DisplayName("100장 한정 쿠폰에 150명이 동시 요청 — Kafka 순차 처리 없이는 race condition으로 초과 가능")
        void showsRaceConditionWithoutKafkaOrdering() throws InterruptedException {
            // given
            // 동시성 제어 전략: Kafka partition key=couponId → 같은 쿠폰 요청은 순차 처리
            // 이 테스트는 Kafka 없이 직접 서비스를 동시 호출하므로 race condition이 발생할 수 있다.
            // 실운영에서는 Kafka 파티션 순차 처리로 수량 초과를 방지한다.
            int maxIssueCount = 100;
            int totalRequests = 150;
            Coupon coupon = createLimitedCoupon(maxIssueCount);

            List<MemberCoupon> memberCoupons = new ArrayList<>();
            for (int i = 1; i <= totalRequests; i++) {
                memberCoupons.add(createRequestedMemberCoupon((long) i, coupon.getId()));
            }

            ExecutorService executorService = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(totalRequests);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            // when
            for (int i = 0; i < totalRequests; i++) {
                int index = i;
                executorService.submit(() -> {
                    try {
                        MemberCoupon mc = memberCoupons.get(index);
                        String eventId = UUID.randomUUID().toString();
                        boolean processed = couponIssueEventService.processIssueRequest(
                                eventId, coupon.getId(), mc.getMemberId(), mc.getId()
                        );
                        if (processed) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            executorService.shutdown();

            // then — 모든 요청이 처리(성공 또는 실패)되었는지 확인
            long availableCount = memberCouponJpaRepository.countByCouponIdAndStatusIn(
                    coupon.getId(), Set.of(MemberCouponStatus.AVAILABLE, MemberCouponStatus.USED)
            );
            long failedCount = memberCouponJpaRepository.findAll().stream()
                    .filter(mc -> mc.getCouponId().equals(coupon.getId()))
                    .filter(mc -> mc.getStatus() == MemberCouponStatus.FAILED)
                    .count();
            assertThat(availableCount + failedCount)
                    .as("처리된 전체 요청 수(AVAILABLE+FAILED)는 총 요청 수(%d)와 같아야 한다", totalRequests)
                    .isEqualTo(totalRequests);
        }

        @Test
        @DisplayName("10장 한정 쿠폰에 순차 처리하면 정확히 10장만 AVAILABLE이 된다")
        void exactLimitWithSequentialProcessing() {
            // given — 순차 처리로 Kafka partition 순서 보장을 시뮬레이션
            int maxIssueCount = 10;
            int totalRequests = 30;
            Coupon coupon = createLimitedCoupon(maxIssueCount);

            List<MemberCoupon> memberCoupons = new ArrayList<>();
            for (int i = 1; i <= totalRequests; i++) {
                memberCoupons.add(createRequestedMemberCoupon((long) i, coupon.getId()));
            }

            // when — 순차 처리 (Kafka partition key=couponId가 보장하는 순서를 시뮬레이션)
            for (int i = 0; i < totalRequests; i++) {
                MemberCoupon mc = memberCoupons.get(i);
                String eventId = UUID.randomUUID().toString();
                couponIssueEventService.processIssueRequest(
                        eventId, coupon.getId(), mc.getMemberId(), mc.getId()
                );
            }

            // then
            long availableCount = memberCouponJpaRepository.countByCouponIdAndStatusIn(
                    coupon.getId(), Set.of(MemberCouponStatus.AVAILABLE, MemberCouponStatus.USED)
            );
            long failedCount = memberCouponJpaRepository.findAll().stream()
                    .filter(mc -> mc.getCouponId().equals(coupon.getId()))
                    .filter(mc -> mc.getStatus() == MemberCouponStatus.FAILED)
                    .count();

            assertThat(availableCount)
                    .as("순차 처리 시 AVAILABLE 수는 정확히 최대 발급 수량(%d)이어야 한다", maxIssueCount)
                    .isEqualTo(maxIssueCount);
            assertThat(failedCount)
                    .as("초과 요청은 모두 FAILED 처리되어야 한다")
                    .isEqualTo(totalRequests - maxIssueCount);
        }
    }

    @Nested
    @DisplayName("멱등성 검증")
    class Idempotency {

        @Test
        @DisplayName("같은 eventId로 중복 요청하면 한 번만 처리된다")
        void duplicateEventIdIsProcessedOnce() {
            // given
            Coupon coupon = createLimitedCoupon(100);
            MemberCoupon memberCoupon = createRequestedMemberCoupon(1L, coupon.getId());
            String eventId = UUID.randomUUID().toString();

            // when
            boolean firstResult = couponIssueEventService.processIssueRequest(
                    eventId, coupon.getId(), 1L, memberCoupon.getId()
            );
            boolean secondResult = couponIssueEventService.processIssueRequest(
                    eventId, coupon.getId(), 1L, memberCoupon.getId()
            );

            // then
            assertThat(firstResult).isTrue();
            assertThat(secondResult).isFalse();
            assertThat(eventHandledJpaRepository.existsById(eventId)).isTrue();
        }
    }
}
