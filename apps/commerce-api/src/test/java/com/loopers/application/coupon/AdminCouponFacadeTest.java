package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.CouponService;
import com.loopers.domain.coupon.DiscountType;
import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.coupon.MemberCouponStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCouponFacade 단위 테스트")
class AdminCouponFacadeTest {

    @Mock
    private CouponService couponService;

    @InjectMocks
    private AdminCouponFacade adminCouponFacade;

    private Coupon createCouponWithId(Long id, String name, CouponScope scope, Long targetId,
                                      DiscountType discountType, int discountValue,
                                      int minOrderAmount, int maxDiscountAmount,
                                      ZonedDateTime validFrom, ZonedDateTime validTo) {
        Coupon coupon = new Coupon(name, scope, targetId, discountType, discountValue,
                minOrderAmount, maxDiscountAmount, validFrom, validTo);
        ReflectionTestUtils.setField(coupon, "id", id);
        return coupon;
    }

    @Nested
    @DisplayName("쿠폰을 생성할 때,")
    class CreateCoupon {

        @Test
        @DisplayName("CouponService를 호출하고 CouponInfo를 반환한다.")
        void callsServiceAndReturnsCouponInfo() {
            // given
            ZonedDateTime now = ZonedDateTime.now();
            Coupon inputCoupon = new Coupon("신규 가입 쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 5000, 10000, 0,
                    now.minusDays(1), now.plusDays(30));

            Coupon savedCoupon = createCouponWithId(1L, "신규 가입 쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 5000, 10000, 0,
                    now.minusDays(1), now.plusDays(30));

            when(couponService.createCoupon(any(Coupon.class))).thenReturn(savedCoupon);

            // when
            CouponInfo result = adminCouponFacade.createCoupon(inputCoupon);

            // then
            assertAll(
                    () -> assertThat(result.id()).isEqualTo(1L),
                    () -> assertThat(result.name()).isEqualTo("신규 가입 쿠폰"),
                    () -> assertThat(result.couponScope()).isEqualTo(CouponScope.CART),
                    () -> assertThat(result.discountType()).isEqualTo(DiscountType.FIXED_AMOUNT),
                    () -> assertThat(result.discountValue()).isEqualTo(5000),
                    () -> verify(couponService, times(1)).createCoupon(any(Coupon.class))
            );
        }
    }

    @Nested
    @DisplayName("쿠폰 목록을 조회할 때,")
    class GetCoupons {

        @Test
        @DisplayName("CouponService를 호출하고 CouponInfo 페이지를 반환한다.")
        void callsServiceAndReturnsCouponInfoPage() {
            // given
            ZonedDateTime now = ZonedDateTime.now();
            Coupon coupon = createCouponWithId(1L, "신규 가입 쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 5000, 10000, 0,
                    now.minusDays(1), now.plusDays(30));

            Pageable pageable = PageRequest.of(0, 20);
            when(couponService.findAllCoupons(pageable))
                    .thenReturn(new PageImpl<>(List.of(coupon), pageable, 1));

            // when
            Page<CouponInfo> result = adminCouponFacade.getCoupons(pageable);

            // then
            assertAll(
                    () -> assertThat(result.getContent()).hasSize(1),
                    () -> assertThat(result.getContent().get(0).id()).isEqualTo(1L),
                    () -> assertThat(result.getContent().get(0).name()).isEqualTo("신규 가입 쿠폰"),
                    () -> verify(couponService, times(1)).findAllCoupons(pageable)
            );
        }
    }

    @Nested
    @DisplayName("쿠폰 상세를 조회할 때,")
    class GetCoupon {

        @Test
        @DisplayName("CouponService를 호출하고 CouponDetailInfo를 반환한다.")
        void callsServiceAndReturnsCouponDetailInfo() {
            // given
            ZonedDateTime now = ZonedDateTime.now();
            Coupon coupon = createCouponWithId(1L, "신규 가입 쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 5000, 10000, 0,
                    now.minusDays(1), now.plusDays(30));

            when(couponService.findById(1L)).thenReturn(coupon);

            // when
            CouponDetailInfo result = adminCouponFacade.getCoupon(1L);

            // then
            assertAll(
                    () -> assertThat(result.id()).isEqualTo(1L),
                    () -> assertThat(result.name()).isEqualTo("신규 가입 쿠폰"),
                    () -> assertThat(result.couponScope()).isEqualTo(CouponScope.CART),
                    () -> verify(couponService, times(1)).findById(1L)
            );
        }

        @Test
        @DisplayName("쿠폰이 존재하지 않으면 예외가 전파된다.")
        void throwsException_whenCouponNotFound() {
            // given
            when(couponService.findById(999L))
                    .thenThrow(new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));

            // when & then
            assertThatThrownBy(() -> adminCouponFacade.getCoupon(999L))
                    .isInstanceOf(CoreException.class)
                    .satisfies(exception -> {
                        CoreException coreException = (CoreException) exception;
                        assertThat(coreException.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
                    });
        }
    }

    @Nested
    @DisplayName("쿠폰을 수정할 때,")
    class UpdateCoupon {

