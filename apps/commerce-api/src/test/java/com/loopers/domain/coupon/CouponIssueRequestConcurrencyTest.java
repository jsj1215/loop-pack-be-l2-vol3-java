package com.loopers.domain.coupon;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * [동시성 테스트 - Concurrency Integration]
 *
 * 테스트 대상: CouponIssueService.createIssueRequest() (중복 발급 요청 방지)
 * 테스트 유형: 동시성 통합 테스트
 * 테스트 범위: Service → Repository → Database (Testcontainers)
 *
 * 검증 목적:
 * - 같은 회원이 동시에 같은 쿠폰에 발급 요청을 보낼 때 UK(member_id, coupon_id) 제약으로 1건만 성공하는지
 * - 서로 다른 회원이 동시에 같은 쿠폰에 발급 요청을 보낼 때 모두 성공하는지
 *
 * 주의: 각 스레드가 독립 트랜잭션을 가져야 하므로 클래스 레벨 @Transactional을 사용하지 않는다.
 */
@SpringBootTest
@DisplayName("선착순 쿠폰 발급 요청 동시성 테스트")
class CouponIssueRequestConcurrencyTest {

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Coupon createAndSaveLimitedCoupon(int maxIssueCount) {
        ZonedDateTime now = ZonedDateTime.now();
        Coupon coupon = new Coupon("선착순 " + maxIssueCount + "장 쿠폰",
                CouponScope.CART, null,
                DiscountType.FIXED_AMOUNT, 5000, 10000, 0,
                now.minusDays(1), now.plusDays(30), maxIssueCount);
        return couponRepository.save(coupon);
    }

    @Nested
    @DisplayName("같은 회원이 동시에 같은 쿠폰에 발급 요청할 때,")
    class SameMemberConcurrentRequest {

        @Test
        @DisplayName("UK(member_id, coupon_id) 제약으로 1건만 성공하고 나머지는 예외가 발생한다")
        void onlyOneSucceeds_whenSameMemberConcurrentRequest() throws InterruptedException {
            // given
            int threadCount = 5;
            Coupon coupon = createAndSaveLimitedCoupon(100);
            Long sameMemberId = 1L;

            ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            // when
            for (int i = 0; i < threadCount; i++) {
                executorService.execute(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await();
                        couponIssueService.createIssueRequest(sameMemberId, coupon.getId());
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            readyLatch.await();
            startLatch.countDown();
            doneLatch.await();
            executorService.shutdown();

            // then
            assertAll(
                    () -> assertThat(successCount.get()).isEqualTo(1),
                    () -> assertThat(failCount.get()).isEqualTo(threadCount - 1)
            );
        }
    }

    @Nested
    @DisplayName("서로 다른 회원이 동시에 같은 쿠폰에 발급 요청할 때,")
    class DifferentMembersConcurrentRequest {

        @Test
        @DisplayName("모든 회원이 성공적으로 REQUESTED 상태를 생성한다")
        void allSucceed_whenDifferentMembersConcurrentRequest() throws InterruptedException {
            // given
            int threadCount = 10;
            Coupon coupon = createAndSaveLimitedCoupon(100);

            ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            // when
            for (int i = 1; i <= threadCount; i++) {
                long memberId = i;
                executorService.execute(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await();
                        couponIssueService.createIssueRequest(memberId, coupon.getId());
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            readyLatch.await();
            startLatch.countDown();
            doneLatch.await();
            executorService.shutdown();

            // then
            assertAll(
                    () -> assertThat(successCount.get()).isEqualTo(threadCount),
                    () -> assertThat(failCount.get()).isEqualTo(0)
            );
        }
    }
}
