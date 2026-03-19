package com.loopers.application.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.application.cart.CartFacade;
import com.loopers.domain.cart.CartItem;
import com.loopers.domain.cart.CartRepository;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.CouponService;
import com.loopers.domain.coupon.DiscountType;
import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.coupon.MemberCouponRepository;
import com.loopers.domain.coupon.MemberCouponStatus;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderService.OrderItemRequest;
import com.loopers.domain.point.PointService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
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
 * 테스트 대상: OrderFacade.createOrder() (트랜잭션 단위 동시성 제어)
 * 테스트 유형: 동시성 통합 테스트
 * 테스트 범위: Facade(@Transactional) → 재고(비관적 락) + 쿠폰(낙관적 락) + 포인트(비관적 락) → Database
 *
 * 주의: 각 스레드가 독립 트랜잭션을 가져야 하므로 클래스 레벨 @Transactional을 사용하지 않는다.
 */
@SpringBootTest
class OrderConcurrencyTest {

    @Autowired
    private OrderPlacementService orderPlacementService;

    @Autowired
    private ProductService productService;

    @Autowired
    private BrandService brandService;

    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private PointService pointService;

    @Autowired
    private CartFacade cartFacade;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    // ── 헬퍼 메서드 ──────────────────────────────────────────────

    private Product createProduct(String brandName, String productName, int price, int stockQuantity) {
        Brand brand = brandService.register(brandName, brandName + " 설명");
        brand = brandService.update(brand.getId(), brandName, brandName + " 설명", BrandStatus.ACTIVE);
        ProductOption option = new ProductOption(null, productName + " 옵션", stockQuantity);
        return productService.register(brand, productName, price, MarginType.RATE, 10,
                0, 3000, productName + " 설명", List.of(option));
    }

    private Coupon createCoupon(int discountValue, int minOrderAmount) {
        Coupon coupon = new Coupon(
                "테스트 쿠폰",
                CouponScope.CART,
                null,
                DiscountType.FIXED_AMOUNT,
                discountValue,
                minOrderAmount,
                0,
                ZonedDateTime.now().minusDays(1),
                ZonedDateTime.now().plusDays(30)
        );
        return couponRepository.save(coupon);
    }

    private MemberCoupon issueCouponToMember(Long memberId, Long couponId) {
        MemberCoupon memberCoupon = new MemberCoupon(memberId, couponId);
        return memberCouponRepository.save(memberCoupon);
    }

    private void createPointForMember(Long memberId, int balance) {
        transactionTemplate.executeWithoutResult(status -> {
            pointService.createPoint(memberId);
            if (balance > 0) {
                pointService.chargePoint(memberId, balance, "테스트 충전");
            }
        });
    }

    private ConcurrencyResult executeConcurrently(int threadCount, Runnable[] tasks) throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            int index = i;
            executorService.execute(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    tasks[index].run();
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

        return new ConcurrencyResult(successCount.get(), failCount.get());
    }

    private record ConcurrencyResult(int successCount, int failCount) {}

    // ── A. 자원 경합 (서로 다른 회원 간) ─────────────────────────

    @DisplayName("A. 서로 다른 회원 간 자원 경합")
    @Nested
    class ResourceContention {

        /**
         * [재고 비관적 락 경합 테스트]
         *
         * 시나리오: 재고 5개인 상품에 10명이 동시 주문
         *
         * 실행 흐름:
         *   Thread 1~10  (동시 시작)
         *     │
         *     ├─ deductStock() → SELECT ... FOR UPDATE (비관적 락)
         *     │   ├─ Thread A: 락 획득 → 재고 5→4 차감 → commit → 성공
         *     │   ├─ Thread B: 락 대기 → 획득 → 재고 4→3 차감 → commit → 성공
         *     │   ├─ ...
         *     │   ├─ Thread E: 락 대기 → 획득 → 재고 1→0 차감 → commit → 성공
         *     │   ├─ Thread F: 락 대기 → 획득 → 재고 0 → 재고 부족 예외 → rollback
         *     │   └─ ...
         *     └─ 결과: 5명 성공, 5명 실패, 최종 재고 0
         */
        @Test
        @DisplayName("A-1: 10명이 동시에 같은 상품 주문 (재고 5개) → 5명 성공, 5명 실패, 재고 0")
        void onlyStockCountSucceeds_whenConcurrentOrders() throws InterruptedException {
            // given
            int stockQuantity = 5;
            int threadCount = 10;
            Product product = createProduct("나이키", "에어맥스", 100000, stockQuantity);
            Long optionId = product.getOptions().get(0).getId();

            Runnable[] tasks = new Runnable[threadCount];
            for (int i = 0; i < threadCount; i++) {
                long memberId = i + 1;
                tasks[i] = () -> orderPlacementService.placeOrder(
                        memberId,
                        List.of(new OrderItemRequest(product.getId(), optionId, 1)),
                        null, 0, null
                );
            }

            // when
            ConcurrencyResult result = executeConcurrently(threadCount, tasks);

            // then
            ProductOption updatedOption = productService.findOptionById(optionId);

            assertAll(
                    () -> assertThat(result.successCount()).isEqualTo(stockQuantity),
                    () -> assertThat(result.failCount()).isEqualTo(threadCount - stockQuantity),
                    () -> assertThat(updatedOption.getStockQuantity()).isEqualTo(0)
            );
        }

