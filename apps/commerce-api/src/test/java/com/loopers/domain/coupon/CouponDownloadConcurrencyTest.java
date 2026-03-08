package com.loopers.domain.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.domain.member.Member;
import com.loopers.support.error.CoreException;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * [동시성 테스트 - Concurrency Integration]
 *
 * 테스트 대상: CouponFacade.downloadCoupon() (중복 발급 방지 동시성 제어)
 * 테스트 유형: 동시성 통합 테스트
 * 테스트 범위: Facade(@Transactional) → Service → Repository → Database
 *
 * 주의: 각 스레드가 독립 트랜잭션을 가져야 하므로 클래스 레벨 @Transactional을 사용하지 않는다.
 */
@SpringBootTest
class CouponDownloadConcurrencyTest {

    @Autowired
    private CouponFacade couponFacade;

    @Autowired
    private CouponService couponService;

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Coupon createAndSaveCoupon(String name) {
        Coupon coupon = new Coupon(
                name,
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

    private Member createFakeMember(Long memberId) {
        try {
            Constructor<Member> constructor = Member.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Member member = constructor.newInstance();
            ReflectionTestUtils.setField(member, "id", memberId);
            return member;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create fake Member", e);
        }
    }

    @DisplayName("서로 다른 회원이 동시에 같은 쿠폰을 다운로드할 때,")
    @Nested
    class ConcurrentDownloadByDifferentMembers {

        @Test
        @DisplayName("모든 회원이 성공적으로 다운로드한다.")
        void allSucceed_whenConcurrentDownload() throws InterruptedException {
            // given - 테스트 데이터 준비
            // *테스트 시나리오: 서로 다른 10명의 회원이 동시에 같은 쿠폰을 다운로드하면? -> 10명 모두 성공
            int threadCount = 10;
            Coupon savedCoupon = createAndSaveCoupon("동시성 테스트 쿠폰");

            // 동시성 제어를 위한 도구 세팅
            ExecutorService executorService = Executors.newFixedThreadPool(threadCount); // 10개의 스레드를 관리하는 스레드 풀
            CountDownLatch readyLatch = new CountDownLatch(threadCount); // 모든 스레드가 준비되었는지 확인 (값: 10)
            CountDownLatch startLatch = new CountDownLatch(1); // 동시 출발 신호를 보냄 (값: 1)
            CountDownLatch doneLatch = new CountDownLatch(threadCount); // 모든 스레드가 끝났는지 확인 (값: 10)

            AtomicInteger successCount = new AtomicInteger(0); // 멀티스레드 환경에서 안전한 성공 카운터
            AtomicInteger failCount = new AtomicInteger(0); // 멀티스레드 환경에서 안전한 실패 카운터

            // when - 10개 스레드 동시 실행 (각 스레드가 다른 회원)
            for (int i = 1; i <= threadCount; i++) {
                Member fakeMember = createFakeMember((long) i); // 회원 1~10
                executorService.execute(() -> { // 메인 스레드가 워커 스레드에 작업을 등록
                    // 워커 스레드에서 실행되는 영역
                    try {
                        readyLatch.countDown(); // "나 준비됐어!" (10→9→...→0)
                        startLatch.await(); // 출발 신호 대기 (startLatch가 0이 될 때까지 블로킹)
                        couponFacade.downloadCoupon(fakeMember, savedCoupon.getId()); // 쿠폰 다운로드
                        successCount.incrementAndGet(); // 성공 카운트 +1
                    } catch (CoreException e) {
                        failCount.incrementAndGet(); // 실패 카운트 +1
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown(); // "나 끝났어!" (성공이든 실패든 반드시 실행)
                    }
                });
            }

            // 메인 스레드: 동기화 제어
            readyLatch.await(); // 10개 스레드 모두 준비될 때까지 메인 스레드 블로킹
            startLatch.countDown(); // 출발 신호! (1→0, 대기 중인 10개 워커 스레드가 동시에 깨어남)
            doneLatch.await(); // 10개 스레드 모두 끝날 때까지 메인 스레드 블로킹
            executorService.shutdown(); // 스레드 풀 정리

            // then
            List<MemberCoupon> issuedMemberCoupons = memberCouponRepository.findByCouponId(
                    savedCoupon.getId(),
                    org.springframework.data.domain.PageRequest.of(0, 100)
            ).getContent();

            assertAll(
                    () -> assertThat(successCount.get()).isEqualTo(threadCount),
                    () -> assertThat(failCount.get()).isEqualTo(0),
                    () -> assertThat(issuedMemberCoupons).hasSize(threadCount)
            );
        }
    }

    @DisplayName("같은 회원이 동시에 같은 쿠폰을 다운로드할 때,")
    @Nested
    class ConcurrentDownloadBySameMember {

        @Test
        @DisplayName("1번만 성공하고, 나머지는 CONFLICT 예외가 발생한다.")
        void onlyOneSucceeds_whenSameMemberConcurrentDownload() throws InterruptedException {
            // given - 테스트 데이터 준비
            // *테스트 시나리오: 같은 회원이 동시에 같은 쿠폰을 5번 다운로드하면? -> 1번만 성공, 4번은 중복 예외
            int threadCount = 5;
            Coupon savedCoupon = createAndSaveCoupon("중복 다운로드 테스트 쿠폰");
            Long sameMemberId = 1L;

            // 동시성 제어를 위한 도구 세팅
            ExecutorService executorService = Executors.newFixedThreadPool(threadCount); // 5개의 스레드를 관리하는 스레드 풀
            CountDownLatch readyLatch = new CountDownLatch(threadCount); // 모든 스레드가 준비되었는지 확인 (값: 5)
            CountDownLatch startLatch = new CountDownLatch(1); // 동시 출발 신호를 보냄 (값: 1)
            CountDownLatch doneLatch = new CountDownLatch(threadCount); // 모든 스레드가 끝났는지 확인 (값: 5)

            AtomicInteger successCount = new AtomicInteger(0); // 멀티스레드 환경에서 안전한 성공 카운터
            AtomicInteger failCount = new AtomicInteger(0); // 멀티스레드 환경에서 안전한 실패 카운터

            // when - 5개 스레드 동시 실행 (모두 같은 회원)
            for (int i = 0; i < threadCount; i++) {
                Member fakeMember = createFakeMember(sameMemberId); // 같은 회원 ID
                executorService.execute(() -> { // 메인 스레드가 워커 스레드에 작업을 등록
                    // 워커 스레드에서 실행되는 영역
                    try {
                        readyLatch.countDown(); // "나 준비됐어!" (5→4→...→0)
                        startLatch.await(); // 출발 신호 대기 (startLatch가 0이 될 때까지 블로킹)
                        couponFacade.downloadCoupon(fakeMember, savedCoupon.getId()); // 쿠폰 다운로드
                        successCount.incrementAndGet(); // 성공 카운트 +1
                    } catch (Exception e) {
                        failCount.incrementAndGet(); // 실패 카운트 +1 (중복 다운로드 예외)
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
            List<MemberCoupon> issuedMemberCoupons = memberCouponRepository.findByCouponId(
                    savedCoupon.getId(),
                    org.springframework.data.domain.PageRequest.of(0, 100)
            ).getContent();

            assertAll(
                    () -> assertThat(successCount.get()).isEqualTo(1),
                    () -> assertThat(failCount.get()).isEqualTo(threadCount - 1),
                    () -> assertThat(issuedMemberCoupons).hasSize(1)
            );
        }
    }
}
