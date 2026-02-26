package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/*
  [단위 테스트]
  대상 : Point
  사용 라이브러리 : JUnit 5, AssertJ

  특징:
  - Spring Context 불필요 -> 빠른 실행
  - Docker/DB 불필요
 */
@DisplayName("Point 도메인 모델")
class PointTest {

    @Nested
    @DisplayName("생성할 때,")
    class Create {

        @Test
        @DisplayName("정적 팩토리 메서드로 생성하면 모든 필드가 올바르게 설정된다.")
        void createsPoint_whenValidParameters() {
            // given
            Long memberId = 1L;
            int initialBalance = 0;

            // when
            Point point = Point.create(memberId, initialBalance);

            // then
            assertAll(
                    () -> assertThat(point.getId()).isEqualTo(0L),
                    () -> assertThat(point.getMemberId()).isEqualTo(1L),
                    () -> assertThat(point.getBalance()).isEqualTo(0),
                    () -> assertThat(point.getCreatedAt()).isNull(),
                    () -> assertThat(point.getUpdatedAt()).isNull());
        }

        @Test
        @DisplayName("초기 잔액이 음수이면 BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNegativeInitialBalance() {
            // given
            Long memberId = 1L;
            int initialBalance = -100;

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> Point.create(memberId, initialBalance));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("포인트를 충전할 때,")
    class Charge {

        @Test
        @DisplayName("잔액이 충전 금액만큼 증가한다.")
        void increasesBalance_whenCharge() {
            // given
            Point point = Point.create(1L, 1000);

            // when
            point.charge(500);

            // then
            assertThat(point.getBalance()).isEqualTo(1500);
        }

        @Test
        @DisplayName("충전 금액이 0이면 BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenChargeAmountIsZero() {
            // given
            Point point = Point.create(1L, 1000);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> point.charge(0));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("충전 금액이 음수이면 BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenChargeAmountIsNegative() {
            // given
            Point point = Point.create(1L, 1000);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> point.charge(-500));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("포인트를 사용할 때,")
    class Use {

        @Test
        @DisplayName("잔액이 사용 금액만큼 감소한다.")
        void decreasesBalance_whenUse() {
            // given
            Point point = Point.create(1L, 1000);

            // when
            point.use(300);

            // then
            assertThat(point.getBalance()).isEqualTo(700);
        }

        @Test
        @DisplayName("잔액이 부족하면 BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenInsufficientBalance() {
            // given
            Point point = Point.create(1L, 100);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> point.use(500));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("포인트가 부족합니다."));
        }

        @Test
        @DisplayName("사용 금액이 0이면 BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenUseAmountIsZero() {
            // given
            Point point = Point.create(1L, 1000);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> point.use(0));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("사용 금액이 음수이면 BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenUseAmountIsNegative() {
            // given
            Point point = Point.create(1L, 1000);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> point.use(-100));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}