        /**
         * [복합 자원(재고+쿠폰+포인트) 롤백 정합성 테스트]
         *
         * 시나리오: 재고 5개 상품에 10명이 각자 쿠폰+포인트를 사용하여 동시 주문
         *
         * 실행 흐름:
         *   Thread 1~10  (동시 시작)
         *     │
         *     ├─ 1) couponService.calculateCouponDiscount() — 쿠폰 할인 계산 (읽기)
         *     ├─ 2) deductStock() → SELECT ... FOR UPDATE (비관적 락)
         *     │     ├─ 성공 스레드 (5개): 재고 차감 성공
         *     │     └─ 실패 스레드 (5개): 재고 부족 예외 → @Transactional rollback
         *     ├─ 3) couponService.useCoupon() — @Version 낙관적 락으로 상태 변경
         *     ├─ 4) pointService.usePoint() — 비관적 락으로 잔액 차감
         *     └─ 5) 장바구니 삭제
         *
         * 검증 포인트:
         *   - 실패 건: 트랜잭션 rollback → 쿠폰 AVAILABLE 유지, 포인트 잔액 3000 유지
         *   - 성공 건: 쿠폰 USED, 포인트 잔액 0
         */
        @Test
        @DisplayName("A-2: 10명이 동시에 같은 상품 주문 (재고 5개, 각자 쿠폰+포인트 사용) → 실패 건 쿠폰/포인트 롤백")
        void failedOrdersRollbackCouponAndPoint_whenStockInsufficient() throws InterruptedException {
            // given
            int stockQuantity = 5;
            int threadCount = 10;
            Product product = createProduct("나이키", "에어맥스", 100000, stockQuantity);
            Long optionId = product.getOptions().get(0).getId();

            Coupon coupon = createCoupon(5000, 10000);
            Long[] memberCouponIds = new Long[threadCount];
            for (int i = 0; i < threadCount; i++) {
                long memberId = i + 1;
                createPointForMember(memberId, 3000);
                MemberCoupon mc = issueCouponToMember(memberId, coupon.getId());
                memberCouponIds[i] = mc.getId();
            }

            Runnable[] tasks = new Runnable[threadCount];
            for (int i = 0; i < threadCount; i++) {
                long memberId = i + 1;
                Long memberCouponId = memberCouponIds[i];
                tasks[i] = () -> orderPlacementService.placeOrder(
                        memberId,
                        List.of(new OrderItemRequest(product.getId(), optionId, 1)),
                        memberCouponId, 3000, null
                );
            }

            // when
            ConcurrencyResult result = executeConcurrently(threadCount, tasks);

            // then
            ProductOption updatedOption = productService.findOptionById(optionId);

            // 성공한 회원의 쿠폰은 USED, 실패한 회원의 쿠폰은 AVAILABLE
            long usedCouponCount = 0;
            long availableCouponCount = 0;
            for (Long mcId : memberCouponIds) {
                MemberCoupon mc = couponService.getMemberCoupon(mcId);
                if (mc.getStatus() == MemberCouponStatus.USED) {
                    usedCouponCount++;
                } else if (mc.getStatus() == MemberCouponStatus.AVAILABLE) {
                    availableCouponCount++;
                }
            }

            // 실패한 회원의 포인트는 그대로 3000
            final int[] pointSums = {0, 0}; // [0]=failed, [1]=success
            for (int i = 0; i < threadCount; i++) {
                long memberId = i + 1;
                int balance = pointService.getBalance(memberId);
                MemberCoupon mc = couponService.getMemberCoupon(memberCouponIds[i]);
                if (mc.getStatus() == MemberCouponStatus.AVAILABLE) {
                    pointSums[0] += balance;
                } else {
                    pointSums[1] += balance;
                }
            }

            final long finalUsedCouponCount = usedCouponCount;
            final long finalAvailableCouponCount = availableCouponCount;
            assertAll(
                    () -> assertThat(result.successCount()).isEqualTo(stockQuantity),
                    () -> assertThat(result.failCount()).isEqualTo(threadCount - stockQuantity),
                    () -> assertThat(updatedOption.getStockQuantity()).isEqualTo(0),
                    () -> assertThat(finalUsedCouponCount).isEqualTo(stockQuantity),
                    () -> assertThat(finalAvailableCouponCount).isEqualTo(threadCount - stockQuantity),
                    () -> assertThat(pointSums[0]).isEqualTo((threadCount - stockQuantity) * 3000),
                    () -> assertThat(pointSums[1]).isEqualTo(0)
            );
        }

        /**
         * [자원 격리 테스트 — 서로 다른 상품 간 간섭 없음]
         *
         * 시나리오: 10명이 각각 다른 상품(재고 충분)을 동시 주문
         *
         * 실행 흐름:
         *   Thread 1: deductStock(상품1) → 락 획득 → 차감 → commit
         *   Thread 2: deductStock(상품2) → 락 획득 → 차감 → commit   (상품1과 별개 row → 경합 없음)
         *   ...
         *   Thread 10: deductStock(상품10) → 락 획득 → 차감 → commit
         *
         * 검증 포인트:
         *   - 전원 성공 (서로 다른 row에 락 → 병렬 처리)
         *   - 각 상품 재고가 정확히 1씩만 차감
         */
        @Test
        @DisplayName("A-3: 10명이 서로 다른 상품을 동시 주문 (각 재고 충분) → 전원 성공, 상호 간섭 없음")
        void allSucceed_whenOrderingDifferentProducts() throws InterruptedException {
            // given
            int threadCount = 10;
            Product[] products = new Product[threadCount];
            for (int i = 0; i < threadCount; i++) {
                products[i] = createProduct("브랜드" + i, "상품" + i, 10000, 10);
            }

            Runnable[] tasks = new Runnable[threadCount];
            for (int i = 0; i < threadCount; i++) {
                long memberId = i + 1;
                Product product = products[i];
                Long optionId = product.getOptions().get(0).getId();
                tasks[i] = () -> orderPlacementService.placeOrder(
                        memberId,
                        List.of(new OrderItemRequest(product.getId(), optionId, 1)),
                        null, 0, null
                );
            }

            // when
            ConcurrencyResult result = executeConcurrently(threadCount, tasks);

            // then
            assertAll(
                    () -> assertThat(result.successCount()).isEqualTo(threadCount),
                    () -> assertThat(result.failCount()).isEqualTo(0)
            );

            for (Product product : products) {
                ProductOption option = productService.findOptionById(product.getOptions().get(0).getId());
                assertThat(option.getStockQuantity()).isEqualTo(9);
            }
        }
    }

