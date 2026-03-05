package com.loopers.domain.like;

import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductService;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
 * 테스트 대상: ProductFacade.like() (Atomic UPDATE 동시성 제어)
 * 테스트 유형: 동시성 통합 테스트
 * 테스트 범위: Facade(@Transactional) → LikeService → ProductRepository(Atomic UPDATE) → Database
 *
 * 주의: 각 스레드가 독립 트랜잭션을 가져야 하므로 클래스 레벨 @Transactional을 사용하지 않는다.
 */
@SpringBootTest
class LikeConcurrencyTest {

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private ProductService productService;

    @Autowired
    private BrandService brandService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Product createProduct() {
        Brand brand = brandService.register("나이키", "스포츠 브랜드");
        brand = brandService.update(brand.getId(), "나이키", "스포츠 브랜드", BrandStatus.ACTIVE);
        ProductOption option = new ProductOption(null, "사이즈 270", 100);
        return productService.register(brand, "에어맥스", 100000, MarginType.RATE, 10,
                0, 3000, "에어맥스 설명", List.of(option));
    }

    @DisplayName("서로 다른 회원이 동시에 같은 상품에 좋아요할 때,")
    @Nested
    class ConcurrentLikeByDifferentMembers {

        @Test
        @DisplayName("모든 좋아요가 성공하고, likeCount가 정확히 반영된다.")
        void allLikesSucceed_whenConcurrentLike() throws InterruptedException {
            // given - 테스트 데이터 준비
            // *테스트 시나리오: 서로 다른 10명의 회원이 동시에 같은 상품에 좋아요하면? -> 10명 모두 성공, likeCount=10
            int threadCount = 10;
            Product product = createProduct();

            // 동시성 제어를 위한 도구 세팅
            ExecutorService executorService = Executors.newFixedThreadPool(threadCount); // 10개의 스레드를 관리하는 스레드 풀
            CountDownLatch readyLatch = new CountDownLatch(threadCount); // 모든 스레드가 준비되었는지 확인 (값: 10)
            CountDownLatch startLatch = new CountDownLatch(1); // 동시 출발 신호를 보냄 (값: 1)
            CountDownLatch doneLatch = new CountDownLatch(threadCount); // 모든 스레드가 끝났는지 확인 (값: 10)

            AtomicInteger successCount = new AtomicInteger(0); // 멀티스레드 환경에서 안전한 성공 카운터
            AtomicInteger failCount = new AtomicInteger(0); // 멀티스레드 환경에서 안전한 실패 카운터

            // when - 10개 스레드 동시 실행 (각 스레드가 다른 회원)
            for (int i = 1; i <= threadCount; i++) {
                long memberId = i; // 회원 1~10
                executorService.execute(() -> { // 메인 스레드가 워커 스레드에 작업을 등록
                    // 워커 스레드에서 실행되는 영역
                    try {
                        readyLatch.countDown(); // "나 준비됐어!" (10→9→...→0)
                        startLatch.await(); // 출발 신호 대기 (startLatch가 0이 될 때까지 블로킹)
                        productFacade.like(memberId, product.getId()); // 좋아요 (Atomic UPDATE)
                        successCount.incrementAndGet(); // 성공 카운트 +1
                    } catch (Exception e) {
                        failCount.incrementAndGet(); // 실패 카운트 +1
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
            Product updatedProduct = productService.findById(product.getId());

            assertAll(
                    () -> assertThat(successCount.get()).isEqualTo(threadCount),
                    () -> assertThat(failCount.get()).isEqualTo(0),
                    () -> assertThat(updatedProduct.getLikeCount()).isEqualTo(threadCount)
            );
        }
    }

    @DisplayName("서로 다른 회원이 동시에 같은 상품에 좋아요 취소할 때,")
    @Nested
    class ConcurrentUnlikeByDifferentMembers {

        private Product product;
        private int memberCount;

        @BeforeEach
        void setUp() {
            // given - 10명이 미리 좋아요한 상태
            memberCount = 10;
            product = createProduct();
            for (int i = 1; i <= memberCount; i++) {
                productFacade.like((long) i, product.getId());
            }
        }

        @Test
        @DisplayName("모든 좋아요 취소가 성공하고, likeCount가 0이 된다.")
        void allUnlikesSucceed_whenConcurrentUnlike() throws InterruptedException {
            // given - 동시성 제어를 위한 도구 세팅
            // *테스트 시나리오: 10명이 미리 좋아요한 상태에서 동시에 좋아요 취소하면? -> 10명 모두 성공, likeCount=0
            ExecutorService executorService = Executors.newFixedThreadPool(memberCount); // 10개의 스레드를 관리하는 스레드 풀
            CountDownLatch readyLatch = new CountDownLatch(memberCount); // 모든 스레드가 준비되었는지 확인 (값: 10)
            CountDownLatch startLatch = new CountDownLatch(1); // 동시 출발 신호를 보냄 (값: 1)
            CountDownLatch doneLatch = new CountDownLatch(memberCount); // 모든 스레드가 끝났는지 확인 (값: 10)

            AtomicInteger successCount = new AtomicInteger(0); // 멀티스레드 환경에서 안전한 성공 카운터
            AtomicInteger failCount = new AtomicInteger(0); // 멀티스레드 환경에서 안전한 실패 카운터

            // when - 10개 스레드 동시 실행 (각 스레드가 다른 회원)
            for (int i = 1; i <= memberCount; i++) {
                long memberId = i; // 회원 1~10
                executorService.execute(() -> { // 메인 스레드가 워커 스레드에 작업을 등록
                    // 워커 스레드에서 실행되는 영역
                    try {
                        readyLatch.countDown(); // "나 준비됐어!" (10→9→...→0)
                        startLatch.await(); // 출발 신호 대기 (startLatch가 0이 될 때까지 블로킹)
                        productFacade.unlike(memberId, product.getId()); // 좋아요 취소 (Atomic UPDATE)
                        successCount.incrementAndGet(); // 성공 카운트 +1
                    } catch (Exception e) {
                        failCount.incrementAndGet(); // 실패 카운트 +1
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
            Product updatedProduct = productService.findById(product.getId());

            assertAll(
                    () -> assertThat(successCount.get()).isEqualTo(memberCount),
                    () -> assertThat(failCount.get()).isEqualTo(0),
                    () -> assertThat(updatedProduct.getLikeCount()).isEqualTo(0)
            );
        }
    }

    @DisplayName("서로 다른 회원이 동시에 좋아요와 좋아요 취소를 혼합 요청할 때,")
    @Nested
    class ConcurrentLikeAndUnlikeMixed {

        @Test
        @DisplayName("최종 likeCount가 정확히 반영된다.")
        void likeCountIsCorrect_whenConcurrentLikeAndUnlike() throws InterruptedException {
            // given - 테스트 데이터 준비
            // *테스트 시나리오: 회원 1~5는 좋아요 취소, 회원 6~10은 좋아요를 동시에 하면? -> 최종 likeCount = 5 (5-5+5)
            Product product = createProduct();
            int unlikeCount = 5;
            int likeCount = 5;
            int threadCount = unlikeCount + likeCount;

            // 회원 1~5는 이미 좋아요한 상태 (이 5명이 unlike 할 예정)
            for (int i = 1; i <= unlikeCount; i++) {
                productFacade.like((long) i, product.getId());
            }

            // 동시성 제어를 위한 도구 세팅
            ExecutorService executorService = Executors.newFixedThreadPool(threadCount); // 10개의 스레드를 관리하는 스레드 풀
            CountDownLatch readyLatch = new CountDownLatch(threadCount); // 모든 스레드가 준비되었는지 확인 (값: 10)
            CountDownLatch startLatch = new CountDownLatch(1); // 동시 출발 신호를 보냄 (값: 1)
            CountDownLatch doneLatch = new CountDownLatch(threadCount); // 모든 스레드가 끝났는지 확인 (값: 10)

            AtomicInteger successCount = new AtomicInteger(0); // 멀티스레드 환경에서 안전한 성공 카운터

            // when - 회원 1~5: unlike (좋아요 취소)
            for (int i = 1; i <= unlikeCount; i++) {
                long memberId = i; // 회원 1~5
                executorService.execute(() -> { // 메인 스레드가 워커 스레드에 작업을 등록
                    // 워커 스레드에서 실행되는 영역
                    try {
                        readyLatch.countDown(); // "나 준비됐어!" (10→9→...→0)
                        startLatch.await(); // 출발 신호 대기 (startLatch가 0이 될 때까지 블로킹)
                        productFacade.unlike(memberId, product.getId()); // 좋아요 취소 (Atomic UPDATE)
                        successCount.incrementAndGet(); // 성공 카운트 +1
                    } catch (Exception e) {
                        // unexpected
                    } finally {
                        doneLatch.countDown(); // "나 끝났어!" (성공이든 실패든 반드시 실행)
                    }
                });
            }

            // when - 회원 6~10: like (좋아요)
            for (int i = unlikeCount + 1; i <= threadCount; i++) {
                long memberId = i; // 회원 6~10
                executorService.execute(() -> { // 메인 스레드가 워커 스레드에 작업을 등록
                    // 워커 스레드에서 실행되는 영역
                    try {
                        readyLatch.countDown(); // "나 준비됐어!" (10→9→...→0)
                        startLatch.await(); // 출발 신호 대기 (startLatch가 0이 될 때까지 블로킹)
                        productFacade.like(memberId, product.getId()); // 좋아요 (Atomic UPDATE)
                        successCount.incrementAndGet(); // 성공 카운트 +1
                    } catch (Exception e) {
                        // unexpected
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

            // then - 초기 5 - unlike 5 + like 5 = 5
            Product updatedProduct = productService.findById(product.getId());
            int expectedLikeCount = likeCount; // 5 - 5 + 5 = 5

            assertAll(
                    () -> assertThat(successCount.get()).isEqualTo(threadCount),
                    () -> assertThat(updatedProduct.getLikeCount()).isEqualTo(expectedLikeCount)
            );
        }
    }
}
