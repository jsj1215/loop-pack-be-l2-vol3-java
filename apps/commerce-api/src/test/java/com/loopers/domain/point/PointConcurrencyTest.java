package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * [동시성 테스트 - Concurrency Integration]
 *
 * 테스트 대상: PointService.usePoint() (비관적 락 동시성 제어)
 * 테스트 유형: 동시성 통합 테스트
 * 테스트 범위: TransactionTemplate → Service → Repository(PESSIMISTIC_WRITE) → Database
 *
 * 주의:
 * - 각 스레드가 독립 트랜잭션을 가져야 하므로 클래스 레벨 @Transactional을 사용하지 않는다.
 * - usePoint()은 @Transactional이 없으므로(Facade에서 트랜잭션 제공),
 *   테스트에서는 TransactionTemplate으로 트랜잭션을 감싼다.
 */
@SpringBootTest
class PointConcurrencyTest {

    @Autowired
    private PointService pointService;

    @Autowired
    private PointRepository pointRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("여러 스레드가 동시에 같은 회원의 포인트를 사용할 때,")
    @Nested
    class ConcurrentPointUsage {

        @Test
        @DisplayName("잔액만큼만 성공하고, 나머지는 BAD_REQUEST 예외가 발생한다.")
        void onlyBalanceSucceeds_whenConcurrentUsage() throws InterruptedException {
            // given - 테스트 데이터 준비
            // *테스트 시나리오: 잔액 5000원인 회원이 10번 동시에 1000원씩 사용하면? -> 5번 성공, 5번 잔액 부족 예외
            Long memberId = 1L;
            int initialBalance = 5000;
            int useAmountPerThread = 1000;
            int threadCount = 10;

            transactionTemplate.executeWithoutResult(status -> {
                pointService.createPoint(memberId);
                pointService.chargePoint(memberId, initialBalance, "테스트 충전");
            });

            // 동시성 제어를 위한 도구 세팅
            ExecutorService executorService = Executors.newFixedThreadPool(threadCount); // 10개의 스레드를 관리하는 스레드 풀
            CountDownLatch readyLatch = new CountDownLatch(threadCount); // 모든 스레드가 준비되었는지 확인 (값: 10)
            CountDownLatch startLatch = new CountDownLatch(1); // 동시 출발 신호를 보냄 (값: 1)
            CountDownLatch doneLatch = new CountDownLatch(threadCount); // 모든 스레드가 끝났는지 확인 (값: 10)

            AtomicInteger successCount = new AtomicInteger(0); // 멀티스레드 환경에서 안전한 성공 카운터
            AtomicInteger failCount = new AtomicInteger(0); // 멀티스레드 환경에서 안전한 실패 카운터

            // when - 10개 스레드 동시 실행 (각 스레드가 다른 주문에서 포인트 사용 시도)
            for (int i = 0; i < threadCount; i++) {
                long orderId = i + 1; // 주문 1~10
                executorService.execute(() -> { // 메인 스레드가 워커 스레드에 작업을 등록
                    // 워커 스레드에서 실행되는 영역
                    try {
                        readyLatch.countDown(); // "나 준비됐어!" (10→9→...→0)
                        startLatch.await(); // 출발 신호 대기 (startLatch가 0이 될 때까지 블로킹)
                        transactionTemplate.executeWithoutResult(status -> { // 스레드별 독립 트랜잭션 (Service에 @Transactional 없으므로)
                            pointService.usePoint(memberId, useAmountPerThread, "주문 사용", orderId); // 포인트 사용 (비관적 락 PESSIMISTIC_WRITE)
                        });
                        successCount.incrementAndGet(); // 성공 카운트 +1
                    } catch (CoreException e) {
                        if (e.getErrorType() == ErrorType.BAD_REQUEST) {
                            failCount.incrementAndGet(); // 실패 카운트 +1 (잔액 부족 예외)
                        }
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
            int expectedSuccessCount = initialBalance / useAmountPerThread;
            int balance = pointService.getBalance(memberId);

            assertAll(
                    () -> assertThat(successCount.get()).isEqualTo(expectedSuccessCount),
                    () -> assertThat(failCount.get()).isEqualTo(threadCount - expectedSuccessCount),
                    () -> assertThat(balance).isEqualTo(0)
            );
        }
    }

    @DisplayName("충전과 사용이 동시에 발생할 때,")
    @Nested
    class ConcurrentChargeAndUsage {

        @Test
        @DisplayName("최종 잔액이 정확히 반영된다.")
        void balanceIsCorrect_whenConcurrentChargeAndUse() throws InterruptedException {
            // given - 테스트 데이터 준비
            // *테스트 시나리오: 잔액 10000원인 회원이 충전 5회(각 1000원) + 사용 5회(각 1000원) 동시에 하면? -> 최종 잔액 10000원 (10000+5000-5000)
            Long memberId = 1L;
            int initialBalance = 10000;
            int chargeAmount = 1000;
            int useAmount = 1000;
            int chargeCount = 5;
            int useCount = 5;
            int threadCount = chargeCount + useCount;

            transactionTemplate.executeWithoutResult(status -> {
                pointService.createPoint(memberId);
                pointService.chargePoint(memberId, initialBalance, "테스트 충전");
            });

            // 동시성 제어를 위한 도구 세팅
            ExecutorService executorService = Executors.newFixedThreadPool(threadCount); // 10개의 스레드를 관리하는 스레드 풀
            CountDownLatch readyLatch = new CountDownLatch(threadCount); // 모든 스레드가 준비되었는지 확인 (값: 10)
            CountDownLatch startLatch = new CountDownLatch(1); // 동시 출발 신호를 보냄 (값: 1)
            CountDownLatch doneLatch = new CountDownLatch(threadCount); // 모든 스레드가 끝났는지 확인 (값: 10)

            AtomicInteger successCount = new AtomicInteger(0); // 멀티스레드 환경에서 안전한 성공 카운터

            // when - 충전 5회 (워커 스레드 5개)
            for (int i = 0; i < chargeCount; i++) {
                executorService.execute(() -> { // 메인 스레드가 워커 스레드에 작업을 등록
                    // 워커 스레드에서 실행되는 영역
                    try {
                        readyLatch.countDown(); // "나 준비됐어!" (10→9→...→0)
                        startLatch.await(); // 출발 신호 대기 (startLatch가 0이 될 때까지 블로킹)
                        transactionTemplate.executeWithoutResult(status -> { // 스레드별 독립 트랜잭션 (Service에 @Transactional 없으므로)
                            pointService.chargePoint(memberId, chargeAmount, "동시 충전"); // 포인트 충전 (비관적 락 PESSIMISTIC_WRITE)
                        });
                        successCount.incrementAndGet(); // 성공 카운트 +1
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown(); // "나 끝났어!" (성공이든 실패든 반드시 실행)
                    }
                });
            }

            // when - 사용 5회 (워커 스레드 5개)
            for (int i = 0; i < useCount; i++) {
                long orderId = i + 1; // 주문 1~5
                executorService.execute(() -> { // 메인 스레드가 워커 스레드에 작업을 등록
                    // 워커 스레드에서 실행되는 영역
                    try {
                        readyLatch.countDown(); // "나 준비됐어!" (10→9→...→0)
                        startLatch.await(); // 출발 신호 대기 (startLatch가 0이 될 때까지 블로킹)
                        transactionTemplate.executeWithoutResult(status -> { // 스레드별 독립 트랜잭션 (Service에 @Transactional 없으므로)
                            pointService.usePoint(memberId, useAmount, "동시 사용", orderId); // 포인트 사용 (비관적 락 PESSIMISTIC_WRITE)
                        });
                        successCount.incrementAndGet(); // 성공 카운트 +1
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
            int expectedBalance = initialBalance + (chargeCount * chargeAmount) - (useCount * useAmount);
            int balance = pointService.getBalance(memberId);

            assertAll(
                    () -> assertThat(successCount.get()).isEqualTo(threadCount),
                    () -> assertThat(balance).isEqualTo(expectedBalance)
            );
        }
    }
}