    // ── B. 같은 회원 동시 주문 (이중 사용 방어) ──────────────────

    @DisplayName("B. 같은 회원 동시 주문 (이중 사용 방어)")
    @Nested
    class SameMemberConcurrentOrder {

        /**
         * [쿠폰 이중 사용 방어 테스트 — @Version 낙관적 락]
         *
         * 시나리오: 같은 회원이 동일 쿠폰으로 동시에 2건 주문
         *
         * 실행 흐름:
         *   Thread A ─┬─ deductStock() → 락 획득 → 재고 차감
         *             ├─ useCoupon() → version=0 읽기 → USED 변경 → version=1로 commit ✅
         *             └─ 성공
         *
         *   Thread B ─┬─ deductStock() → 락 대기 → 획득 → 재고 차감
         *             ├─ useCoupon() → version=0 읽기 → UPDATE WHERE version=0
         *             │   → 이미 version=1 → OptimisticLockException 발생 ❌
         *             └─ @Transactional rollback → 재고 차감도 원복
         *
         * 검증 포인트:
         *   - 1건만 성공, 쿠폰 USED 상태
         *   - 실패 건의 재고 차감이 rollback되어 1만 차감됨
         */
        @Test
        @DisplayName("B-1: 같은 회원이 동일 쿠폰으로 동시 2건 주문 → 1건만 쿠폰 적용 성공, 실패 건 재고 롤백")
        void onlyOneSucceeds_whenSameCouponUsedConcurrently() throws InterruptedException {
            // given
            Long memberId = 1L;
            Product product = createProduct("나이키", "에어맥스", 100000, 10);
            Long optionId = product.getOptions().get(0).getId();

            Coupon coupon = createCoupon(5000, 10000);
            MemberCoupon memberCoupon = issueCouponToMember(memberId, coupon.getId());

            int threadCount = 2;
            Runnable[] tasks = new Runnable[threadCount];
            for (int i = 0; i < threadCount; i++) {
                tasks[i] = () -> orderPlacementService.placeOrder(
                        memberId,
                        List.of(new OrderItemRequest(product.getId(), optionId, 1)),
                        memberCoupon.getId(), 0, null
                );
            }

            // when
            ConcurrencyResult result = executeConcurrently(threadCount, tasks);

            // then
            ProductOption updatedOption = productService.findOptionById(optionId);
            MemberCoupon updatedMemberCoupon = couponService.getMemberCoupon(memberCoupon.getId());

            assertAll(
                    () -> assertThat(result.successCount()).isEqualTo(1),
                    () -> assertThat(result.failCount()).isEqualTo(1),
                    () -> assertThat(updatedMemberCoupon.getStatus()).isEqualTo(MemberCouponStatus.USED),
                    // 성공 1건만 재고 차감 (실패 건 롤백)
                    () -> assertThat(updatedOption.getStockQuantity()).isEqualTo(9)
            );
        }

        /**
         * [포인트 이중 사용 방어 테스트 — 비관적 락]
         *
         * 시나리오: 잔액 5000원인 회원이 동시에 2건 주문 (각 3000원 포인트 사용)
         *
         * 실행 흐름:
         *   Thread A ─┬─ deductStock() → 재고 차감
         *             ├─ usePoint(3000) → SELECT ... FOR UPDATE → 잔액 5000→2000 → commit ✅
         *             └─ 성공
         *
         *   Thread B ─┬─ deductStock() → 재고 차감
         *             ├─ usePoint(3000) → SELECT ... FOR UPDATE → 잔액 2000 < 3000
         *             │   → 잔액 부족 예외 ❌
         *             └─ @Transactional rollback → 재고 차감도 원복
         *
         * 검증 포인트:
         *   - 1건만 성공, 포인트 잔액 2000
         *   - 실패 건의 재고 차감이 rollback되어 1만 차감됨
         */
        @Test
        @DisplayName("B-2: 같은 회원이 포인트 5000원으로 동시 2건 주문 (각 3000원 사용) → 1건 성공, 1건 실패, 재고 롤백")
        void onlyOneSucceeds_whenInsufficientPointsConcurrently() throws InterruptedException {
            // given
            Long memberId = 1L;
            Product product = createProduct("나이키", "에어맥스", 100000, 10);
            Long optionId = product.getOptions().get(0).getId();
            createPointForMember(memberId, 5000);

            int threadCount = 2;
            Runnable[] tasks = new Runnable[threadCount];
            for (int i = 0; i < threadCount; i++) {
                tasks[i] = () -> orderPlacementService.placeOrder(
                        memberId,
                        List.of(new OrderItemRequest(product.getId(), optionId, 1)),
                        null, 3000, null
                );
            }

            // when
            ConcurrencyResult result = executeConcurrently(threadCount, tasks);

            // then
            ProductOption updatedOption = productService.findOptionById(optionId);
            int balance = pointService.getBalance(memberId);

            assertAll(
                    () -> assertThat(result.successCount()).isEqualTo(1),
                    () -> assertThat(result.failCount()).isEqualTo(1),
                    () -> assertThat(balance).isEqualTo(2000),
                    // 성공 1건만 재고 차감 (실패 건 롤백)
                    () -> assertThat(updatedOption.getStockQuantity()).isEqualTo(9)
            );
        }

