package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [통합 테스트 - Service Integration]
 *
 * 테스트 대상: CouponService (Domain Layer)
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: Service → Repository → Database
 */
@SpringBootTest
class CouponServiceIntegrationTest {

    @Autowired
    private CouponService couponService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Coupon createValidCoupon(String name, int totalQuantity) {
        Coupon coupon = new Coupon(
                name,
                CouponScope.CART,
                null,
                DiscountType.FIXED_AMOUNT,
                5000,
                10000,
                0,
                totalQuantity,
                ZonedDateTime.now().minusDays(1),
                ZonedDateTime.now().plusDays(30)
        );
        return couponService.createCoupon(coupon);
    }

    @DisplayName("쿠폰을 생성할 때,")
    @Nested
    class CreateCoupon {

        @Test
        @DisplayName("유효한 정보로 생성하면, 쿠폰이 생성된다.")
        void createsCoupon_whenValidInput() {
            // given
            Coupon coupon = new Coupon(
                    "신규가입 쿠폰",
                    CouponScope.CART,
                    null,
                    DiscountType.FIXED_AMOUNT,
                    5000,
                    10000,
                    0,
                    100,
                    ZonedDateTime.now().minusDays(1),
                    ZonedDateTime.now().plusDays(30)
            );

            // when
            Coupon savedCoupon = couponService.createCoupon(coupon);

            // then
            assertAll(
                    () -> assertThat(savedCoupon.getId()).isNotNull(),
                    () -> assertThat(savedCoupon.getName()).isEqualTo("신규가입 쿠폰"),
                    () -> assertThat(savedCoupon.getCouponScope()).isEqualTo(CouponScope.CART),
                    () -> assertThat(savedCoupon.getDiscountType()).isEqualTo(DiscountType.FIXED_AMOUNT),
                    () -> assertThat(savedCoupon.getDiscountValue()).isEqualTo(5000),
                    () -> assertThat(savedCoupon.getTotalQuantity()).isEqualTo(100),
                    () -> assertThat(savedCoupon.getIssuedQuantity()).isEqualTo(0)
            );
        }
    }

    @DisplayName("쿠폰을 다운로드할 때,")
    @Nested
    class DownloadCoupon {

        @Test
        @DisplayName("쿠폰을 다운로드하면, issuedQuantity가 증가한다.")
        void incrementsIssuedQuantity_whenDownload() {
            // given
            Coupon savedCoupon = createValidCoupon("신규가입 쿠폰", 100);
            Long memberId = 1L;

            // when
            MemberCoupon memberCoupon = couponService.downloadCoupon(memberId, savedCoupon.getId());

            // then
            assertAll(
                    () -> assertThat(memberCoupon.getId()).isNotNull(),
                    () -> assertThat(memberCoupon.getMemberId()).isEqualTo(memberId),
                    () -> assertThat(memberCoupon.getCouponId()).isEqualTo(savedCoupon.getId()),
                    () -> assertThat(memberCoupon.getStatus()).isEqualTo(MemberCouponStatus.AVAILABLE)
            );

            Coupon updatedCoupon = couponService.findById(savedCoupon.getId());
            assertThat(updatedCoupon.getIssuedQuantity()).isEqualTo(1);
        }

        @Test
        @DisplayName("이미 다운로드한 쿠폰이면, CONFLICT 예외가 발생한다.")
        void throwsException_whenAlreadyDownloaded() {
            // given
            Coupon savedCoupon = createValidCoupon("신규가입 쿠폰", 100);
            Long memberId = 1L;
            couponService.downloadCoupon(memberId, savedCoupon.getId());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> couponService.downloadCoupon(memberId, savedCoupon.getId()));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                    () -> assertThat(exception.getMessage()).contains("이미 다운로드한 쿠폰")
            );
        }
    }

    @DisplayName("유효한 쿠폰 목록을 조회할 때,")
    @Nested
    class FindAvailableCoupons {

        @Test
        @DisplayName("유효기간 내 쿠폰이 있으면, 목록을 반환한다.")
        void returnsAvailableCoupons() {
            // given
            createValidCoupon("쿠폰A", 100);
            createValidCoupon("쿠폰B", 50);

            // when
            List<Coupon> coupons = couponService.findAvailableCoupons();

            // then
            assertThat(coupons).hasSize(2);
        }
    }

    @DisplayName("내 쿠폰 목록을 조회할 때,")
    @Nested
    class FindMyCoupons {

        @Test
        @DisplayName("다운로드한 쿠폰이 있으면, 목록을 반환한다.")
        void returnsMyCoupons() {
            // given
            Coupon coupon1 = createValidCoupon("쿠폰A", 100);
            Coupon coupon2 = createValidCoupon("쿠폰B", 100);
            Long memberId = 1L;
            couponService.downloadCoupon(memberId, coupon1.getId());
            couponService.downloadCoupon(memberId, coupon2.getId());

            // when
            List<MemberCoupon> myCoupons = couponService.findMyCoupons(memberId);

            // then
            assertThat(myCoupons).hasSize(2);
        }
    }

    @DisplayName("쿠폰을 사용할 때,")
    @Nested
    class UseCoupon {

        @Test
        @DisplayName("유효한 쿠폰을 사용하면, USED 상태로 전환된다.")
        void usesCoupon_whenValid() {
            // given
            Coupon savedCoupon = createValidCoupon("신규가입 쿠폰", 100);
            Long memberId = 1L;
            MemberCoupon downloadedCoupon = couponService.downloadCoupon(memberId, savedCoupon.getId());
            Long orderId = 1L;

            // when
            MemberCoupon usedCoupon = couponService.useCoupon(downloadedCoupon.getId(), orderId);

            // then
            assertAll(
                    () -> assertThat(usedCoupon.getStatus()).isEqualTo(MemberCouponStatus.USED),
                    () -> assertThat(usedCoupon.getOrderId()).isEqualTo(orderId),
                    () -> assertThat(usedCoupon.getUsedAt()).isNotNull()
            );
        }
    }
}
