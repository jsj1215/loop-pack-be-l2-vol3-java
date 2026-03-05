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
                                      ZonedDateTime validFrom, ZonedDateTime validTo) {
        Coupon coupon = new Coupon(name, scope, targetId, discountType, discountValue,
                minOrderAmount, maxDiscountAmount, validFrom, validTo);
        ReflectionTestUtils.setField(coupon, "id", id);
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
                    DiscountType.FIXED_AMOUNT, 1000, 10000, 0,
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
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0,
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
    @DisplayName("쿠폰 ID 목록으로 조회할 때,")
    class FindByIds {

        @Test
        @DisplayName("ID 목록에 해당하는 쿠폰들을 반환한다.")
        void returnsCoupons_whenIdsExist() {
            // given
            Coupon coupon1 = createCouponWithId(1L, "쿠폰1", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            Coupon coupon2 = createCouponWithId(2L, "쿠폰2", CouponScope.PRODUCT, 10L,
                    DiscountType.FIXED_RATE, 10, 5000, 3000,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));

            when(couponRepository.findByIds(List.of(1L, 2L))).thenReturn(List.of(coupon1, coupon2));

            // when
            List<Coupon> result = couponService.findByIds(List.of(1L, 2L));

            // then
            assertAll(
                    () -> assertThat(result).hasSize(2),
                    () -> assertThat(result.get(0).getName()).isEqualTo("쿠폰1"),
                    () -> assertThat(result.get(1).getName()).isEqualTo("쿠폰2"));
        }

        @Test
        @DisplayName("빈 ID 목록이면 빈 리스트를 반환한다.")
        void returnsEmptyList_whenEmptyIds() {
            // given
            when(couponRepository.findByIds(List.of())).thenReturn(List.of());

            // when
            List<Coupon> result = couponService.findByIds(List.of());

            // then
            assertThat(result).isEmpty();
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
        @DisplayName("정상적으로 다운로드하면 MemberCouponDetail을 반환한다.")
        void returnsMemberCouponDetail_whenValidDownload() {
            // given
            Coupon coupon = createCouponWithId(1L, "쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
            when(memberCouponRepository.findByMemberIdAndCouponId(1L, 1L)).thenReturn(Optional.empty());
            when(memberCouponRepository.save(any(MemberCoupon.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // when
            MemberCouponDetail result = couponService.downloadCoupon(1L, 1L);

            // then
            assertAll(
                    () -> assertThat(result.memberCoupon().getMemberId()).isEqualTo(1L),
                    () -> assertThat(result.memberCoupon().getCouponId()).isEqualTo(1L),
                    () -> assertThat(result.memberCoupon().getStatus()).isEqualTo(MemberCouponStatus.AVAILABLE),
                    () -> assertThat(result.coupon()).isEqualTo(coupon),
                    () -> verify(memberCouponRepository, times(1)).save(any(MemberCoupon.class)));
        }

        @Test
        @DisplayName("이미 다운로드한 쿠폰이면 CONFLICT 예외가 발생한다.")
        void throwsConflict_whenAlreadyDownloaded() {
            // given
            Coupon coupon = createCouponWithId(1L, "쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0,
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
        @DisplayName("유효기간이 지난 쿠폰이면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenExpired() {
            // given
            Coupon coupon = createCouponWithId(1L, "쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0,
                    ZonedDateTime.now().minusDays(30), ZonedDateTime.now().minusDays(1));

            when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));

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
    @DisplayName("전체 내 쿠폰을 조회할 때,")
    class FindAllMyCoupons {

        @Test
        @DisplayName("상태 무관하게 전체 회원 쿠폰 목록을 반환한다.")
        void returnsAllMemberCoupons() {
            // given
            MemberCoupon availableCoupon = createMemberCouponWithId(1L, 1L, 10L,
                    MemberCouponStatus.AVAILABLE, null);
            MemberCoupon usedCoupon = createMemberCouponWithId(2L, 1L, 20L,
                    MemberCouponStatus.USED, 100L);
            when(memberCouponRepository.findByMemberId(1L))
                    .thenReturn(List.of(availableCoupon, usedCoupon));

            // when
            List<MemberCoupon> result = couponService.findAllMyCoupons(1L);

            // then
            assertAll(
                    () -> assertThat(result).hasSize(2),
                    () -> assertThat(result.get(0).getStatus()).isEqualTo(MemberCouponStatus.AVAILABLE),
                    () -> assertThat(result.get(1).getStatus()).isEqualTo(MemberCouponStatus.USED));
        }
    }

    @Nested
    @DisplayName("내 쿠폰 상세 목록을 조회할 때,")
    class GetMyCouponDetails {

        @Test
        @DisplayName("MemberCoupon과 Coupon을 조합한 MemberCouponDetail 목록을 반환한다.")
        void returnsMemberCouponDetails() {
            // given
            MemberCoupon mc1 = createMemberCouponWithId(1L, 1L, 10L,
                    MemberCouponStatus.AVAILABLE, null);
            MemberCoupon mc2 = createMemberCouponWithId(2L, 1L, 20L,
                    MemberCouponStatus.USED, 100L);

            Coupon coupon1 = createCouponWithId(10L, "쿠폰A", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            Coupon coupon2 = createCouponWithId(20L, "쿠폰B", CouponScope.PRODUCT, 5L,
                    DiscountType.FIXED_RATE, 10, 5000, 3000,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));

            when(memberCouponRepository.findByMemberId(1L)).thenReturn(List.of(mc1, mc2));
            when(couponRepository.findByIds(List.of(10L, 20L))).thenReturn(List.of(coupon1, coupon2));

            // when
            List<MemberCouponDetail> result = couponService.getMyCouponDetails(1L);

            // then
            assertAll(
                    () -> assertThat(result).hasSize(2),
                    () -> assertThat(result.get(0).memberCoupon()).isEqualTo(mc1),
                    () -> assertThat(result.get(0).coupon()).isEqualTo(coupon1),
                    () -> assertThat(result.get(1).memberCoupon()).isEqualTo(mc2),
                    () -> assertThat(result.get(1).coupon()).isEqualTo(coupon2));
        }

        @Test
        @DisplayName("내 쿠폰이 없으면 빈 목록을 반환한다.")
        void returnsEmptyList_whenNoCoupons() {
            // given
            when(memberCouponRepository.findByMemberId(999L)).thenReturn(List.of());
            when(couponRepository.findByIds(List.of())).thenReturn(List.of());

            // when
            List<MemberCouponDetail> result = couponService.getMyCouponDetails(999L);

            // then
            assertThat(result).isEmpty();
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
                    DiscountType.FIXED_AMOUNT, 5000, 0, 0,
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
                    DiscountType.FIXED_AMOUNT, 5000, 0, 0,
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
                    DiscountType.FIXED_AMOUNT, 5000, 50000, 0,
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
            MemberCoupon result = couponService.useCoupon(1L, 1L, 100L);

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
                    () -> couponService.useCoupon(1L, 999L, 100L));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @Test
        @DisplayName("다른 회원의 쿠폰을 사용하면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenNotOwner() {
            // given
            MemberCoupon memberCoupon = createMemberCouponWithId(1L, 1L, 10L,
                    MemberCouponStatus.AVAILABLE, null);
            when(memberCouponRepository.findById(1L)).thenReturn(Optional.of(memberCoupon));

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> couponService.useCoupon(999L, 1L, 100L));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("쿠폰을 수정할 때,")
    class UpdateCoupon {

        @Test
        @DisplayName("존재하는 쿠폰을 수정하면 수정된 쿠폰을 반환한다.")
        void returnsUpdatedCoupon_whenExists() {
            // given
            Coupon coupon = createCouponWithId(1L, "기존 쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            when(couponRepository.findByIdWithLock(1L)).thenReturn(Optional.of(coupon));
            when(couponRepository.save(any(Coupon.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ZonedDateTime newValidFrom = ZonedDateTime.now().minusDays(1);
            ZonedDateTime newValidTo = ZonedDateTime.now().plusDays(60);

            // when
            Coupon result = couponService.updateCoupon(1L, "수정된 쿠폰", CouponScope.PRODUCT, 100L,
                    DiscountType.FIXED_RATE, 10, 5000, 3000, newValidFrom, newValidTo);

            // then
            assertAll(
                    () -> assertThat(result.getName()).isEqualTo("수정된 쿠폰"),
                    () -> assertThat(result.getCouponScope()).isEqualTo(CouponScope.PRODUCT),
                    () -> assertThat(result.getTargetId()).isEqualTo(100L),
                    () -> assertThat(result.getDiscountType()).isEqualTo(DiscountType.FIXED_RATE),
                    () -> assertThat(result.getDiscountValue()).isEqualTo(10),
                    () -> verify(couponRepository, times(1)).save(any(Coupon.class)));
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰을 수정하면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenNotExists() {
            // given
            when(couponRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> couponService.updateCoupon(999L, "쿠폰", CouponScope.CART, null,
                            DiscountType.FIXED_AMOUNT, 1000, 0, 0,
                            ZonedDateTime.now(), ZonedDateTime.now().plusDays(30)));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("쿠폰을 삭제할 때,")
    class SoftDeleteCoupon {

        @Test
        @DisplayName("존재하는 쿠폰을 삭제하면 delete 후 save를 수행한다.")
        void callsDeleteAndSave_whenExists() {
            // given
            Coupon coupon = createCouponWithId(1L, "쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            when(couponRepository.findByIdWithLock(1L)).thenReturn(Optional.of(coupon));
            when(couponRepository.save(any(Coupon.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            couponService.softDelete(1L);

            // then
            assertAll(
                    () -> assertThat(coupon.getDeletedAt()).isNotNull(),
                    () -> verify(couponRepository, times(1)).save(coupon));
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰을 삭제하면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenNotExists() {
            // given
            when(couponRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> couponService.softDelete(999L));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("쿠폰 발급 내역을 조회할 때,")
    class FindCouponIssues {

        @Test
        @DisplayName("쿠폰 ID로 발급 내역을 페이징 조회한다.")
        void returnsMemberCouponPage() {
            // given
            Pageable pageable = PageRequest.of(0, 20);
            MemberCoupon memberCoupon = createMemberCouponWithId(1L, 1L, 10L,
                    MemberCouponStatus.AVAILABLE, null);
            when(memberCouponRepository.findByCouponId(10L, pageable))
                    .thenReturn(new PageImpl<>(List.of(memberCoupon), pageable, 1));

            // when
            Page<MemberCoupon> result = couponService.findCouponIssues(10L, pageable);

            // then
            assertAll(
                    () -> assertThat(result.getContent()).hasSize(1),
                    () -> assertThat(result.getContent().get(0).getCouponId()).isEqualTo(10L));
        }
    }
}