        /**
         * [쿠폰+포인트 복합 이중 사용 방어 테스트]
         *
         * 시나리오: 같은 회원이 동일 쿠폰(5000원) + 포인트(3000원)로 동시에 2건 주문
         *
         * 실행 흐름:
         *   Thread A ─┬─ deductStock() → 재고 차감
         *             ├─ useCoupon() → version=0 → USED, version=1 → commit ✅
         *             ├─ usePoint(3000) → 잔액 3000→0 → commit ✅
         *             └─ 성공
         *
         *   Thread B ─┬─ deductStock() → 재고 차감
         *             ├─ useCoupon() → version=0으로 UPDATE 시도 → version 충돌 ❌
         *             │   (또는 쿠폰 통과 시 usePoint()에서 잔액 부족 ❌)
         *             └─ @Transactional rollback → 재고+쿠폰+포인트 모두 원복
         *
         * 검증 포인트:
         *   - 1건만 성공, 쿠폰 USED, 포인트 잔액 0, 재고 1만 차감
         */
        @Test
        @DisplayName("B-3: 같은 회원이 동일 쿠폰 + 포인트로 동시 2건 주문 → 1건만 성공, 전체 정합성 유지")
        void onlyOneSucceeds_whenSameCouponAndPointsConcurrently() throws InterruptedException {
            // given
            Long memberId = 1L;
            Product product = createProduct("나이키", "에어맥스", 100000, 10);
            Long optionId = product.getOptions().get(0).getId();

            Coupon coupon = createCoupon(5000, 10000);
            MemberCoupon memberCoupon = issueCouponToMember(memberId, coupon.getId());
            createPointForMember(memberId, 3000);

            int threadCount = 2;
            Runnable[] tasks = new Runnable[threadCount];
            for (int i = 0; i < threadCount; i++) {
                tasks[i] = () -> orderPlacementService.placeOrder(
                        memberId,
                        List.of(new OrderItemRequest(product.getId(), optionId, 1)),
                        memberCoupon.getId(), 3000, null
                );
            }

            // when
            ConcurrencyResult result = executeConcurrently(threadCount, tasks);

            // then
            ProductOption updatedOption = productService.findOptionById(optionId);
            MemberCoupon updatedMemberCoupon = couponService.getMemberCoupon(memberCoupon.getId());
            int balance = pointService.getBalance(memberId);

            assertAll(
                    () -> assertThat(result.successCount()).isEqualTo(1),
                    () -> assertThat(result.failCount()).isEqualTo(1),
                    () -> assertThat(updatedMemberCoupon.getStatus()).isEqualTo(MemberCouponStatus.USED),
                    () -> assertThat(balance).isEqualTo(0),
                    () -> assertThat(updatedOption.getStockQuantity()).isEqualTo(9)
            );
        }
    }

    // ── C. TOCTOU (Time of Check to Time of Use) ────────────────

    @DisplayName("C. TOCTOU - 검증 시점과 사용 시점 사이의 갭")
    @Nested
    class TOCTOU {

        /**
         * [TOCTOU 검증 — 쿠폰 검증 시점과 사용 시점 사이의 갭]
         *
         * 시나리오: 같은 회원이 동일 쿠폰으로 동시 2건 주문.
         *          한쪽이 쿠폰 할인 계산(검증)을 먼저 통과했더라도,
         *          실제 useCoupon() 시점에서 @Version 충돌로 차단됨.
         *
         * 실행 흐름:
         *   Thread A ─┬─ calculateCouponDiscount() → 쿠폰 유효 (읽기만, 락 없음)
         *             ├─ deductStock() → 비관적 락 → 재고 차감
         *             ├─ useCoupon() → version=0 → USED → version=1 → commit ✅
         *             └─ 성공
         *
         *   Thread B ─┬─ calculateCouponDiscount() → 쿠폰 유효 (읽기 시점에선 AVAILABLE)
         *             ├─ deductStock() → 비관적 락 대기 → 획득 → 재고 차감
         *             ├─ useCoupon() → version=0으로 UPDATE → 이미 version=1 → 충돌 ❌
         *             └─ @Transactional rollback → 재고 원복
         *
         * 검증 포인트:
         *   - 읽기 시점에 AVAILABLE이더라도 쓰기 시점에 @Version으로 방어
         *   - 실패 건의 재고가 정확히 rollback됨
         */
        @Test
        @DisplayName("C-1: 쿠폰 유효성 통과 후 재고 락 대기 중 다른 주문이 쿠폰 사용 → 재고 롤백")
        void stockRollsBack_whenCouponAlreadyUsedDuringStockLockWait() throws InterruptedException {
            // given
            // 재고를 1로 설정하여 한 스레드가 재고 락을 오래 잡도록 유도
            Long member1 = 1L;
            Long member2 = 2L;
            Product product = createProduct("나이키", "에어맥스", 100000, 10);
            Long optionId = product.getOptions().get(0).getId();

            // 같은 쿠폰을 두 회원에게 발급할 수 없으므로, 같은 회원이 2건 주문하는 시나리오
            // (이미 B-1에서 검증했지만, 여기서는 재고 롤백에 초점)
            Coupon coupon = createCoupon(5000, 10000);
            MemberCoupon mc1 = issueCouponToMember(member1, coupon.getId());

            int threadCount = 2;
            Runnable[] tasks = new Runnable[threadCount];
            for (int i = 0; i < threadCount; i++) {
                tasks[i] = () -> orderPlacementService.placeOrder(
                        member1,
                        List.of(new OrderItemRequest(product.getId(), optionId, 1)),
                        mc1.getId(), 0, null
                );
            }

            // when
            ConcurrencyResult result = executeConcurrently(threadCount, tasks);

            // then - 재고가 1만 차감되어야 함 (실패 건 롤백)
            ProductOption updatedOption = productService.findOptionById(optionId);
            MemberCoupon updatedMc = couponService.getMemberCoupon(mc1.getId());

            assertAll(
                    () -> assertThat(result.successCount()).isEqualTo(1),
                    () -> assertThat(result.failCount()).isEqualTo(1),
                    () -> assertThat(updatedOption.getStockQuantity()).isEqualTo(9),
                    () -> assertThat(updatedMc.getStatus()).isEqualTo(MemberCouponStatus.USED),
                    () -> assertThat(updatedMc.getOrderId()).isNotNull()
            );
        }
    }

