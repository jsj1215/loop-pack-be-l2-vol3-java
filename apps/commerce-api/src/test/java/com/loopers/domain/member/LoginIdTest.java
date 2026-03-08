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
  대상 : LoginId VO
  사용 라이브러리 : JUnit 5, AssertJ
 
  특징:
  - Spring Context 불필요 → 빠른 실행
  - Docker/DB 불필요
 */
@DisplayName("로그인 아이디를 생성할 때,")
class LoginIdTest {

    @Nested
    @DisplayName("성공 케이스")
    class Success {

        @Test
        @DisplayName("영문과 숫자로 4~20자인 경우, 정상적으로 생성된다.")
        void createsLoginId_whenValidFormat() {
            // arrange
            String value = "testuser1";

            // act
            LoginId loginId = new LoginId(value);

            // assert
            assertThat(loginId.value()).isEqualTo(value);
        }

        @Test
        @DisplayName("정확히 4자인 경우, 정상적으로 생성된다.")
        void createsLoginId_whenExactly4Characters() {
            // arrange
            String value = "test";

            // act
            LoginId loginId = new LoginId(value);

            // assert
            assertThat(loginId.value()).isEqualTo(value);
        }

        @Test
        @DisplayName("정확히 20자인 경우, 정상적으로 생성된다.")
        void createsLoginId_whenExactly20Characters() {
            // arrange
            String value = "a".repeat(20);

            // act
            LoginId loginId = new LoginId(value);

            // assert
            assertThat(loginId.value()).isEqualTo(value);
        }

        @Test
        @DisplayName("앞뒤 공백이 있는 경우, 공백이 제거되어 저장된다.")
        void createsLoginId_whenHasWhitespace_thenTrimmed() {
            // arrange
            String value = "  testuser1  ";

            // act
            LoginId loginId = new LoginId(value);

            // assert
            assertThat(loginId.value()).isEqualTo("testuser1");
        }
    }

    @Nested
    @DisplayName("실패 케이스")
    class Failure {

        @Test
        @DisplayName("4자 미만인 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenLessThan4Characters() {
            // arrange
            String value = "abc";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new LoginId(value));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("4", "20"));
        }

        @Test
        @DisplayName("20자 초과인 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenMoreThan20Characters() {
            // arrange
            String value = "a".repeat(21);

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new LoginId(value));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("4", "20"));
        }

        @Test
        @DisplayName("특수문자가 포함된 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenContainsSpecialCharacters() {
            // arrange
            String value = "test@user";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new LoginId(value));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("한글이 포함된 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenContainsKorean() {
            // arrange
            String value = "test유저";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new LoginId(value));

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
                    () -> new LoginId(value));

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
                    () -> new LoginId(value));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}
