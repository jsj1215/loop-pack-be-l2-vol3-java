package com.loopers.domain.coupon;

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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CouponService 단위 테스트")
class CouponServiceTest {

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private MemberCouponRepository memberCouponRepository;

    @InjectMocks
    private CouponService couponService;

    private Coupon createCouponWithId(Long id, String name, CouponScope scope, Long targetId,
                                      DiscountType discountType, int discountValue,
                                      int minOrderAmount, int maxDiscountAmount,
                                      int totalQuantity, int issuedQuantity,
                                      ZonedDateTime validFrom, ZonedDateTime validTo) {
        Coupon coupon = new Coupon(name, scope, targetId, discountType, discountValue,
                minOrderAmount, maxDiscountAmount, totalQuantity, validFrom, validTo);
        ReflectionTestUtils.setField(coupon, "id", id);
        ReflectionTestUtils.setField(coupon, "issuedQuantity", issuedQuantity);
        return coupon;
    }

    private MemberCoupon createMemberCouponWithId(Long id, Long memberId, Long couponId,
                                                   MemberCouponStatus status, Long orderId) {
        MemberCoupon memberCoupon = new MemberCoupon(memberId, couponId);
        ReflectionTestUtils.setField(memberCoupon, "id", id);
        if (status != MemberCouponStatus.AVAILABLE) {
            ReflectionTestUtils.setField(memberCoupon, "status", status);
        }
        if (orderId != null) {
            ReflectionTestUtils.setField(memberCoupon, "orderId", orderId);
        }
        return memberCoupon;
    }

    @Nested
    @DisplayName("쿠폰을 생성할 때,")
    class CreateCoupon {