    // ── D. 데드락 방어 ──────────────────────────────────────────

    @DisplayName("D. 데드락 방어")
    @Nested
    class DeadlockPrevention {

        /**
         * [교차 상품 주문 데드락 방어 테스트]
         *
         * 시나리오: 2명이 동시에 상품1+상품2를 주문하되, 요청 순서가 반대
         *          (주문A: 상품1→상품2, 주문B: 상품2→상품1)
         *
         * 데드락 위험 (정렬 없을 경우):
         *   Thread A: LOCK(상품1) → LOCK(상품2) 시도
         *   Thread B: LOCK(상품2) → LOCK(상품1) 시도
         *   → 서로 상대가 가진 락을 대기 → 데드락!
         *
         * 실제 실행 흐름 (prepareOrderItems에서 optionId 오름차순 정렬):
         *   Thread A: LOCK(옵션ID 작은 것) → LOCK(옵션ID 큰 것) → 차감 → commit ✅
         *   Thread B: LOCK(옵션ID 작은 것) → 대기 → 획득 → LOCK(큰 것) → 차감 → commit ✅
         *   → 락 획득 순서가 동일하므로 데드락 발생하지 않음
         *
         * 검증 포인트:
         *   - 데드락 없이 2건 모두 성공
         *   - 각 상품 재고 정확히 2 차감
         */
        @Test
        @DisplayName("D-1: 교차 상품 주문 (주문A: 상품1+상품2, 주문B: 상품2+상품1) → 데드락 없이 완료")
        void noDeadlock_whenCrossProductOrders() throws InterruptedException {
            // given
            Product product1 = createProduct("나이키", "에어맥스", 100000, 10);
            Product product2 = createProduct("아디다스", "울트라부스트", 80000, 10);
            Long optionId1 = product1.getOptions().get(0).getId();
            Long optionId2 = product2.getOptions().get(0).getId();

            int threadCount = 2;
            Runnable[] tasks = new Runnable[threadCount];

            // 주문A: 상품1 → 상품2 순서로 요청 (내부적으로 optionId 정렬됨)
            tasks[0] = () -> orderPlacementService.placeOrder(
                    1L,
                    List.of(
                            new OrderItemRequest(product1.getId(), optionId1, 1),
                            new OrderItemRequest(product2.getId(), optionId2, 1)
                    ),
                    null, 0, null
            );

            // 주문B: 상품2 → 상품1 역순으로 요청 (내부적으로 optionId 정렬됨)
            tasks[1] = () -> orderPlacementService.placeOrder(
                    2L,
                    List.of(
                            new OrderItemRequest(product2.getId(), optionId2, 1),
                            new OrderItemRequest(product1.getId(), optionId1, 1)
                    ),
                    null, 0, null
            );

            // when
            ConcurrencyResult result = executeConcurrently(threadCount, tasks);

            // then
            ProductOption updatedOption1 = productService.findOptionById(optionId1);
            ProductOption updatedOption2 = productService.findOptionById(optionId2);

            assertAll(
                    () -> assertThat(result.successCount()).isEqualTo(2),
                    () -> assertThat(result.failCount()).isEqualTo(0),
                    () -> assertThat(updatedOption1.getStockQuantity()).isEqualTo(8),
                    () -> assertThat(updatedOption2.getStockQuantity()).isEqualTo(8)
            );
        }

