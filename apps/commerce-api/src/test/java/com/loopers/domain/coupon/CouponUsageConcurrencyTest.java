package com.loopers.domain.coupon;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

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
 * 테스트 대상: CouponService.useCoupon() (낙관적 락 동시성 제어)
 * 테스트 유형: 동시성 통합 테스트
 * 테스트 범위: TransactionTemplate → Service → MemberCoupon(@Version) → Database
 *
 * 주의:
 * - 각 스레드가 독립 트랜잭션을 가져야 하므로 클래스 레벨 @Transactional을 사용하지 않는다.
 * - useCoupon()은 @Transactional이 없으므로(Facade에서 트랜잭션 제공),
 *   테스트에서는 TransactionTemplate으로 트랜잭션을 감싼다.
 */
@SpringBootTest
class CouponUsageConcurrencyTest {

    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Coupon createAndSaveCoupon() {
        Coupon coupon = new Coupon(
                "테스트 쿠폰",
                CouponScope.CART,
                null,
                DiscountType.FIXED_AMOUNT,
                5000,
                10000,
                0,
                ZonedDateTime.now().minusDays(1),
                ZonedDateTime.now().plusDays(30)
        );
        return couponRepository.save(coupon);
    }

    private MemberCoupon createAndSaveMemberCoupon(Long memberId, Long couponId) {
        MemberCoupon memberCoupon = new MemberCoupon(memberId, couponId);
        return memberCouponRepository.save(memberCoupon);
    }

    @DisplayName("동일한 쿠폰으로 여러 기기에서 동시에 사용을 시도할 때,")
    @Nested
    class ConcurrentCouponUsage {

        @Test
        @DisplayName("1번만 성공하고, 나머지는 예외가 발생한다.")
        void onlyOneSucceeds_whenConcurrentUsage() throws InterruptedException {
            // given - 테스트 데이터 준비
            // *테스트 시나리오: 같은 쿠폰을 5개 기기에서 동시에 사용하면? -> 1번만 성공, 4번은 낙관적 락 예외
            int threadCount = 5;
            Coupon savedCoupon = createAndSaveCoupon();
            Long memberId = 1L;
            MemberCoupon savedMemberCoupon = createAndSaveMemberCoupon(memberId, savedCoupon.getId());

            // 동시성 제어를 위한 도구 세팅
            ExecutorService executorService = Executors.newFixedThreadPool(threadCount); // 5개의 스레드를 관리하는 스레드 풀
            CountDownLatch readyLatch = new CountDownLatch(threadCount); // 모든 스레드가 준비되었는지 확인 (값: 5)
            CountDownLatch startLatch = new CountDownLatch(1); // 동시 출발 신호를 보냄 (값: 1)
            CountDownLatch doneLatch = new CountDownLatch(threadCount); // 모든 스레드가 끝났는지 확인 (값: 5)

            AtomicInteger successCount = new AtomicInteger(0); // 멀티스레드 환경에서 안전한 성공 카운터
            AtomicInteger failCount = new AtomicInteger(0); // 멀티스레드 환경에서 안전한 실패 카운터

            // when - 5개 스레드 동시 실행 (각 스레드가 다른 주문에서 같은 쿠폰 사용 시도)
            for (int i = 0; i < threadCount; i++) {
                long orderId = i + 1; // 주문 1~5
                executorService.execute(() -> { // 메인 스레드가 워커 스레드에 작업을 등록
                    // 워커 스레드에서 실행되는 영역
                    try {
                        readyLatch.countDown(); // "나 준비됐어!" (5→4→...→0)
                        startLatch.await(); // 출발 신호 대기 (startLatch가 0이 될 때까지 블로킹)
                        transactionTemplate.executeWithoutResult(status -> { // 스레드별 독립 트랜잭션 (Service에 @Transactional 없으므로)
                            couponService.useCoupon(memberId, savedMemberCoupon.getId(), orderId); // 쿠폰 사용 (낙관적 락 @Version)
                        });
                        successCount.incrementAndGet(); // 성공 카운트 +1
                    } catch (Exception e) {
                        failCount.incrementAndGet(); // 실패 카운트 +1 (낙관적 락 충돌 예외)
                    } finally {
                        doneLatch.countDown(); // "나 끝났어!" (성공이든 실패든 반드시 실행)
                    }
                });
            }

            // 메인 스레드: 동기화 제어
            readyLatch.await(); // 5개 스레드 모두 준비될 때까지 메인 스레드 블로킹
            startLatch.countDown(); // 출발 신호! (1→0, 대기 중인 5개 워커 스레드가 동시에 깨어남)
            doneLatch.await(); // 5개 스레드 모두 끝날 때까지 메인 스레드 블로킹
            executorService.shutdown(); // 스레드 풀 정리

            // then
            MemberCoupon updatedMemberCoupon = couponService.getMemberCoupon(savedMemberCoupon.getId());

            assertAll(
                    () -> assertThat(successCount.get()).isEqualTo(1),
                    () -> assertThat(failCount.get()).isEqualTo(threadCount - 1),
                    () -> assertThat(updatedMemberCoupon.getStatus()).isEqualTo(MemberCouponStatus.USED)
            );
        }
    }
}
