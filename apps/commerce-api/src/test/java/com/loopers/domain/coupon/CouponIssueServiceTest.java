package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CouponIssueService 단위 테스트")
@ExtendWith(MockitoExtension.class)
class CouponIssueServiceTest {

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private MemberCouponRepository memberCouponRepository;

    private CouponIssueService couponIssueService;

    @BeforeEach
    void setUp() {
        couponIssueService = new CouponIssueService(couponRepository, memberCouponRepository);
    }

    private Coupon createLimitedCoupon(Long id, int maxIssueCount) {
        ZonedDateTime now = ZonedDateTime.now();
        Coupon coupon = new Coupon("선착순 쿠폰", CouponScope.CART, null,
                DiscountType.FIXED_AMOUNT, 5000, 10000, 0,
                now.minusDays(1), now.plusDays(30), maxIssueCount);
        ReflectionTestUtils.setField(coupon, "id", id);
        return coupon;
    }

    private Coupon createUnlimitedCoupon(Long id) {
        ZonedDateTime now = ZonedDateTime.now();
        Coupon coupon = new Coupon("일반 쿠폰", CouponScope.CART, null,
                DiscountType.FIXED_AMOUNT, 3000, 5000, 0,
                now.minusDays(1), now.plusDays(30));
        ReflectionTestUtils.setField(coupon, "id", id);
        return coupon;
    }

    @Nested
    @DisplayName("선착순 발급 요청 생성")
    class CreateIssueRequest {