        @Test
        @DisplayName("CouponService를 호출하고 CouponInfo를 반환한다.")
        void callsServiceAndReturnsCouponInfo() {
            // given
            ZonedDateTime now = ZonedDateTime.now();
            Coupon updatedCoupon = createCouponWithId(1L, "수정된 쿠폰", CouponScope.PRODUCT, 100L,
                    DiscountType.FIXED_RATE, 10, 5000, 3000,
                    now.minusDays(1), now.plusDays(60));

            when(couponService.updateCoupon(any(), any(), any(), any(), any(), any(int.class),
                    any(int.class), any(int.class), any(), any()))
                    .thenReturn(updatedCoupon);

            // when
            CouponInfo result = adminCouponFacade.updateCoupon(1L, "수정된 쿠폰", CouponScope.PRODUCT, 100L,
                    DiscountType.FIXED_RATE, 10, 5000, 3000,
                    now.minusDays(1), now.plusDays(60));

            // then
            assertAll(
                    () -> assertThat(result.id()).isEqualTo(1L),
                    () -> assertThat(result.name()).isEqualTo("수정된 쿠폰"),
                    () -> assertThat(result.couponScope()).isEqualTo(CouponScope.PRODUCT),
                    () -> assertThat(result.discountType()).isEqualTo(DiscountType.FIXED_RATE)
            );
        }

        @Test
        @DisplayName("쿠폰이 존재하지 않으면 예외가 전파된다.")
        void throwsException_whenCouponNotFound() {
            // given
            ZonedDateTime now = ZonedDateTime.now();
            when(couponService.updateCoupon(any(), any(), any(), any(), any(), any(int.class),
                    any(int.class), any(int.class), any(), any()))
                    .thenThrow(new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));

            // when & then
            assertThatThrownBy(() -> adminCouponFacade.updateCoupon(999L, "쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0,
                    now.minusDays(1), now.plusDays(30)))
                    .isInstanceOf(CoreException.class)
                    .satisfies(exception -> {
                        CoreException coreException = (CoreException) exception;
                        assertThat(coreException.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
                    });
        }
    }

    @Nested
    @DisplayName("쿠폰을 삭제할 때,")
    class DeleteCoupon {

        @Test
        @DisplayName("CouponService의 softDelete를 호출한다.")
        void callsServiceSoftDelete() {
            // given
            // when
            adminCouponFacade.deleteCoupon(1L);

            // then
            verify(couponService, times(1)).softDelete(1L);
        }

        @Test
        @DisplayName("쿠폰이 존재하지 않으면 예외가 전파된다.")
        void throwsException_whenCouponNotFound() {
            // given
            doThrow(new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."))
                    .when(couponService).softDelete(999L);

            // when & then
            assertThatThrownBy(() -> adminCouponFacade.deleteCoupon(999L))
                    .isInstanceOf(CoreException.class)
                    .satisfies(exception -> {
                        CoreException coreException = (CoreException) exception;
                        assertThat(coreException.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
                    });
        }
    }

    @Nested
    @DisplayName("쿠폰 발급 내역을 조회할 때,")
    class GetCouponIssues {

        @Test
        @DisplayName("CouponService를 호출하고 CouponIssueInfo 페이지를 반환한다.")
        void callsServiceAndReturnsCouponIssueInfoPage() {
            // given
            ZonedDateTime now = ZonedDateTime.now();
            Coupon coupon = createCouponWithId(10L, "테스트 쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0,
                    now.minusDays(1), now.plusDays(30));

            MemberCoupon memberCoupon = new MemberCoupon(1L, 10L);
            ReflectionTestUtils.setField(memberCoupon, "id", 1L);
            ReflectionTestUtils.setField(memberCoupon, "createdAt", now);

            Pageable pageable = PageRequest.of(0, 20);
            when(couponService.findById(10L)).thenReturn(coupon);
            when(couponService.findCouponIssues(10L, pageable))
                    .thenReturn(new PageImpl<>(List.of(memberCoupon), pageable, 1));

            // when
            Page<CouponIssueInfo> result = adminCouponFacade.getCouponIssues(10L, pageable);

            // then
            assertAll(
                    () -> assertThat(result.getContent()).hasSize(1),
                    () -> assertThat(result.getContent().get(0).memberCouponId()).isEqualTo(1L),
                    () -> assertThat(result.getContent().get(0).memberId()).isEqualTo(1L),
                    () -> assertThat(result.getContent().get(0).couponName()).isEqualTo("테스트 쿠폰"),
                    () -> assertThat(result.getContent().get(0).status()).isEqualTo(MemberCouponStatus.AVAILABLE),
                    () -> verify(couponService, times(1)).findCouponIssues(10L, pageable)
            );
        }

        @Test
        @DisplayName("쿠폰이 존재하지 않으면 예외가 전파된다.")
        void throwsException_whenCouponNotFound() {
            // given
            Pageable pageable = PageRequest.of(0, 20);
            when(couponService.findById(999L))
                    .thenThrow(new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));

            // when & then
            assertThatThrownBy(() -> adminCouponFacade.getCouponIssues(999L, pageable))
                    .isInstanceOf(CoreException.class)
                    .satisfies(exception -> {
                        CoreException coreException = (CoreException) exception;
                        assertThat(coreException.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
                    });
        }
    }
}
