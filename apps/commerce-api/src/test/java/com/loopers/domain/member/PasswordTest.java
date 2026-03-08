package com.loopers.domain.member;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/*
  [단위 테스트]
  대상 : Password VO
  사용 라이브러리 : JUnit 5, AssertJ
 
  특징:
  - Spring Context 불필요 → 빠른 실행
  - Docker/DB 불필요
 */
@DisplayName("비밀번호를 생성할 때,")
class PasswordTest {

    @Nested
    @DisplayName("성공 케이스")
    class Success {

        @Test
        @DisplayName("영문 대소문자, 숫자, 특수문자로 8~16자인 경우, 정상적으로 생성된다.")
        void createsPassword_whenValidFormat() {
            // arrange
            String value = "Password1!";

            // act
            Password password = new Password(value);

            // assert
            assertThat(password.value()).isEqualTo(value);
        }

        @Test
        @DisplayName("정확히 8자인 경우, 정상적으로 생성된다.")
        void createsPassword_whenExactly8Characters() {
            // arrange
            String value = "Pass12!@";

            // act
            Password password = new Password(value);

            // assert
            assertThat(password.value()).isEqualTo(value);
        }

        @Test
        @DisplayName("정확히 16자인 경우, 정상적으로 생성된다.")
        void createsPassword_whenExactly16Characters() {
            // arrange
            String value = "Password1234!@#$";

            // act
            Password password = new Password(value);

            // assert
            assertThat(password.value()).isEqualTo(value);
        }

        @Test
        @DisplayName("앞뒤 공백이 있는 경우, 공백이 제거되어 저장된다.")
        void createsPassword_whenHasWhitespace_thenTrimmed() {
            // arrange
            String value = "  Password1!  ";

            // act
            Password password = new Password(value);

            // assert
            assertThat(password.value()).isEqualTo("Password1!");
        }
    }

    @Nested
    @DisplayName("실패 케이스")
    class Failure {

        @Test
        @DisplayName("8자 미만인 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenLessThan8Characters() {
            // arrange
            String value = "Pass1!";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new Password(value));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("8", "16"));
        }

        @Test
        @DisplayName("16자 초과인 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenMoreThan16Characters() {
            // arrange
            String value = "Password12345!@#$";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new Password(value));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("8", "16"));
        }

        @Test
        @DisplayName("한글이 포함된 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenContainsKorean() {
            // arrange
            String value = "Password비밀번호1!";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new Password(value));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("null인 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNull() {
            // arrange
            String value = null;

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new Password(value));

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
                    () -> new Password(value));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("생년월일 포함 여부를 검증할 때,")
    class ValidateNotContainsBirthDate {

        @Test
        @DisplayName("생년월일이 포함된 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenContainsBirthDate() {
            // arrange
            Password password = new Password("Pass19990101!");
            String birthDate = "19990101";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> password.validateNotContainsBirthDate(birthDate));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("생년월일"));
        }

        @Test
        @DisplayName("생년월일이 포함되지 않은 경우, 검증을 통과한다.")
        void passesValidation_whenNotContainsBirthDate() {
            // arrange
            Password password = new Password("Password1!");
            String birthDate = "19990101";

            // act & assert
            assertDoesNotThrow(() -> password.validateNotContainsBirthDate(birthDate));
        }

        @Test
        @DisplayName("생년월일이 null인 경우, 검증을 통과한다.")
        void passesValidation_whenBirthDateIsNull() {
            // arrange
            Password password = new Password("Password1!");

            // act & assert
            assertDoesNotThrow(() -> password.validateNotContainsBirthDate(null));
        }
    }
}
