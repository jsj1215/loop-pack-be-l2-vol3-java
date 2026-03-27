package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MemberCoupon 선착순 발급 도메인 테스트")
class MemberCouponIssueTest {

    @Nested
    @DisplayName("createRequested 정적 팩토리 메서드")
    class CreateRequested {

        @Test
        @DisplayName("REQUESTED 상태로 MemberCoupon을 생성한다")
        void createsWithRequestedStatus() {
            // given
            Long memberId = 1L;
            Long couponId = 100L;

            // when
            MemberCoupon memberCoupon = MemberCoupon.createRequested(memberId, couponId);

            // then
            assertThat(memberCoupon.getMemberId()).isEqualTo(memberId);
            assertThat(memberCoupon.getCouponId()).isEqualTo(couponId);
            assertThat(memberCoupon.getStatus()).isEqualTo(MemberCouponStatus.REQUESTED);
            assertThat(memberCoupon.getFailReason()).isNull();
        }
    }

    @Nested
    @DisplayName("approve 메서드")
    class Approve {

        @Test
        @DisplayName("REQUESTED 상태에서 AVAILABLE로 전환한다")
        void transitionsToAvailable() {
            // given
            MemberCoupon memberCoupon = MemberCoupon.createRequested(1L, 100L);

            // when
            memberCoupon.approve();

            // then
            assertThat(memberCoupon.getStatus()).isEqualTo(MemberCouponStatus.AVAILABLE);
        }

        @Test
        @DisplayName("AVAILABLE 상태에서 approve를 호출하면 예외가 발생한다")
        void throwsWhenNotRequested() {
            // given
            MemberCoupon memberCoupon = new MemberCoupon(1L, 100L); // status=AVAILABLE

            // when & then
            assertThatThrownBy(memberCoupon::approve)
                    .isInstanceOf(CoreException.class)
                    .satisfies(ex -> assertThat(((CoreException) ex).getErrorType()).isEqualTo(ErrorType.CONFLICT));
        }
    }

    @Nested
    @DisplayName("reject 메서드")
    class Reject {

        @Test
        @DisplayName("REQUESTED 상태에서 FAILED로 전환하고 사유를 기록한다")
        void transitionsToFailedWithReason() {
            // given
            MemberCoupon memberCoupon = MemberCoupon.createRequested(1L, 100L);

            // when
            memberCoupon.reject("수량 초과");

            // then
            assertThat(memberCoupon.getStatus()).isEqualTo(MemberCouponStatus.FAILED);
            assertThat(memberCoupon.getFailReason()).isEqualTo("수량 초과");
        }

        @Test
        @DisplayName("AVAILABLE 상태에서 reject를 호출하면 예외가 발생한다")
        void throwsWhenNotRequested() {
            // given
            MemberCoupon memberCoupon = new MemberCoupon(1L, 100L);

            // when & then
            assertThatThrownBy(() -> memberCoupon.reject("수량 초과"))
                    .isInstanceOf(CoreException.class)
                    .satisfies(ex -> assertThat(((CoreException) ex).getErrorType()).isEqualTo(ErrorType.CONFLICT));
        }
    }

    @Nested
    @DisplayName("retryRequest 메서드")
    class RetryRequest {

        @Test
        @DisplayName("FAILED 상태에서 REQUESTED로 전환하고 failReason을 초기화한다")
        void transitionsToRequestedAndClearsFailReason() {
            // given
            MemberCoupon memberCoupon = MemberCoupon.createRequested(1L, 100L);
            memberCoupon.reject("수량 초과");

            // when
            memberCoupon.retryRequest();

            // then
            assertThat(memberCoupon.getStatus()).isEqualTo(MemberCouponStatus.REQUESTED);
            assertThat(memberCoupon.getFailReason()).isNull();
        }

        @Test
        @DisplayName("REQUESTED 상태에서 retryRequest를 호출하면 예외가 발생한다")
        void throwsWhenRequested() {
            // given
            MemberCoupon memberCoupon = MemberCoupon.createRequested(1L, 100L);

            // when & then
            assertThatThrownBy(memberCoupon::retryRequest)
                    .isInstanceOf(CoreException.class)
                    .satisfies(ex -> assertThat(((CoreException) ex).getErrorType()).isEqualTo(ErrorType.CONFLICT));
        }

        @Test
        @DisplayName("AVAILABLE 상태에서 retryRequest를 호출하면 예외가 발생한다")
        void throwsWhenAvailable() {
            // given
            MemberCoupon memberCoupon = new MemberCoupon(1L, 100L);

            // when & then
            assertThatThrownBy(memberCoupon::retryRequest)
                    .isInstanceOf(CoreException.class)
                    .satisfies(ex -> assertThat(((CoreException) ex).getErrorType()).isEqualTo(ErrorType.CONFLICT));
        }
    }
}
