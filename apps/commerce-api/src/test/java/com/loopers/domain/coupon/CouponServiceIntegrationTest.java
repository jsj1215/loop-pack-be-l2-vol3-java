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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

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
@Transactional
class CouponServiceIntegrationTest {

    @Autowired
    private CouponService couponService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Coupon createValidCoupon(String name) {
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
                    () -> assertThat(savedCoupon.getDiscountValue()).isEqualTo(5000)
            );
        }
    }

    @DisplayName("쿠폰을 다운로드할 때,")
    @Nested
    class DownloadCoupon {

        @Test
        @DisplayName("쿠폰을 다운로드하면, MemberCouponDetail을 반환한다.")
        void returnsMemberCouponDetail_whenDownload() {
            // given
            Coupon savedCoupon = createValidCoupon("신규가입 쿠폰");
            Long memberId = 1L;

            // when
            MemberCouponDetail detail = couponService.downloadCoupon(memberId, savedCoupon.getId());

            // then
            assertAll(
                    () -> assertThat(detail.memberCoupon().getId()).isNotNull(),
                    () -> assertThat(detail.memberCoupon().getMemberId()).isEqualTo(memberId),
                    () -> assertThat(detail.memberCoupon().getCouponId()).isEqualTo(savedCoupon.getId()),
                    () -> assertThat(detail.memberCoupon().getStatus()).isEqualTo(MemberCouponStatus.AVAILABLE),
                    () -> assertThat(detail.coupon().getId()).isEqualTo(savedCoupon.getId())
            );
        }

        @Test
        @DisplayName("이미 다운로드한 쿠폰이면, CONFLICT 예외가 발생한다.")
        void throwsException_whenAlreadyDownloaded() {
            // given
            Coupon savedCoupon = createValidCoupon("신규가입 쿠폰");
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

    @DisplayName("내 쿠폰 목록을 조회할 때,")
    @Nested
    class FindMyCoupons {

        @Test
        @DisplayName("다운로드한 쿠폰이 있으면, 목록을 반환한다.")
        void returnsMyCoupons() {
            // given
            Coupon coupon1 = createValidCoupon("쿠폰A");
            Coupon coupon2 = createValidCoupon("쿠폰B");
            Long memberId = 1L;
            couponService.downloadCoupon(memberId, coupon1.getId());
            couponService.downloadCoupon(memberId, coupon2.getId());

            // when
            List<MemberCoupon> myCoupons = couponService.findMyCoupons(memberId);

            // then
            assertThat(myCoupons).hasSize(2);
        }
    }

    @DisplayName("내 쿠폰 전체 목록을 조회할 때,")
    @Nested
    class FindAllMyCoupons {

        @Test
        @DisplayName("다운로드한 모든 쿠폰을 상태와 관계없이 반환한다.")
        void returnsAllMyCoupons() {
            // given
            Coupon coupon1 = createValidCoupon("쿠폰A");
            Coupon coupon2 = createValidCoupon("쿠폰B");
            Long memberId = 1L;
            couponService.downloadCoupon(memberId, coupon1.getId());
            MemberCouponDetail detail2 = couponService.downloadCoupon(memberId, coupon2.getId());
            couponService.useCoupon(memberId, detail2.memberCoupon().getId(), 1L);

            // when
            List<MemberCoupon> myCoupons = couponService.findAllMyCoupons(memberId);

            // then
            assertAll(
                    () -> assertThat(myCoupons).hasSize(2),
                    () -> assertThat(myCoupons).anyMatch(mc -> mc.getStatus() == MemberCouponStatus.AVAILABLE),
                    () -> assertThat(myCoupons).anyMatch(mc -> mc.getStatus() == MemberCouponStatus.USED)
            );
        }

        @Test
        @DisplayName("다운로드한 쿠폰이 없으면 빈 목록을 반환한다.")
        void returnsEmptyList_whenNoCoupons() {
            // given
            Long memberId = 999L;

            // when
            List<MemberCoupon> myCoupons = couponService.findAllMyCoupons(memberId);

            // then
            assertThat(myCoupons).isEmpty();
        }
    }

    @DisplayName("내 쿠폰 상세 목록을 조회할 때,")
    @Nested
    class GetMyCouponDetails {

        @Test
        @DisplayName("MemberCoupon과 Coupon을 조합한 상세 목록을 반환한다.")
        void returnsMyCouponDetails() {
            // given
            Coupon coupon1 = createValidCoupon("쿠폰A");
            Coupon coupon2 = createValidCoupon("쿠폰B");
            Long memberId = 1L;
            couponService.downloadCoupon(memberId, coupon1.getId());
            couponService.downloadCoupon(memberId, coupon2.getId());

            // when
            List<MemberCouponDetail> details = couponService.getMyCouponDetails(memberId);

            // then
            assertAll(
                    () -> assertThat(details).hasSize(2),
                    () -> assertThat(details.get(0).coupon().getName()).isEqualTo("쿠폰A"),
                    () -> assertThat(details.get(1).coupon().getName()).isEqualTo("쿠폰B"),
                    () -> assertThat(details.get(0).memberCoupon().getMemberId()).isEqualTo(memberId),
                    () -> assertThat(details.get(1).memberCoupon().getMemberId()).isEqualTo(memberId)
            );
        }

        @Test
        @DisplayName("내 쿠폰이 없으면 빈 목록을 반환한다.")
        void returnsEmptyList_whenNoCoupons() {
            // given
            Long memberId = 999L;

            // when
            List<MemberCouponDetail> details = couponService.getMyCouponDetails(memberId);

            // then
            assertThat(details).isEmpty();
        }
    }

    @DisplayName("쿠폰을 수정할 때,")
    @Nested
    class UpdateCoupon {

        @Test
        @DisplayName("유효한 정보로 수정하면, 쿠폰이 수정된다.")
        void updatesCoupon_whenValidInput() {
            // given
            Coupon savedCoupon = createValidCoupon("원본쿠폰");
            ZonedDateTime now = ZonedDateTime.now();

            // when
            Coupon updatedCoupon = couponService.updateCoupon(
                    savedCoupon.getId(),
                    "수정된쿠폰",
                    CouponScope.PRODUCT,
                    200L,
                    DiscountType.FIXED_RATE,
                    15,
                    20000,
                    5000,
                    now.minusDays(1),
                    now.plusDays(60));

            // then
            assertAll(
                    () -> assertThat(updatedCoupon.getName()).isEqualTo("수정된쿠폰"),
                    () -> assertThat(updatedCoupon.getCouponScope()).isEqualTo(CouponScope.PRODUCT),
                    () -> assertThat(updatedCoupon.getTargetId()).isEqualTo(200L),
                    () -> assertThat(updatedCoupon.getDiscountType()).isEqualTo(DiscountType.FIXED_RATE),
                    () -> assertThat(updatedCoupon.getDiscountValue()).isEqualTo(15),
                    () -> assertThat(updatedCoupon.getMinOrderAmount()).isEqualTo(20000),
                    () -> assertThat(updatedCoupon.getMaxDiscountAmount()).isEqualTo(5000)
            );
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰을 수정하면, NOT_FOUND 예외가 발생한다.")
        void throwsException_whenCouponNotFound() {
            // given
            ZonedDateTime now = ZonedDateTime.now();

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> couponService.updateCoupon(999L, "쿠폰", CouponScope.CART, null,
                            DiscountType.FIXED_AMOUNT, 1000, 5000, 0,
                            now.minusDays(1), now.plusDays(30)));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("쿠폰을 삭제할 때,")
    @Nested
    class SoftDeleteCoupon {

        @Test
        @DisplayName("존재하는 쿠폰을 삭제하면, soft delete 처리된다.")
        void softDeletesCoupon_whenExists() {
            // given
            Coupon savedCoupon = createValidCoupon("삭제할쿠폰");

            // when
            couponService.softDelete(savedCoupon.getId());

            // then
            CoreException exception = assertThrows(CoreException.class,
                    () -> couponService.findById(savedCoupon.getId()));
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰을 삭제하면, NOT_FOUND 예외가 발생한다.")
        void throwsException_whenCouponNotFound() {
            // given
            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> couponService.softDelete(999L));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("쿠폰 발급 내역을 조회할 때,")
    @Nested
    class FindCouponIssues {

        @Test
        @DisplayName("발급 내역이 있으면, 페이지 결과를 반환한다.")
        void returnsCouponIssues() {
            // given
            Coupon savedCoupon = createValidCoupon("발급내역 테스트");
            couponService.downloadCoupon(1L, savedCoupon.getId());
            couponService.downloadCoupon(2L, savedCoupon.getId());

            // when
            Page<MemberCoupon> issues = couponService.findCouponIssues(
                    savedCoupon.getId(), PageRequest.of(0, 20));

            // then
            assertAll(
                    () -> assertThat(issues.getContent()).hasSize(2),
                    () -> assertThat(issues.getTotalElements()).isEqualTo(2)
            );
        }

        @Test
        @DisplayName("발급 내역이 없으면, 빈 페이지를 반환한다.")
        void returnsEmptyPage_whenNoIssues() {
            // given
            Coupon savedCoupon = createValidCoupon("발급없는쿠폰");

            // when
            Page<MemberCoupon> issues = couponService.findCouponIssues(
                    savedCoupon.getId(), PageRequest.of(0, 20));

            // then
            assertAll(
                    () -> assertThat(issues.getContent()).isEmpty(),
                    () -> assertThat(issues.getTotalElements()).isEqualTo(0)
            );
        }
    }

    @DisplayName("쿠폰을 사용할 때,")
    @Nested
    class UseCoupon {

        @Test
        @DisplayName("유효한 쿠폰을 사용하면, USED 상태로 전환된다.")
        void usesCoupon_whenValid() {
            // given
            Coupon savedCoupon = createValidCoupon("신규가입 쿠폰");
            Long memberId = 1L;
            MemberCouponDetail downloadDetail = couponService.downloadCoupon(memberId, savedCoupon.getId());
            Long orderId = 1L;

            // when
            MemberCoupon usedCoupon = couponService.useCoupon(memberId, downloadDetail.memberCoupon().getId(), orderId);

            // then
            assertAll(
                    () -> assertThat(usedCoupon.getStatus()).isEqualTo(MemberCouponStatus.USED),
                    () -> assertThat(usedCoupon.getOrderId()).isEqualTo(orderId),
                    () -> assertThat(usedCoupon.getUsedAt()).isNotNull()
            );
        }
    }
}