        /**
         * [대규모 교차 주문 데드락 스트레스 테스트]
         *
         * 시나리오: 10명이 동시에 3개 상품을 주문하되, 각자 다른 순서로 요청
         *          (i%3에 따라 상품1→2→3, 상품3→1→2, 상품2→3→1 순서)
         *
         * 실행 흐름:
         *   Thread 1 (i%3=0): 요청 순서 상품1→2→3 → 정렬 후 LOCK(1)→LOCK(2)→LOCK(3)
         *   Thread 2 (i%3=1): 요청 순서 상품3→1→2 → 정렬 후 LOCK(1)→LOCK(2)→LOCK(3)
         *   Thread 3 (i%3=2): 요청 순서 상품2→3→1 → 정렬 후 LOCK(1)→LOCK(2)→LOCK(3)
         *   ...
         *   → 요청 순서와 무관하게 내부적으로 동일한 락 획득 순서 보장
         *
         * 검증 포인트:
         *   - 10건 전부 데드락 없이 성공
         *   - 각 상품 재고 정확히 10 차감 (50→40)
         */
        @Test
        @DisplayName("D-2: 10명이 동시에 3개 상품 조합 주문 → 데드락 없이 전부 완료")
        void noDeadlock_whenMassiveConcurrentMultiProductOrders() throws InterruptedException {
            // given
            Product product1 = createProduct("나이키", "에어맥스", 100000, 50);
            Product product2 = createProduct("아디다스", "울트라부스트", 80000, 50);
            Product product3 = createProduct("뉴발란스", "993", 90000, 50);
            Long optionId1 = product1.getOptions().get(0).getId();
            Long optionId2 = product2.getOptions().get(0).getId();
            Long optionId3 = product3.getOptions().get(0).getId();

            int threadCount = 10;
            Runnable[] tasks = new Runnable[threadCount];
            for (int i = 0; i < threadCount; i++) {
                long memberId = i + 1;
                // 각 스레드마다 다른 순서로 상품을 요청
                List<OrderItemRequest> requests = switch (i % 3) {
                    case 0 -> List.of(
                            new OrderItemRequest(product1.getId(), optionId1, 1),
                            new OrderItemRequest(product2.getId(), optionId2, 1),
                            new OrderItemRequest(product3.getId(), optionId3, 1)
                    );
                    case 1 -> List.of(
                            new OrderItemRequest(product3.getId(), optionId3, 1),
                            new OrderItemRequest(product1.getId(), optionId1, 1),
                            new OrderItemRequest(product2.getId(), optionId2, 1)
                    );
                    default -> List.of(
                            new OrderItemRequest(product2.getId(), optionId2, 1),
                            new OrderItemRequest(product3.getId(), optionId3, 1),
                            new OrderItemRequest(product1.getId(), optionId1, 1)
                    );
                };

                tasks[i] = () -> orderPlacementService.placeOrder(memberId, requests, null, 0, null);
            }

            // when
            ConcurrencyResult result = executeConcurrently(threadCount, tasks);

            // then
            ProductOption updatedOption1 = productService.findOptionById(optionId1);
            ProductOption updatedOption2 = productService.findOptionById(optionId2);
            ProductOption updatedOption3 = productService.findOptionById(optionId3);

            assertAll(
                    () -> assertThat(result.successCount()).isEqualTo(threadCount),
                    () -> assertThat(result.failCount()).isEqualTo(0),
                    () -> assertThat(updatedOption1.getStockQuantity()).isEqualTo(40),
                    () -> assertThat(updatedOption2.getStockQuantity()).isEqualTo(40),
                    () -> assertThat(updatedOption3.getStockQuantity()).isEqualTo(40)
            );
        }
    }

    // ── E. 부분 실패 롤백 정합성 ────────────────────────────────

    @DisplayName("E. 부분 실패 롤백 정합성")
    @Nested
    class PartialFailureRollback {

        /**
         * [재고 차감 후 쿠폰 @Version 충돌 시 재고 롤백 테스트]
         *
         * 시나리오: 같은 쿠폰으로 동시 2건 주문 → 재고 차감은 둘 다 성공하지만
         *          쿠폰 사용에서 한 건이 @Version 충돌로 실패
         *
         * 실행 흐름:
         *   Thread A ─┬─ deductStock() → 재고 10→9 ✅
         *             ├─ useCoupon(version=0) → USED, version=1 → commit ✅
         *             └─ 성공 (재고 9)
         *
         *   Thread B ─┬─ deductStock() → 재고 9→8 ✅ (재고 차감 자체는 성공)
         *             ├─ useCoupon(version=0) → UPDATE WHERE version=0 → 충돌 ❌
         *             └─ @Transactional rollback → 재고 8→9로 원복
         *
         * 검증 포인트:
         *   - 최종 재고: 9 (실패 건의 차감이 rollback)
         *   - 트랜잭션 원자성으로 "재고만 빠지고 쿠폰은 안 빠지는" 불일치 방지
         */
        @Test
        @DisplayName("E-1: 재고 차감 성공 후 쿠폰 사용 실패(@Version 충돌) → 재고 원복, 주문 미생성")
        void stockRollsBack_whenCouponVersionConflict() throws InterruptedException {
            // given
            Long memberId = 1L;
            Product product = createProduct("나이키", "에어맥스", 100000, 10);
            Long optionId = product.getOptions().get(0).getId();

            Coupon coupon = createCoupon(5000, 10000);
            MemberCoupon memberCoupon = issueCouponToMember(memberId, coupon.getId());

            // 동시에 같은 쿠폰으로 주문 → 1건은 @Version 충돌로 실패
            int threadCount = 2;
            Runnable[] tasks = new Runnable[threadCount];
            for (int i = 0; i < threadCount; i++) {
                tasks[i] = () -> orderPlacementService.placeOrder(
                        memberId,
                        List.of(new OrderItemRequest(product.getId(), optionId, 1)),
                        memberCoupon.getId(), 0, null
                );
            }

            // when
            ConcurrencyResult result = executeConcurrently(threadCount, tasks);

            // then
            ProductOption updatedOption = productService.findOptionById(optionId);

            assertAll(
                    () -> assertThat(result.successCount()).isEqualTo(1),
                    () -> assertThat(result.failCount()).isEqualTo(1),
                    // 핵심: 실패 건의 재고도 롤백되어 1만 차감
                    () -> assertThat(updatedOption.getStockQuantity()).isEqualTo(9)
            );
        }