        @Test
        @DisplayName("정상 요청 시 REQUESTED 상태의 MemberCoupon을 저장한다")
        void createsRequestedMemberCoupon() {
            // given
            Long memberId = 1L;
            Long couponId = 100L;
            Coupon coupon = createLimitedCoupon(couponId, 100);
            when(couponRepository.findById(couponId)).thenReturn(Optional.of(coupon));
            when(memberCouponRepository.findByMemberIdAndCouponIdIncludingDeleted(memberId, couponId))
                    .thenReturn(Optional.empty());
            when(memberCouponRepository.save(any(MemberCoupon.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // when
            MemberCoupon result = couponIssueService.createIssueRequest(memberId, couponId);

            // then
            assertThat(result.getStatus()).isEqualTo(MemberCouponStatus.REQUESTED);
            assertThat(result.getMemberId()).isEqualTo(memberId);
            assertThat(result.getCouponId()).isEqualTo(couponId);

            ArgumentCaptor<MemberCoupon> captor = ArgumentCaptor.forClass(MemberCoupon.class);
            verify(memberCouponRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(MemberCouponStatus.REQUESTED);
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰이면 NOT_FOUND 예외가 발생한다")
        void throwsWhenCouponNotFound() {
            // given
            when(couponRepository.findById(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> couponIssueService.createIssueRequest(1L, 999L))
                    .isInstanceOf(CoreException.class)
                    .satisfies(ex -> assertThat(((CoreException) ex).getErrorType()).isEqualTo(ErrorType.NOT_FOUND));
        }

        @Test
        @DisplayName("유효기간이 만료된 쿠폰이면 BAD_REQUEST 예외가 발생한다")
        void throwsWhenCouponExpired() {
            // given
            Long couponId = 100L;
            ZonedDateTime now = ZonedDateTime.now();
            Coupon expiredCoupon = new Coupon("만료 쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 5000, 10000, 0,
                    now.minusDays(30), now.minusDays(1), 100);
            ReflectionTestUtils.setField(expiredCoupon, "id", couponId);
            when(couponRepository.findById(couponId)).thenReturn(Optional.of(expiredCoupon));

            // when & then
            assertThatThrownBy(() -> couponIssueService.createIssueRequest(1L, couponId))
                    .isInstanceOf(CoreException.class)
                    .satisfies(ex -> assertThat(((CoreException) ex).getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
        }

        @Test
        @DisplayName("선착순 쿠폰이 아니면 BAD_REQUEST 예외가 발생한다")
        void throwsWhenNotLimitedCoupon() {
            // given
            Long couponId = 100L;
            Coupon coupon = createUnlimitedCoupon(couponId);
            when(couponRepository.findById(couponId)).thenReturn(Optional.of(coupon));

            // when & then
            assertThatThrownBy(() -> couponIssueService.createIssueRequest(1L, couponId))
                    .isInstanceOf(CoreException.class)
                    .satisfies(ex -> {
                        CoreException coreEx = (CoreException) ex;
                        assertThat(coreEx.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                        assertThat(coreEx.getMessage()).contains("선착순 쿠폰이 아닙니다");
                    });
        }

        @Test
        @DisplayName("이미 REQUESTED 상태의 기존 요청이 있으면 CONFLICT 예외가 발생한다")
        void throwsWhenAlreadyRequested() {
            // given
            Long memberId = 1L;
            Long couponId = 100L;
            Coupon coupon = createLimitedCoupon(couponId, 100);
            when(couponRepository.findById(couponId)).thenReturn(Optional.of(coupon));

            MemberCoupon existing = MemberCoupon.createRequested(memberId, couponId);
            when(memberCouponRepository.findByMemberIdAndCouponIdIncludingDeleted(memberId, couponId))
                    .thenReturn(Optional.of(existing));

            // when & then
            assertThatThrownBy(() -> couponIssueService.createIssueRequest(memberId, couponId))
                    .isInstanceOf(CoreException.class)
                    .satisfies(ex -> assertThat(((CoreException) ex).getErrorType()).isEqualTo(ErrorType.CONFLICT));
            verify(memberCouponRepository, never()).save(any());
        }

        @Test
        @DisplayName("이미 AVAILABLE 상태의 기존 쿠폰이 있으면 CONFLICT 예외가 발생한다")
        void throwsWhenAlreadyIssued() {
            // given
            Long memberId = 1L;
            Long couponId = 100L;
            Coupon coupon = createLimitedCoupon(couponId, 100);
            when(couponRepository.findById(couponId)).thenReturn(Optional.of(coupon));

            MemberCoupon existing = new MemberCoupon(memberId, couponId); // status=AVAILABLE
            when(memberCouponRepository.findByMemberIdAndCouponIdIncludingDeleted(memberId, couponId))
                    .thenReturn(Optional.of(existing));

            // when & then
            assertThatThrownBy(() -> couponIssueService.createIssueRequest(memberId, couponId))
                    .isInstanceOf(CoreException.class)
                    .satisfies(ex -> assertThat(((CoreException) ex).getErrorType()).isEqualTo(ErrorType.CONFLICT));
        }

        @Test
        @DisplayName("FAILED 상태의 기존 요청이 있으면 기존 레코드를 REQUESTED로 되돌려 재요청한다")
        void allowsReRequestWhenPreviouslyFailed() {
            // given
            Long memberId = 1L;
            Long couponId = 100L;
            Coupon coupon = createLimitedCoupon(couponId, 100);
            when(couponRepository.findById(couponId)).thenReturn(Optional.of(coupon));

            MemberCoupon failed = MemberCoupon.createRequested(memberId, couponId);
            failed.reject("수량 초과");
            when(memberCouponRepository.findByMemberIdAndCouponIdIncludingDeleted(memberId, couponId))
                    .thenReturn(Optional.of(failed));
            when(memberCouponRepository.save(any(MemberCoupon.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // when
            MemberCoupon result = couponIssueService.createIssueRequest(memberId, couponId);

            // then
            assertThat(result.getStatus()).isEqualTo(MemberCouponStatus.REQUESTED);
            assertThat(result.getFailReason()).isNull();
            // 기존 레코드를 재사용하므로 같은 객체가 save된다
            ArgumentCaptor<MemberCoupon> captor = ArgumentCaptor.forClass(MemberCoupon.class);
            verify(memberCouponRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(failed);
        }
    }

    @Nested
    @DisplayName("발급 상태 조회")
    class FindIssueStatus {

        @Test
        @DisplayName("존재하는 레코드를 반환한다")
        void returnsExistingRecord() {
            // given
            Long memberId = 1L;
            Long couponId = 100L;
            MemberCoupon memberCoupon = MemberCoupon.createRequested(memberId, couponId);
            when(memberCouponRepository.findByMemberIdAndCouponIdIncludingDeleted(memberId, couponId))
                    .thenReturn(Optional.of(memberCoupon));

            // when
            Optional<MemberCoupon> result = couponIssueService.findIssueStatus(memberId, couponId);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().getStatus()).isEqualTo(MemberCouponStatus.REQUESTED);
        }

        @Test
        @DisplayName("요청하지 않은 경우 빈 Optional을 반환한다")
        void returnsEmptyWhenNotRequested() {
            // given
            when(memberCouponRepository.findByMemberIdAndCouponIdIncludingDeleted(1L, 100L))
                    .thenReturn(Optional.empty());

            // when
            Optional<MemberCoupon> result = couponIssueService.findIssueStatus(1L, 100L);

            // then
            assertThat(result).isEmpty();
        }
    }
}
