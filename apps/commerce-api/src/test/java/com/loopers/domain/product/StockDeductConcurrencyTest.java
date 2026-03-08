package com.loopers.domain.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.BrandStatus;
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
 * 테스트 대상: ProductService.deductStock() (비관적 락 동시성 제어)
 * 테스트 유형: 동시성 통합 테스트
 * 테스트 범위: TransactionTemplate → Service → Repository(PESSIMISTIC_WRITE) → Database
 *
 * 주의:
 * - 각 스레드가 독립 트랜잭션을 가져야 하므로 클래스 레벨 @Transactional을 사용하지 않는다.
 * - deductStock()은 @Transactional이 없으므로(Facade에서 트랜잭션 제공),
 *   테스트에서는 TransactionTemplate으로 트랜잭션을 감싼다.
 */
@SpringBootTest
class StockDeductConcurrencyTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private BrandService brandService;

    @Autowired
    private TransactionTemplate transactionTemplate;

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

    @DisplayName("여러 주문이 동시에 같은 상품의 재고를 차감할 때,")
    @Nested
    class ConcurrentStockDeduction {

        @Test
        @DisplayName("재고 수량만큼만 성공하고, 나머지는 BAD_REQUEST 예외가 발생한다.")
        void onlyStockQuantitySucceeds_whenConcurrentDeduction() throws InterruptedException {
            // given - 테스트 데이터 준비
            // *테스트 시나리오: 재고가 5개인 상품에 10명이 동시에 1개씩 주문하면? -> 5명은 성공, 5명은 실패
            int stockQuantity = 5; // 재고 5개
            int threadCount = 10; // 동시 요청 10개
            Product product = createProductWithStock(stockQuantity);
            Long optionId = product.getOptions().get(0).getId();

            // 동시성 제어를 위한 도구 세팅
            ExecutorService executorService = Executors.newFixedThreadPool(threadCount); // ExecutorService : 10개의 스레드를 관리하는 스레드 풀
            CountDownLatch readyLatch = new CountDownLatch(threadCount); // 모든 스레드가 준비되었는지 확인.(값: 10)
            CountDownLatch startLatch = new CountDownLatch(1); // 동시 출발 신호를 보냄. (값: 1)
            CountDownLatch doneLatch = new CountDownLatch(threadCount); // 모든 스레드가 끝났는지 확인. (값: 10)

            AtomicInteger successCount = new AtomicInteger(0); // 멀티스레드 환경에서의 안전한 카운터
            AtomicInteger failCount = new AtomicInteger(0);

            // when - 스레드 실행!!
            for (int i = 0; i < threadCount; i++) {
                executorService.execute(() -> { // 메인 스레드가 작업을 등록함
                    // 워커 스레드들 실행
                    try {
                        readyLatch.countDown(); // (1) 준비되었음을 알림
                        startLatch.await(); // (2) 일시정지!!! 출발 신호 기다림
                        transactionTemplate.executeWithoutResult(status -> { // (5)
                            productService.deductStock(optionId, 1); // 재고 1개 차감 (비관적 락)
                        });
                        successCount.incrementAndGet(); // 성공 카운트 +1
                    } catch (CoreException e) {
                        if (e.getErrorType() == ErrorType.BAD_REQUEST) {
                            failCount.incrementAndGet(); // 실패 카운트 +1
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown(); // (6) 끝남 알림
                    }
                });
            }

            readyLatch.await(); // (3) 10개 스레드 준비되었는지 체크 (0이 될 때까지 메인 스레드 블로킹 하므로, 10개의 스레드가 모두 countDown()해야 메인스레드 다음 줄로 이동)
            startLatch.countDown(); // (4) 출발~
            doneLatch.await(); // (7) 10개 스레드 다 끝났는지 확인
            executorService.shutdown(); // (8) 스레드 풀 정리

            // then - 결론
            ProductOption updatedOption = productService.findOptionById(optionId);

            assertAll(
                    () -> assertThat(successCount.get()).isEqualTo(stockQuantity),
                    () -> assertThat(failCount.get()).isEqualTo(threadCount - stockQuantity),
                    () -> assertThat(updatedOption.getStockQuantity()).isEqualTo(0)
            );
        }
    }
}