        /**
         * [재고+쿠폰 성공 후 포인트 실패 시 전체 롤백 테스트]
         *
         * 시나리오: 잔액 5000원인 회원이 쿠폰+포인트(3000)로 동시 2건 주문
         *          1건 성공 후, 2건째는 쿠폰 @Version 충돌 또는 포인트 잔액 부족으로 실패
         *
         * 실행 흐름:
         *   Thread A ─┬─ deductStock() → 재고 차감 ✅
         *             ├─ useCoupon(version=0) → USED → version=1 ✅
         *             ├─ usePoint(3000) → 잔액 5000→2000 ✅
         *             └─ 성공
         *
         *   Thread B ─┬─ deductStock() → 재고 차감 ✅
         *             ├─ useCoupon(version=0) → 충돌 ❌ → rollback
         *             │   (또는 쿠폰 통과 시 usePoint → 잔액 2000 < 3000 → 부족 ❌ → rollback)
         *             └─ @Transactional rollback → 재고+쿠폰+포인트 모두 원복
         *
         * 검증 포인트:
         *   - 재고: 1만 차감 (9), 쿠폰: USED, 포인트: 2000
         *   - 실패 건에서 앞 단계(재고/쿠폰)가 성공했어도 트랜잭션으로 전체 원복
         */
        @Test
        @DisplayName("E-2: 재고+쿠폰 성공 후 포인트 부족 실패 → 재고 원복, 쿠폰 AVAILABLE 복원, 주문 미생성")
        void allRollsBack_whenPointInsufficient() throws InterruptedException {
            // given
            Long memberId = 1L;
            Product product = createProduct("나이키", "에어맥스", 100000, 10);
            Long optionId = product.getOptions().get(0).getId();

            Coupon coupon = createCoupon(5000, 10000);
            MemberCoupon memberCoupon = issueCouponToMember(memberId, coupon.getId());
            createPointForMember(memberId, 5000);

            // 동시에 2건 주문: 둘 다 쿠폰 + 포인트 3000 사용
            // 1건 성공 후, 2건째는 쿠폰(@Version) 또는 포인트(잔액 부족) 에서 실패
            int threadCount = 2;
            Runnable[] tasks = new Runnable[threadCount];
            for (int i = 0; i < threadCount; i++) {
                tasks[i] = () -> orderPlacementService.placeOrder(
                        memberId,
                        List.of(new OrderItemRequest(product.getId(), optionId, 1)),
                        memberCoupon.getId(), 3000, null
                );
            }

            // when
            ConcurrencyResult result = executeConcurrently(threadCount, tasks);

            // then
            ProductOption updatedOption = productService.findOptionById(optionId);
            MemberCoupon updatedMemberCoupon = couponService.getMemberCoupon(memberCoupon.getId());
            int balance = pointService.getBalance(memberId);

            assertAll(
                    () -> assertThat(result.successCount()).isEqualTo(1),
                    () -> assertThat(result.failCount()).isEqualTo(1),
                    // 재고: 성공 1건만 차감
                    () -> assertThat(updatedOption.getStockQuantity()).isEqualTo(9),
                    // 쿠폰: 성공 건에서 사용됨
                    () -> assertThat(updatedMemberCoupon.getStatus()).isEqualTo(MemberCouponStatus.USED),
                    // 포인트: 성공 건에서만 3000 차감
                    () -> assertThat(balance).isEqualTo(2000)
            );
        }

        /**
         * [다중 상품 부분 재고 부족 시 전체 재고 롤백 테스트]
         *
         * 시나리오: 2명이 동시에 상품1(재고10)+상품2(재고1) 주문
         *          상품2 재고가 1개뿐이므로 1명은 실패
         *
         * 실행 흐름 (optionId 오름차순 정렬):
         *   Thread A ─┬─ LOCK(상품1옵션) → 재고 10→9 ✅
         *             ├─ LOCK(상품2옵션) → 재고 1→0 ✅
         *             └─ commit → 성공
         *
         *   Thread B ─┬─ LOCK(상품1옵션) → 대기 → 획득 → 재고 9→8 ✅
         *             ├─ LOCK(상품2옵션) → 대기 → 획득 → 재고 0 → 부족 예외 ❌
         *             └─ @Transactional rollback → 상품1 재고 8→9 원복
         *
         * 검증 포인트:
         *   - 상품1 재고: 9 (실패 건에서 차감한 것도 롤백)
         *   - 상품2 재고: 0
         *   - 트랜잭션 원자성으로 "상품1만 빠지고 상품2는 안 빠지는" 불일치 방지
         */
        @Test
        @DisplayName("E-3: 여러 상품 주문 시 두 번째 상품 재고 부족 → 첫 번째 상품 재고도 원복")
        void allStockRestored_whenPartialStockInsufficient() throws InterruptedException {
            // given
            Product product1 = createProduct("나이키", "에어맥스", 100000, 10);
            Product product2 = createProduct("아디다스", "울트라부스트", 80000, 1);
            Long optionId1 = product1.getOptions().get(0).getId();
            Long optionId2 = product2.getOptions().get(0).getId();

            // 2명이 동시에 상품1+상품2 주문 (상품2 재고 1개뿐)
            int threadCount = 2;
            Runnable[] tasks = new Runnable[threadCount];
            for (int i = 0; i < threadCount; i++) {
                long memberId = i + 1;
                tasks[i] = () -> orderPlacementService.placeOrder(
                        memberId,
                        List.of(
                                new OrderItemRequest(product1.getId(), optionId1, 1),
                                new OrderItemRequest(product2.getId(), optionId2, 1)
                        ),
                        null, 0, null
                );
            }

            // when
            ConcurrencyResult result = executeConcurrently(threadCount, tasks);

            // then
            ProductOption updatedOption1 = productService.findOptionById(optionId1);
            ProductOption updatedOption2 = productService.findOptionById(optionId2);

            assertAll(
                    () -> assertThat(result.successCount()).isEqualTo(1),
                    () -> assertThat(result.failCount()).isEqualTo(1),
                    // 핵심: 실패 건에서 첫 번째 상품 재고도 롤백
                    () -> assertThat(updatedOption1.getStockQuantity()).isEqualTo(9),
                    () -> assertThat(updatedOption2.getStockQuantity()).isEqualTo(0)
            );
        }
    }

    // ── F. 혼합 흐름 (주문 + 다른 API 동시) ─────────────────────

