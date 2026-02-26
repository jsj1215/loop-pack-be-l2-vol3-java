package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Coupon 도메인 모델 단위 테스트")
class CouponTest {

    @Nested
    @DisplayName("쿠폰을 생성할 때,")
    class Create {

        @Test
        @DisplayName("생성자로 생성하면 issuedQuantity가 0이다.")
        void issuedQuantityIsZero_whenCreated() {
            // given
            ZonedDateTime validFrom = ZonedDateTime.now().minusDays(1);
            ZonedDateTime validTo = ZonedDateTime.now().plusDays(30);

            // when
            Coupon coupon = new Coupon("10% 할인 쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_RATE, 10, 10000, 5000, 100,
                    validFrom, validTo);

            // then
            assertAll(
                    () -> assertThat(coupon.getId()).isEqualTo(0L),
                    () -> assertThat(coupon.getName()).isEqualTo("10% 할인 쿠폰"),
                    () -> assertThat(coupon.getCouponScope()).isEqualTo(CouponScope.CART),
                    () -> assertThat(coupon.getTargetId()).isNull(),
                    () -> assertThat(coupon.getDiscountType()).isEqualTo(DiscountType.FIXED_RATE),
                    () -> assertThat(coupon.getDiscountValue()).isEqualTo(10),
                    () -> assertThat(coupon.getIssuedQuantity()).isEqualTo(0),
                    () -> assertThat(coupon.getTotalQuantity()).isEqualTo(100));
        }
    }

    @Nested
    @DisplayName("쿠폰 발급 가능 여부를 확인할 때,")
    class IsIssuable {

