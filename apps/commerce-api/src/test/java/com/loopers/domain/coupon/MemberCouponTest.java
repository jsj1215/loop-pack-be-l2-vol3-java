package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("MemberCoupon 도메인 모델 단위 테스트")
class MemberCouponTest {

    @Nested
    @DisplayName("회원 쿠폰을 생성할 때,")
    class Create {

        @Test
        @DisplayName("생성하면 기본 상태는 AVAILABLE이다.")
        void defaultStatusIsAvailable_whenCreated() {
            // given
            Long memberId = 1L;
            Long couponId = 10L;

            // when
            MemberCoupon memberCoupon = new MemberCoupon(memberId, couponId);

            // then
            assertAll(
                    () -> assertThat(memberCoupon.getId()).isEqualTo(0L),
                    () -> assertThat(memberCoupon.getMemberId()).isEqualTo(1L),
                    () -> assertThat(memberCoupon.getCouponId()).isEqualTo(10L),
                    () -> assertThat(memberCoupon.getStatus()).isEqualTo(MemberCouponStatus.AVAILABLE),
                    () -> assertThat(memberCoupon.getOrderId()).isNull(),
                    () -> assertThat(memberCoupon.getUsedAt()).isNull());
        }
    }

    @Nested
    @DisplayName("쿠폰을 사용할 때,")
    class Use {

        @Test
        @DisplayName("AVAILABLE 상태에서 사용하면 USED로 변경된다.")
        void changesStatusToUsed_whenAvailable() {
            // given
            MemberCoupon memberCoupon = new MemberCoupon(1L, 10L);
            ReflectionTestUtils.setField(memberCoupon, "id", 1L);

            // when
            memberCoupon.use(100L);

            // then
            assertAll(
                    () -> assertThat(memberCoupon.getStatus()).isEqualTo(MemberCouponStatus.USED),
                    () -> assertThat(memberCoupon.getOrderId()).isEqualTo(100L),
                    () -> assertThat(memberCoupon.getUsedAt()).isNotNull());
        }

        @Test
        @DisplayName("이미 사용된 쿠폰이면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenAlreadyUsed() {
            // given
            MemberCoupon memberCoupon = new MemberCoupon(1L, 10L);
            ReflectionTestUtils.setField(memberCoupon, "id", 1L);
            ReflectionTestUtils.setField(memberCoupon, "status", MemberCouponStatus.USED);
            ReflectionTestUtils.setField(memberCoupon, "orderId", 50L);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberCoupon.use(100L));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("사용할 수 없는 쿠폰"));
        }
    }

    @Nested
    @DisplayName("쿠폰을 삭제할 때,")
    class MarkDeleted {

        @Test
        @DisplayName("상태가 DELETED로 변경된다.")
        void changesStatusToDeleted() {
            // given
            MemberCoupon memberCoupon = new MemberCoupon(1L, 10L);
            ReflectionTestUtils.setField(memberCoupon, "id", 1L);

            // when
            memberCoupon.markDeleted();

            // then
            assertAll(
                    () -> assertThat(memberCoupon.getStatus()).isEqualTo(MemberCouponStatus.DELETED),
                    () -> assertThat(memberCoupon.getDeletedAt()).isNotNull());
        }
    }

    @Nested
    @DisplayName("삭제된 쿠폰을 재발급할 때,")
    class Reissue {

        @Test
        @DisplayName("DELETED 상태에서 재발급하면 AVAILABLE로 복원된다.")
        void restoresToAvailable_whenDeleted() {
            // given
            MemberCoupon memberCoupon = new MemberCoupon(1L, 10L);
            ReflectionTestUtils.setField(memberCoupon, "id", 1L);
            memberCoupon.markDeleted();

            // when
            memberCoupon.reissue();

            // then
            assertAll(
                    () -> assertThat(memberCoupon.getStatus()).isEqualTo(MemberCouponStatus.AVAILABLE),
                    () -> assertThat(memberCoupon.getOrderId()).isNull(),
                    () -> assertThat(memberCoupon.getUsedAt()).isNull(),
                    () -> assertThat(memberCoupon.getDeletedAt()).isNull());
        }

        @Test
        @DisplayName("AVAILABLE 상태에서 재발급하면 CONFLICT 예외가 발생한다.")
        void throwsConflict_whenAvailable() {
            // given
            MemberCoupon memberCoupon = new MemberCoupon(1L, 10L);
            ReflectionTestUtils.setField(memberCoupon, "id", 1L);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberCoupon.reissue());

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                    () -> assertThat(exception.getMessage()).contains("이미 다운로드"));
        }

        @Test
        @DisplayName("USED 상태에서 재발급하면 CONFLICT 예외가 발생한다.")
        void throwsConflict_whenUsed() {
            // given
            MemberCoupon memberCoupon = new MemberCoupon(1L, 10L);
            ReflectionTestUtils.setField(memberCoupon, "id", 1L);
            ReflectionTestUtils.setField(memberCoupon, "status", MemberCouponStatus.USED);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberCoupon.reissue());

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                    () -> assertThat(exception.getMessage()).contains("이미 다운로드"));
        }
    }

    @Nested
    @DisplayName("쿠폰 사용 가능 여부를 확인할 때,")
    class IsAvailable {

        @Test
        @DisplayName("AVAILABLE 상태이면 true를 반환한다.")
        void returnsTrue_whenAvailable() {
            // given
            MemberCoupon memberCoupon = new MemberCoupon(1L, 10L);
            ReflectionTestUtils.setField(memberCoupon, "id", 1L);

            // when & then
            assertThat(memberCoupon.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("USED 상태이면 false를 반환한다.")
        void returnsFalse_whenUsed() {
            // given
            MemberCoupon memberCoupon = new MemberCoupon(1L, 10L);
            ReflectionTestUtils.setField(memberCoupon, "id", 1L);
            ReflectionTestUtils.setField(memberCoupon, "status", MemberCouponStatus.USED);
            ReflectionTestUtils.setField(memberCoupon, "orderId", 50L);

            // when & then
            assertThat(memberCoupon.isAvailable()).isFalse();
        }
    }
}