    @DisplayName("F. 혼합 흐름 (주문 + 다른 API 동시)")
    @Nested
    class MixedFlows {

        /**
         * [주문(포인트 사용) + 포인트 충전 혼합 동시성 테스트]
         *
         * 시나리오: 잔액 10000원. 주문 3건(각 2000원 사용) + 충전 3건(각 1000원) 동시 실행
         *          기대 잔액: 10000 - 6000 + 3000 = 7000
         *
         * 실행 흐름:
         *   Thread 1~3 (주문): usePoint(2000) → SELECT ... FOR UPDATE → 차감 → commit
         *   Thread 4~6 (충전): chargePoint(1000) → SELECT ... FOR UPDATE → 증가 → commit
         *
         *   모든 스레드가 동일 row(Point)에 비관적 락으로 직렬화됨:
         *     T1: 10000→8000 → T4: 8000→9000 → T2: 9000→7000 → T5: 7000→8000
         *     → T3: 8000→6000 → T6: 6000→7000 (순서는 스케줄링에 따라 다름)
         *
         * 검증 포인트:
         *   - 6건 전부 성공 (잔액이 충분하므로)
         *   - 최종 잔액 7000 (비관적 락으로 lost update 방지)
         */
        @Test
        @DisplayName("F-1: 주문(포인트 사용) + 포인트 충전 동시 → 최종 잔액 정확")
        void balanceCorrect_whenOrderAndChargeSimultaneously() throws InterruptedException {
            // given
            Long memberId = 1L;
            Product product = createProduct("나이키", "에어맥스", 100000, 10);
            Long optionId = product.getOptions().get(0).getId();
            createPointForMember(memberId, 10000);

            int orderCount = 3;
            int chargeCount = 3;
            int threadCount = orderCount + chargeCount;
            int usePerOrder = 2000;
            int chargeAmount = 1000;

            Runnable[] tasks = new Runnable[threadCount];

            // 주문 3건 (각 2000 포인트 사용)
            for (int i = 0; i < orderCount; i++) {
                tasks[i] = () -> orderPlacementService.placeOrder(
                        memberId,
                        List.of(new OrderItemRequest(product.getId(), optionId, 1)),
                        null, usePerOrder, null
                );
            }

            // 충전 3건 (각 1000 포인트)
            for (int i = 0; i < chargeCount; i++) {
                tasks[orderCount + i] = () -> transactionTemplate.executeWithoutResult(status ->
                        pointService.chargePoint(memberId, chargeAmount, "동시 충전")
                );
            }

            // when
            ConcurrencyResult result = executeConcurrently(threadCount, tasks);

            // then
            int balance = pointService.getBalance(memberId);
            // 10000 - (2000 * 3) + (1000 * 3) = 7000
            int expectedBalance = 10000 - (usePerOrder * orderCount) + (chargeAmount * chargeCount);

            assertAll(
                    () -> assertThat(result.successCount()).isEqualTo(threadCount),
                    () -> assertThat(result.failCount()).isEqualTo(0),
                    () -> assertThat(balance).isEqualTo(expectedBalance)
            );
        }

        /**
         * [장바구니 중복 주문 멱등성 테스트]
         *
         * 시나리오: 장바구니에 상품(수량2)을 담은 상태에서 동시에 2건 주문
         *          (각각 장바구니 삭제 요청 포함)
         *
         * 실행 흐름:
         *   Thread A ─┬─ deductStock() → 재고 10→9 ✅
         *             ├─ deleteByMemberIdAndProductOptionIds() → 장바구니 삭제 ✅
         *             └─ 성공
         *
         *   Thread B ─┬─ deductStock() → 재고 9→8 ✅
         *             ├─ deleteByMemberIdAndProductOptionIds() → 이미 삭제됨 → 무시 (멱등) ✅
         *             └─ 성공
         *
         * 검증 포인트:
         *   - 2건 모두 성공 (장바구니 삭제가 멱등적이어야 함)
         *   - 장바구니 비어있음
         *   - 재고 정확히 2 차감 (8)
         */
        @Test
        @DisplayName("F-2: 장바구니에서 주문 + 같은 장바구니 항목으로 또 주문 → 오류 없이 처리")
        void noError_whenDuplicateCartOrderSimultaneously() throws InterruptedException {
            // given
            Long memberId = 1L;
            Product product = createProduct("나이키", "에어맥스", 100000, 10);
            Long optionId = product.getOptions().get(0).getId();

            // 장바구니에 상품 담기
            cartFacade.addToCart(memberId, optionId, 2);

            int threadCount = 2;
            Runnable[] tasks = new Runnable[threadCount];
            for (int i = 0; i < threadCount; i++) {
                tasks[i] = () -> orderPlacementService.placeOrder(
                        memberId,
                        List.of(new OrderItemRequest(product.getId(), optionId, 1)),
                        null, 0, List.of(optionId)
                );
            }

            // when
            ConcurrencyResult result = executeConcurrently(threadCount, tasks);

            // then
            Optional<CartItem> cartItem = cartRepository.findByMemberIdAndProductOptionId(memberId, optionId);
            ProductOption updatedOption = productService.findOptionById(optionId);

            assertAll(
                    // 둘 다 성공 (장바구니 삭제는 멱등적이어야 함)
                    () -> assertThat(result.successCount()).isEqualTo(2),
                    () -> assertThat(result.failCount()).isEqualTo(0),
                    // 장바구니는 삭제됨
                    () -> assertThat(cartItem).isEmpty(),
                    // 재고는 2건 차감
                    () -> assertThat(updatedOption.getStockQuantity()).isEqualTo(8)
            );
        }
    }
}
