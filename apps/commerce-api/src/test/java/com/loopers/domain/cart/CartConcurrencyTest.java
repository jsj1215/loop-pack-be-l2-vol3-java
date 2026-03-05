package com.loopers.domain.cart;

import com.loopers.application.cart.CartFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductService;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * [동시성 테스트 - Concurrency Integration]
 *
 * 테스트 대상: CartFacade.addToCart() (원자적 UPSERT 동시성 제어)
 * 테스트 유형: 동시성 통합 테스트
 * 테스트 범위: Facade(@Transactional) → Service → Repository(INSERT ... ON DUPLICATE KEY UPDATE) → Database
 *
 * 주의: 각 스레드가 독립 트랜잭션을 가져야 하므로 클래스 레벨 @Transactional을 사용하지 않는다.
 */
@SpringBootTest
class CartConcurrencyTest {

    @Autowired
    private CartFacade cartFacade;

    @Autowired
    private CartRepository cartRepository;

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

    private Product createProductWithStock(int stockQuantity) {
        Brand brand = brandService.register("나이키", "스포츠 브랜드");
        brand = brandService.update(brand.getId(), "나이키", "스포츠 브랜드", BrandStatus.ACTIVE);
        ProductOption option = new ProductOption(null, "사이즈 270", stockQuantity);
        return productService.register(brand, "에어맥스", 100000, MarginType.RATE, 10,
                0, 3000, "에어맥스 설명", List.of(option));
    }

    @DisplayName("같은 회원이 동시에 같은 상품을 장바구니에 추가할 때,")
    @Nested
    class ConcurrentAddSameItemBySameMember {

        @Test
        @DisplayName("모든 요청이 성공하고, 수량이 정확히 합산된다.")
        void quantityIsSummed_whenConcurrentAdd() throws InterruptedException {
            // given - 테스트 데이터 준비
            // *테스트 시나리오: 같은 회원이 같은 상품을 10번 동시에 장바구니에 추가하면? -> 10번 모두 성공하고, 수량이 10으로 합산
            int threadCount = 10;
            int quantityPerRequest = 1;
            Product product = createProductWithStock(100);
            Long optionId = product.getOptions().get(0).getId();
            Long memberId = 1L;

            // 동시성 제어를 위한 도구 세팅
            ExecutorService executorService = Executors.newFixedThreadPool(threadCount); // 10개의 스레드를 관리하는 스레드 풀
            CountDownLatch readyLatch = new CountDownLatch(threadCount); // 모든 스레드가 준비되었는지 확인 (값: 10)
            CountDownLatch startLatch = new CountDownLatch(1); // 동시 출발 신호를 보냄 (값: 1)
            CountDownLatch doneLatch = new CountDownLatch(threadCount); // 모든 스레드가 끝났는지 확인 (값: 10)

            AtomicInteger successCount = new AtomicInteger(0); // 멀티스레드 환경에서 안전한 성공 카운터
            AtomicInteger failCount = new AtomicInteger(0); // 멀티스레드 환경에서 안전한 실패 카운터

            // when - 10개 스레드 동시 실행
            for (int i = 0; i < threadCount; i++) {
                executorService.execute(() -> { // 메인 스레드가 워커 스레드에 작업을 등록
                    // 워커 스레드에서 실행되는 영역
                    try {
                        readyLatch.countDown(); // "나 준비됐어!" (10→9→...→0)
                        startLatch.await(); // 출발 신호 대기 (startLatch가 0이 될 때까지 블로킹)
                        cartFacade.addToCart(memberId, optionId, quantityPerRequest); // 장바구니 추가 (원자적 UPSERT)
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
            Optional<CartItem> cartItem = cartRepository.findByMemberIdAndProductOptionId(memberId, optionId);

            assertAll(
                    () -> assertThat(successCount.get()).isEqualTo(threadCount),
                    () -> assertThat(failCount.get()).isEqualTo(0),
                    () -> assertThat(cartItem).isPresent(),
                    () -> assertThat(cartItem.get().getQuantity()).isEqualTo(threadCount * quantityPerRequest)
            );
        }
    }

    @DisplayName("서로 다른 회원이 동시에 같은 상품을 장바구니에 추가할 때,")
    @Nested
    class ConcurrentAddSameItemByDifferentMembers {

        @Test
        @DisplayName("모든 요청이 성공하고, 각 회원의 장바구니에 정확히 반영된다.")
        void allSucceed_whenDifferentMembersConcurrentAdd() throws InterruptedException {
            // given - 테스트 데이터 준비
            // *테스트 시나리오: 서로 다른 10명의 회원이 동시에 같은 상품을 장바구니에 추가하면? -> 10명 모두 성공
            int threadCount = 10;
            int quantityPerRequest = 2;
            Product product = createProductWithStock(100);
            Long optionId = product.getOptions().get(0).getId();

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
                        cartFacade.addToCart(memberId, optionId, quantityPerRequest); // 장바구니 추가 (원자적 UPSERT)
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
            assertAll(
                    () -> assertThat(successCount.get()).isEqualTo(threadCount),
                    () -> assertThat(failCount.get()).isEqualTo(0)
            );

            for (int i = 1; i <= threadCount; i++) {
                Optional<CartItem> cartItem = cartRepository.findByMemberIdAndProductOptionId((long) i, optionId);
                assertThat(cartItem).isPresent();
                assertThat(cartItem.get().getQuantity()).isEqualTo(quantityPerRequest);
            }
        }
    }
}
