package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.CouponService;
import com.loopers.domain.coupon.DiscountType;
import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.coupon.MemberCouponDetail;
import com.loopers.domain.coupon.MemberCouponStatus;
import com.loopers.domain.member.Member;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CouponFacade 단위 테스트")
class CouponFacadeTest {

    @Mock
    private CouponService couponService;

    @InjectMocks
    private CouponFacade couponFacade;

    private Coupon createCouponWithId(Long id, String name, CouponScope scope, Long targetId,
                                      DiscountType discountType, int discountValue,
                                      int minOrderAmount, int maxDiscountAmount,
                                      ZonedDateTime validFrom, ZonedDateTime validTo) {
        Coupon coupon = new Coupon(name, scope, targetId, discountType, discountValue,
                minOrderAmount, maxDiscountAmount, validFrom, validTo);
        ReflectionTestUtils.setField(coupon, "id", id);
        return coupon;
    }

    private MemberCoupon createMemberCouponWithId(Long id, Long memberId, Long couponId) {
        MemberCoupon memberCoupon = new MemberCoupon(memberId, couponId);
        ReflectionTestUtils.setField(memberCoupon, "id", id);
        return memberCoupon;
    }

    @Nested
    @DisplayName("쿠폰을 다운로드할 때,")
    class DownloadCoupon {

        @Test
        @DisplayName("CouponService를 호출하고 CouponInfo를 반환한다.")
        void callsServiceAndReturnsCouponInfo() {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);

            Long couponId = 100L;
            ZonedDateTime now = ZonedDateTime.now();

            MemberCoupon memberCoupon = createMemberCouponWithId(10L, 1L, couponId);

            Coupon coupon = createCouponWithId(couponId, "신규 가입 쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 5000, 10000, 0,
                    now.minusDays(1), now.plusDays(30));

            MemberCouponDetail detail = new MemberCouponDetail(memberCoupon, coupon);
            when(couponService.downloadCoupon(1L, couponId)).thenReturn(detail);

            // when
            CouponInfo result = couponFacade.downloadCoupon(mockMember, couponId);

            // then
            assertAll(
                    () -> assertThat(result.id()).isEqualTo(couponId),
                    () -> assertThat(result.name()).isEqualTo("신규 가입 쿠폰"),
                    () -> verify(couponService, times(1)).downloadCoupon(1L, couponId)
            );
        }

        @Test
        @DisplayName("이미 다운로드한 쿠폰이면 예외가 전파된다.")
        void throwsException_whenAlreadyDownloaded() {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);

            when(couponService.downloadCoupon(1L, 100L))
                    .thenThrow(new CoreException(ErrorType.CONFLICT, "이미 다운로드한 쿠폰입니다."));

            // when & then
            assertThatThrownBy(() -> couponFacade.downloadCoupon(mockMember, 100L))
                    .isInstanceOf(CoreException.class)
                    .satisfies(exception -> {
                        CoreException coreException = (CoreException) exception;
                        assertThat(coreException.getErrorType()).isEqualTo(ErrorType.CONFLICT);
                    });
        }

        @Test
        @DisplayName("쿠폰이 존재하지 않으면 예외가 전파된다.")
        void throwsException_whenCouponNotFound() {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);

            when(couponService.downloadCoupon(1L, 999L))
                    .thenThrow(new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));

            // when & then
            assertThatThrownBy(() -> couponFacade.downloadCoupon(mockMember, 999L))
                    .isInstanceOf(CoreException.class)
                    .satisfies(exception -> {
                        CoreException coreException = (CoreException) exception;
                        assertThat(coreException.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
                    });
        }

        @Test
        @DisplayName("발급 불가능한 쿠폰이면 예외가 전파된다.")
        void throwsException_whenNotIssuable() {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);

            when(couponService.downloadCoupon(1L, 100L))
                    .thenThrow(new CoreException(ErrorType.BAD_REQUEST, "쿠폰 발급이 불가합니다."));

            // when & then
            assertThatThrownBy(() -> couponFacade.downloadCoupon(mockMember, 100L))
                    .isInstanceOf(CoreException.class)
                    .satisfies(exception -> {
                        CoreException coreException = (CoreException) exception;
                        assertThat(coreException.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                    });
        }
    }

    @Nested
    @DisplayName("내 쿠폰 목록을 조회할 때,")
    class GetMyCoupons {

        @Test
        @DisplayName("CouponService를 호출하고 MyCouponInfo 목록으로 변환하여 반환한다.")
        void callsServiceAndReturnsMyCouponInfoList() {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);

            ZonedDateTime now = ZonedDateTime.now();
            Long couponId = 100L;

            MemberCoupon memberCoupon = createMemberCouponWithId(10L, 1L, couponId);

            Coupon coupon = createCouponWithId(couponId, "신규 가입 쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 5000, 10000, 0,
                    now.minusDays(1), now.plusDays(30));

            MemberCouponDetail detail = new MemberCouponDetail(memberCoupon, coupon);
            when(couponService.getMyCouponDetails(1L)).thenReturn(List.of(detail));

            // when
            List<MyCouponInfo> result = couponFacade.getMyCoupons(mockMember);

            // then
            assertAll(
                    () -> assertThat(result).hasSize(1),
                    () -> assertThat(result.get(0).memberCouponId()).isEqualTo(10L),
                    () -> assertThat(result.get(0).couponName()).isEqualTo("신규 가입 쿠폰"),
                    () -> assertThat(result.get(0).couponScope()).isEqualTo(CouponScope.CART),
                    () -> assertThat(result.get(0).discountType()).isEqualTo(DiscountType.FIXED_AMOUNT),
                    () -> assertThat(result.get(0).discountValue()).isEqualTo(5000),
                    () -> assertThat(result.get(0).status()).isEqualTo(MemberCouponStatus.AVAILABLE),
                    () -> verify(couponService, times(1)).getMyCouponDetails(1L)
            );
        }

        @Test
        @DisplayName("유효기간이 만료된 AVAILABLE 쿠폰은 EXPIRED 상태로 변환하여 반환한다.")
        void returnsExpiredStatus_whenCouponExpired() {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);

            ZonedDateTime now = ZonedDateTime.now();
            Long couponId = 200L;

            MemberCoupon memberCoupon = createMemberCouponWithId(20L, 1L, couponId);

            Coupon expiredCoupon = createCouponWithId(couponId, "만료된 쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 3000, 5000, 0,
                    now.minusDays(60), now.minusDays(1));

            MemberCouponDetail detail = new MemberCouponDetail(memberCoupon, expiredCoupon);
            when(couponService.getMyCouponDetails(1L)).thenReturn(List.of(detail));

            // when
            List<MyCouponInfo> result = couponFacade.getMyCoupons(mockMember);

            // then
            assertAll(
                    () -> assertThat(result).hasSize(1),
                    () -> assertThat(result.get(0).memberCouponId()).isEqualTo(20L),
                    () -> assertThat(result.get(0).status()).isEqualTo(MemberCouponStatus.EXPIRED)
            );
        }

        @Test
        @DisplayName("내 쿠폰이 없으면 빈 목록을 반환한다.")
        void returnsEmptyList_whenNoCoupons() {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);

            when(couponService.getMyCouponDetails(1L)).thenReturn(List.of());

            // when
            List<MyCouponInfo> result = couponFacade.getMyCoupons(mockMember);

            // then
            assertThat(result).isEmpty();
        }
    }
}