        @Test
        @DisplayName("수량이 남아있고 유효기간 내이면 true를 반환한다.")
        void returnsTrue_whenQuantityRemainsAndWithinValidity() {
            // given
            Coupon coupon = new Coupon("쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0, 100,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            ReflectionTestUtils.setField(coupon, "id", 1L);
            ReflectionTestUtils.setField(coupon, "issuedQuantity", 50);

            // when & then
            assertThat(coupon.isIssuable()).isTrue();
        }

        @Test
        @DisplayName("수량이 모두 소진되면 false를 반환한다.")
        void returnsFalse_whenQuantityExhausted() {
            // given
            Coupon coupon = new Coupon("쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0, 100,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            ReflectionTestUtils.setField(coupon, "id", 1L);
            ReflectionTestUtils.setField(coupon, "issuedQuantity", 100);

            // when & then
            assertThat(coupon.isIssuable()).isFalse();
        }

        @Test
        @DisplayName("유효기간이 지나면 false를 반환한다.")
        void returnsFalse_whenExpired() {
            // given
            Coupon coupon = new Coupon("쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0, 100,
                    ZonedDateTime.now().minusDays(30), ZonedDateTime.now().minusDays(1));
            ReflectionTestUtils.setField(coupon, "id", 1L);
            ReflectionTestUtils.setField(coupon, "issuedQuantity", 50);

            // when & then
            assertThat(coupon.isIssuable()).isFalse();
        }

        @Test
        @DisplayName("유효기간 시작 전이면 false를 반환한다.")
        void returnsFalse_whenBeforeValidFrom() {
            // given
            Coupon coupon = new Coupon("쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0, 100,
                    ZonedDateTime.now().plusDays(1), ZonedDateTime.now().plusDays(30));
            ReflectionTestUtils.setField(coupon, "id", 1L);
            ReflectionTestUtils.setField(coupon, "issuedQuantity", 50);

            // when & then
            assertThat(coupon.isIssuable()).isFalse();
        }
    }

    @Nested
    @DisplayName("쿠폰 유효기간을 확인할 때,")
    class IsValid {

        @Test
        @DisplayName("현재 시간이 유효기간 내이면 true를 반환한다.")
        void returnsTrue_whenWithinValidity() {
            // given
            Coupon coupon = new Coupon("쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0, 100,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            ReflectionTestUtils.setField(coupon, "id", 1L);

            // when & then
            assertThat(coupon.isValid()).isTrue();
        }

        @Test
        @DisplayName("유효기간이 지나면 false를 반환한다.")
        void returnsFalse_whenExpired() {
            // given
            Coupon coupon = new Coupon("쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0, 100,
                    ZonedDateTime.now().minusDays(30), ZonedDateTime.now().minusDays(1));
            ReflectionTestUtils.setField(coupon, "id", 1L);
            ReflectionTestUtils.setField(coupon, "issuedQuantity", 50);

            // when & then
            assertThat(coupon.isValid()).isFalse();
        }
    }

    @Nested
    @DisplayName("쿠폰을 발급할 때,")
    class Issue {

        @Test
        @DisplayName("발급 가능하면 issuedQuantity가 1 증가한다.")
        void incrementsIssuedQuantity_whenIssuable() {
            // given
            Coupon coupon = new Coupon("쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0, 100,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            ReflectionTestUtils.setField(coupon, "id", 1L);
            ReflectionTestUtils.setField(coupon, "issuedQuantity", 50);

            // when
            coupon.issue();

            // then
            assertThat(coupon.getIssuedQuantity()).isEqualTo(51);
        }

        @Test
        @DisplayName("수량이 소진되면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenQuantityExhausted() {
            // given
            Coupon coupon = new Coupon("쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0, 100,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            ReflectionTestUtils.setField(coupon, "id", 1L);
            ReflectionTestUtils.setField(coupon, "issuedQuantity", 100);

            // when
            CoreException exception = assertThrows(CoreException.class, coupon::issue);

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("유효기간이 지나면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenExpired() {
            // given
            Coupon coupon = new Coupon("쿠폰", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0, 100,
                    ZonedDateTime.now().minusDays(30), ZonedDateTime.now().minusDays(1));
            ReflectionTestUtils.setField(coupon, "id", 1L);
            ReflectionTestUtils.setField(coupon, "issuedQuantity", 50);

            // when
            CoreException exception = assertThrows(CoreException.class, coupon::issue);

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("할인 금액을 계산할 때,")
    class CalculateDiscount {

        @Test
        @DisplayName("정액 할인이면 할인값을 그대로 반환한다.")
        void returnsDiscountValue_whenFixedAmount() {
            // given
            Coupon coupon = new Coupon("1000원 할인", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 1000, 0, 0, 100,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            ReflectionTestUtils.setField(coupon, "id", 1L);

            // when
            int discount = coupon.calculateDiscount(50000);

            // then
            assertThat(discount).isEqualTo(1000);
        }

        @Test
        @DisplayName("정률 할인이면 비율에 따른 금액을 반환한다.")
        void returnsCalculatedAmount_whenFixedRate() {
            // given
            Coupon coupon = new Coupon("10% 할인", CouponScope.CART, null,
                    DiscountType.FIXED_RATE, 10, 0, 0, 100,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            ReflectionTestUtils.setField(coupon, "id", 1L);

            // when
            int discount = coupon.calculateDiscount(50000);

            // then
            assertThat(discount).isEqualTo(5000);
        }

        @Test
        @DisplayName("정률 할인에서 maxDiscountAmount가 설정되면 상한을 적용한다.")
        void capsAtMaxDiscountAmount_whenFixedRateWithCap() {
            // given
            Coupon coupon = new Coupon("10% 할인", CouponScope.CART, null,
                    DiscountType.FIXED_RATE, 10, 0, 3000, 100,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            ReflectionTestUtils.setField(coupon, "id", 1L);

            // when
            int discount = coupon.calculateDiscount(50000);

            // then
            assertThat(discount).isEqualTo(3000);
        }

        @Test
        @DisplayName("할인 금액이 적용 대상 금액을 초과하지 않는다.")
        void doesNotExceedApplicableAmount() {
            // given
            Coupon coupon = new Coupon("5000원 할인", CouponScope.CART, null,
                    DiscountType.FIXED_AMOUNT, 5000, 0, 0, 100,
                    ZonedDateTime.now().minusDays(1), ZonedDateTime.now().plusDays(30));
            ReflectionTestUtils.setField(coupon, "id", 1L);

            // when
            int discount = coupon.calculateDiscount(3000);

            // then
            assertThat(discount).isEqualTo(3000);
        }
    }
}