        @Test
        @DisplayName("유효한 쿠폰 정보로 생성하면 저장된 쿠폰을 반환한다.")
        void returnsSavedCoupon_whenValidInfo() {
            // given
            Coupon coupon = new Coupon("1000원 할인", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 10000, 0, 100,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            when(couponRepository.save(any(Coupon.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            Coupon result = couponService.createCoupon(coupon);

            // then
            assertAll(
                    () -> assertThat(result.getName()).isEqualTo("1000원 할인"),
                    () -> verify(couponRepository, times(1)).save(any(Coupon.class)));
        }
    }

    @Nested
    @DisplayName("쿠폰을 조회할 때,")
    class FindById {

        @Test
        @DisplayName("존재하는 쿠폰 ID로 조회하면 쿠폰을 반환한다.")
        void returnsCoupon_whenExists() {
            // given
            Coupon coupon = createCouponWithId(1L, "쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0, 100, 0,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));

            // when
            Coupon result = couponService.findById(1L);

            // then
            assertThat(result.getName()).isEqualTo("쿠폰");
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰 ID로 조회하면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenNotExists() {
            // given
            when(couponRepository.findById(999L)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> couponService.findById(999L));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("유효한 쿠폰 목록을 조회할 때,")
    class FindAvailableCoupons {

        @Test
        @DisplayName("유효한 쿠폰 목록을 반환한다.")
        void returnsValidCoupons() {
            // given
            Coupon coupon = createCouponWithId(1L, "쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0, 100, 50,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            when(couponRepository.findAllValid()).thenReturn(List.of(coupon));

            // when
            List<Coupon> result = couponService.findAvailableCoupons();

            // then
            assertAll(
                    () -> assertThat(result).hasSize(1),
                    () -> assertThat(result.get(0).getName()).isEqualTo("쿠폰"));
        }
    }

    @Nested
    @DisplayName("전체 쿠폰 목록을 조회할 때,")
    class FindAllCoupons {

        @Test
        @DisplayName("빈 페이지도 정상 반환한다.")
        void returnsEmptyPage_whenNoData() {
            // given
            Pageable pageable = PageRequest.of(0, 20);
            when(couponRepository.findAll(pageable))
                    .thenReturn(new PageImpl<>(Collections.emptyList(), pageable, 0));

            // when
            Page<Coupon> result = couponService.findAllCoupons(pageable);

            // then
            assertThat(result.getContent()).isEmpty();
        }
    }

    @Nested
    @DisplayName("쿠폰을 다운로드할 때,")
    class DownloadCoupon {

        @Test
        @DisplayName("정상적으로 다운로드하면 회원 쿠폰을 반환한다.")
        void returnsMemberCoupon_whenValidDownload() {
            // given
            Coupon coupon = createCouponWithId(1L, "쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0, 100, 50,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
            when(memberCouponRepository.findByMemberIdAndCouponId(1L, 1L)).thenReturn(Optional.empty());
            when(couponRepository.save(any(Coupon.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(memberCouponRepository.save(any(MemberCoupon.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // when
            MemberCoupon result = couponService.downloadCoupon(1L, 1L);

            // then
            assertAll(
                    () -> assertThat(result.getMemberId()).isEqualTo(1L),
                    () -> assertThat(result.getCouponId()).isEqualTo(1L),
                    () -> assertThat(result.getStatus()).isEqualTo(MemberCouponStatus.AVAILABLE),
                    () -> verify(couponRepository, times(1)).save(any(Coupon.class)),
                    () -> verify(memberCouponRepository, times(1)).save(any(MemberCoupon.class)));
        }

        @Test
        @DisplayName("이미 다운로드한 쿠폰이면 CONFLICT 예외가 발생한다.")
        void throwsConflict_whenAlreadyDownloaded() {
            // given
            Coupon coupon = createCouponWithId(1L, "쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0, 100, 50,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            MemberCoupon existingCoupon = createMemberCouponWithId(1L, 1L, 1L,
                    MemberCouponStatus.AVAILABLE, null);

            when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
            when(memberCouponRepository.findByMemberIdAndCouponId(1L, 1L))
                    .thenReturn(Optional.of(existingCoupon));

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> couponService.downloadCoupon(1L, 1L));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                    () -> assertThat(exception.getMessage()).contains("이미 다운로드"),
                    () -> verify(memberCouponRepository, never()).save(any(MemberCoupon.class)));
        }

        @Test
        @DisplayName("발급 불가능한 쿠폰이면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenNotIssuable() {
            // given
            Coupon coupon = createCouponWithId(1L, "쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0, 100, 100,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));

            when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
            when(memberCouponRepository.findByMemberIdAndCouponId(1L, 1L)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> couponService.downloadCoupon(1L, 1L));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("쿠폰 발급이 불가"),
                    () -> verify(memberCouponRepository, never()).save(any(MemberCoupon.class)));
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰이면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenCouponNotExists() {
            // given
            when(couponRepository.findById(999L)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> couponService.downloadCoupon(1L, 999L));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND),
                    () -> verify(memberCouponRepository, never()).save(any(MemberCoupon.class)));
        }
    }

    @Nested
    @DisplayName("내 쿠폰을 조회할 때,")
    class FindMyCoupons {

        @Test
        @DisplayName("사용 가능한 회원 쿠폰 목록을 반환한다.")
        void returnsAvailableMemberCoupons() {
            // given
            MemberCoupon memberCoupon = createMemberCouponWithId(1L, 1L, 10L,
                    MemberCouponStatus.AVAILABLE, null);
            when(memberCouponRepository.findByMemberIdAndStatus(1L, MemberCouponStatus.AVAILABLE))
                    .thenReturn(List.of(memberCoupon));

            // when
            List<MemberCoupon> result = couponService.findMyCoupons(1L);

            // then
            assertAll(
                    () -> assertThat(result).hasSize(1),
                    () -> assertThat(result.get(0).getCouponId()).isEqualTo(10L));
        }
    }

    @Nested
    @DisplayName("쿠폰 할인 금액을 계산할 때,")
    class CalculateCouponDiscount {

        @Test
        @DisplayName("유효한 쿠폰으로 정액 할인 금액을 계산한다.")
        void returnsFixedAmountDiscount_whenValidCoupon() {
            // given
            Long memberId = 1L;
            Long memberCouponId = 1L;
            MemberCoupon memberCoupon = createMemberCouponWithId(memberCouponId, memberId, 10L,
                    MemberCouponStatus.AVAILABLE, null);
            Coupon coupon = createCouponWithId(10L, "5000원 할인", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 5000, 0, 0, 100, 10,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));

            when(memberCouponRepository.findById(memberCouponId)).thenReturn(Optional.of(memberCoupon));
            when(couponRepository.findById(10L)).thenReturn(Optional.of(coupon));

            // when
            int discount = couponService.calculateCouponDiscount(memberId, memberCouponId, 100000);

            // then
            assertThat(discount).isEqualTo(5000);
        }

        @Test
        @DisplayName("본인의 쿠폰이 아니면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenNotOwner() {
            // given
            Long memberCouponId = 1L;
            MemberCoupon memberCoupon = createMemberCouponWithId(memberCouponId, 2L, 10L,
                    MemberCouponStatus.AVAILABLE, null);

            when(memberCouponRepository.findById(memberCouponId)).thenReturn(Optional.of(memberCoupon));

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> couponService.calculateCouponDiscount(1L, memberCouponId, 100000));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @Test
        @DisplayName("이미 사용된 쿠폰이면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenAlreadyUsed() {
            // given
            Long memberId = 1L;
            Long memberCouponId = 1L;
            MemberCoupon memberCoupon = createMemberCouponWithId(memberCouponId, memberId, 10L,
                    MemberCouponStatus.USED, 100L);

            when(memberCouponRepository.findById(memberCouponId)).thenReturn(Optional.of(memberCoupon));

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> couponService.calculateCouponDiscount(memberId, memberCouponId, 100000));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("유효기간이 만료된 쿠폰이면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenExpired() {
            // given
            Long memberId = 1L;
            Long memberCouponId = 1L;
            MemberCoupon memberCoupon = createMemberCouponWithId(memberCouponId, memberId, 10L,
                    MemberCouponStatus.AVAILABLE, null);
            Coupon coupon = createCouponWithId(10L, "만료 쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 5000, 0, 0, 100, 10,
                    ZonedDateTime.now().minusDays(30), ZonedDateTime.now().minusDays(1));

            when(memberCouponRepository.findById(memberCouponId)).thenReturn(Optional.of(memberCoupon));
            when(couponRepository.findById(10L)).thenReturn(Optional.of(coupon));

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> couponService.calculateCouponDiscount(memberId, memberCouponId, 100000));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("최소 주문 금액 미달이면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenBelowMinOrderAmount() {
            // given
            Long memberId = 1L;
            Long memberCouponId = 1L;
            MemberCoupon memberCoupon = createMemberCouponWithId(memberCouponId, memberId, 10L,
                    MemberCouponStatus.AVAILABLE, null);
            Coupon coupon = createCouponWithId(10L, "쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 5000, 50000, 0, 100, 10,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));

            when(memberCouponRepository.findById(memberCouponId)).thenReturn(Optional.of(memberCoupon));
            when(couponRepository.findById(10L)).thenReturn(Optional.of(coupon));

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> couponService.calculateCouponDiscount(memberId, memberCouponId, 30000));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("쿠폰을 사용할 때,")
    class UseCoupon {

        @Test
        @DisplayName("사용 가능한 쿠폰이면 USED로 변경된다.")
        void changesStatusToUsed_whenAvailable() {
            // given
            MemberCoupon memberCoupon = createMemberCouponWithId(1L, 1L, 10L,
                    MemberCouponStatus.AVAILABLE, null);
            when(memberCouponRepository.findById(1L)).thenReturn(Optional.of(memberCoupon));
            when(memberCouponRepository.save(any(MemberCoupon.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // when
            MemberCoupon result = couponService.useCoupon(1L, 100L);

            // then
            assertAll(
                    () -> assertThat(result.getStatus()).isEqualTo(MemberCouponStatus.USED),
                    () -> assertThat(result.getOrderId()).isEqualTo(100L),
                    () -> verify(memberCouponRepository, times(1)).save(any(MemberCoupon.class)));
        }

        @Test
        @DisplayName("존재하지 않는 회원 쿠폰이면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenNotExists() {
            // given
            when(memberCouponRepository.findById(999L)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> couponService.useCoupon(999L, 100L));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
