package com.loopers.domain.member;

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
  대상 : BirthDate VO
  사용 라이브러리 : JUnit 5, AssertJ
 
  특징:
  - Spring Context 불필요 → 빠른 실행
  - Docker/DB 불필요
 */
@DisplayName("생년월일을 생성할 때,")
class BirthDateTest {

    @Nested // 테스트를 계층적으로 구성
    @DisplayName("성공 케이스")
    class Success {

        @Test
        @DisplayName("8자리 숫자인 경우, 정상적으로 생성된다.")
        void createsBirthDate_whenValidFormat() {
            // arrange
            String value = "19990101";

            // act
            BirthDate birthDate = new BirthDate(value);

            // assert
            assertThat(birthDate.value()).isEqualTo(value);
        }

        @Test
        @DisplayName("유효한 날짜인 경우, 정상적으로 생성된다.")
        void createsBirthDate_whenValidDate() {
            // arrange
            String value = "20000229"; // 2000년은 윤년

            // act
            BirthDate birthDate = new BirthDate(value);

            // assert
            assertThat(birthDate.value()).isEqualTo(value);
        }

        @Test
        @DisplayName("앞뒤 공백이 있는 경우, 공백이 제거되어 저장된다.")
        void createsBirthDate_whenHasWhitespace_thenTrimmed() {
            // arrange
            String value = "  19990101  ";

            // act
            BirthDate birthDate = new BirthDate(value);

            // assert
            assertThat(birthDate.value()).isEqualTo("19990101");
        }
    }

    @Nested
    @DisplayName("실패 케이스")
    class Failure {

        @Test
        @DisplayName("8자리가 아닌 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNot8Digits() {
            // arrange
            String value = "1992121";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new BirthDate(value));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("8자리"));
        }

        @Test
        @DisplayName("9자리인 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_when9Digits() {
            // arrange
            String value = "199901015";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new BirthDate(value));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("문자가 포함된 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenContainsLetters() {
            // arrange
            String value = "1992121a";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new BirthDate(value));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("하이픈이 포함된 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenContainsHyphen() {
            // arrange
            String value = "1992-12-15";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new BirthDate(value));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("유효하지 않은 월인 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenInvalidMonth() {
            // arrange
            String value = "19921315"; // 13월은 없음

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new BirthDate(value));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("유효하지 않은 날짜"));
        }

        @Test
        @DisplayName("유효하지 않은 일인 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenInvalidDay() {
            // arrange
            String value = "19920230"; // 2월 30일은 없음

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new BirthDate(value));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("유효하지 않은 날짜"));
        }

        @Test
        @DisplayName("윤년이 아닌 해의 2월 29일인 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNotLeapYearFeb29() {
            // arrange
            String value = "19990229"; // 1999년은 윤년이 아님

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new BirthDate(value));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("유효하지 않은 날짜"));
        }

        @Test
        @DisplayName("null인 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNull() {
            // arrange
            String value = null;

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new BirthDate(value));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("빈 문자열인 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenEmpty() {
            // arrange
            String value = "";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new BirthDate(value));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("미래 날짜인 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenFutureDate() {
            // arrange
            String value = "29991231"; // 미래 날짜

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new BirthDate(value));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("미래"));
        }

        @Test
        @DisplayName("오늘 날짜인 경우, 정상적으로 생성된다.")
        void createsBirthDate_whenToday() {
            // arrange
            java.time.LocalDate today = java.time.LocalDate.now();
            String value = String.format("%04d%02d%02d", today.getYear(), today.getMonthValue(), today.getDayOfMonth());

            // act
            BirthDate birthDate = new BirthDate(value);

            // assert
            assertThat(birthDate.value()).isEqualTo(value);
        }
    }
}
