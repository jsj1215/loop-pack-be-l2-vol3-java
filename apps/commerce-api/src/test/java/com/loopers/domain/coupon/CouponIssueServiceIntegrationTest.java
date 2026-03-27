package com.loopers.domain.coupon;

import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.infrastructure.coupon.MemberCouponJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * [통합 테스트 - Service Integration]
 *
 * 테스트 대상: CouponIssueService (Domain Layer)
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: Service → Repository → Database (Testcontainers)
 *
 * 검증 목적:
 * - 선착순 발급 요청이 실제 DB에서 올바르게 동작하는지
 * - UK 제약(member_id + coupon_id)으로 중복 요청이 방지되는지
 * - 상태 기반 검증(REQUESTED/AVAILABLE/FAILED)이 DB 레벨에서 정확한지
 */
@SpringBootTest
@Transactional
@DisplayName("CouponIssueService 통합 테스트")
class CouponIssueServiceIntegrationTest {

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private MemberCouponJpaRepository memberCouponJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Coupon createLimitedCoupon(int maxIssueCount) {
        ZonedDateTime now = ZonedDateTime.now();
        Coupon coupon = new Coupon("선착순 쿠폰", CouponScope.CART, null,
                DiscountType.FIXED_AMOUNT, 5000, 10000, 0,
                now.minusDays(1), now.plusDays(30), maxIssueCount);
        return couponJpaRepository.save(coupon);
    }

    @Nested
    @DisplayName("발급 요청 생성")
    class CreateIssueRequest {

        @Test
        @DisplayName("정상 요청 시 REQUESTED 상태로 DB에 저장된다")
        void savesRequestedMemberCoupon() {
            // given
            Coupon coupon = createLimitedCoupon(100);
            Long memberId = 1L;

            // when
            MemberCoupon result = couponIssueService.createIssueRequest(memberId, coupon.getId());

            // then
            assertAll(
                    () -> assertThat(result.getId()).isNotNull(),
                    () -> assertThat(result.getMemberId()).isEqualTo(memberId),
                    () -> assertThat(result.getCouponId()).isEqualTo(coupon.getId()),
                    () -> assertThat(result.getStatus()).isEqualTo(MemberCouponStatus.REQUESTED)
            );
        }

        @Test
        @DisplayName("동일 회원이 같은 쿠폰에 대해 중복 요청하면 CONFLICT 예외가 발생한다")
        void throwsConflict_whenDuplicate() {
            // given
            Coupon coupon = createLimitedCoupon(100);
            Long memberId = 1L;
            couponIssueService.createIssueRequest(memberId, coupon.getId());

            // when & then
            assertThatThrownBy(() -> couponIssueService.createIssueRequest(memberId, coupon.getId()))
                    .isInstanceOf(CoreException.class)
                    .satisfies(ex -> assertThat(((CoreException) ex).getErrorType()).isEqualTo(ErrorType.CONFLICT));
        }

        @Test
        @DisplayName("유효기간이 만료된 쿠폰이면 BAD_REQUEST 예외가 발생한다")
        void throwsBadRequest_whenExpired() {
            // given
            ZonedDateTime now = ZonedDateTime.now();
            Coupon expiredCoupon = couponJpaRepository.save(new Coupon("만료 쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 5000, 10000, 0,
                    now.minusDays(30), now.minusDays(1), 100));

            // when & then
            assertThatThrownBy(() -> couponIssueService.createIssueRequest(1L, expiredCoupon.getId()))
                    .isInstanceOf(CoreException.class)
                    .satisfies(ex -> assertThat(((CoreException) ex).getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
        }

        @Test
        @DisplayName("선착순 쿠폰이 아니면(maxIssueCount=0) BAD_REQUEST 예외가 발생한다")
        void throwsBadRequest_whenUnlimitedCoupon() {
            // given
            ZonedDateTime now = ZonedDateTime.now();
            Coupon unlimitedCoupon = couponJpaRepository.save(new Coupon("일반 쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 5000, 10000, 0,
                    now.minusDays(1), now.plusDays(30)));

            // when & then
            assertThatThrownBy(() -> couponIssueService.createIssueRequest(1L, unlimitedCoupon.getId()))
                    .isInstanceOf(CoreException.class)
                    .satisfies(ex -> assertThat(((CoreException) ex).getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
        }

        @Test
        @DisplayName("FAILED 상태의 기존 요청이 있으면 기존 레코드를 REQUESTED로 되돌려 재요청한다")
        void retriesRequest_whenPreviouslyFailed() {
            // given
            Coupon coupon = createLimitedCoupon(100);
            Long memberId = 1L;
            MemberCoupon requested = couponIssueService.createIssueRequest(memberId, coupon.getId());
            requested.reject("수량 초과");
            memberCouponJpaRepository.saveAndFlush(requested);

            // when
            MemberCoupon retried = couponIssueService.createIssueRequest(memberId, coupon.getId());

            // then
            assertAll(
                    () -> assertThat(retried.getId()).isEqualTo(requested.getId()),
                    () -> assertThat(retried.getStatus()).isEqualTo(MemberCouponStatus.REQUESTED),
                    () -> assertThat(retried.getFailReason()).isNull()
            );
        }
    }

    @Nested
    @DisplayName("발급 상태 조회")
    class FindIssueStatus {

        @Test
        @DisplayName("요청 기록이 있으면 해당 MemberCoupon을 반환한다")
        void returnsExistingRecord() {
            // given
            Coupon coupon = createLimitedCoupon(100);
            couponIssueService.createIssueRequest(1L, coupon.getId());

            // when
            var result = couponIssueService.findIssueStatus(1L, coupon.getId());

            // then
            assertThat(result).isPresent();
            assertThat(result.get().getStatus()).isEqualTo(MemberCouponStatus.REQUESTED);
        }

        @Test
        @DisplayName("요청 기록이 없으면 빈 Optional을 반환한다")
        void returnsEmpty_whenNoRecord() {
            // when
            var result = couponIssueService.findIssueStatus(1L, 999L);

            // then
            assertThat(result).isEmpty();
        }
    }
}
