package com.loopers.domain.member;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/*
  [단위 테스트]
  대상 : Email VO
  사용 라이브러리 : JUnit 5, AssertJ
 
  특징:
  - Spring Context 불필요 → 빠른 실행
  - Docker/DB 불필요
 */
@DisplayName("이메일을 생성할 때,")
class EmailTest {

    @Nested
    @DisplayName("성공 케이스")
    class Success {

        @Test
        @DisplayName("유효한 이메일 형식인 경우, 정상적으로 생성된다.")
        void createsEmail_whenValidFormat() {
            // arrange
            String value = "test@example.com";

            // act
            Email email = new Email(value);

            // assert
            assertThat(email.value()).isEqualTo(value);
        }

        @Test
        @DisplayName("서브도메인이 있는 이메일인 경우, 정상적으로 생성된다.")
        void createsEmail_whenHasSubdomain() {
            // arrange
            String value = "test@mail.example.com";

            // act
            Email email = new Email(value);

            // assert
            assertThat(email.value()).isEqualTo(value);
        }

        @Test
        @DisplayName("점이 포함된 로컬 파트인 경우, 정상적으로 생성된다.")
        void createsEmail_whenLocalPartHasDot() {
            // arrange
            String value = "test.user@example.com";

            // act
            Email email = new Email(value);

            // assert
            assertThat(email.value()).isEqualTo(value);
        }

        @Test
        @DisplayName("플러스가 포함된 로컬 파트인 경우, 정상적으로 생성된다.")
        void createsEmail_whenLocalPartHasPlus() {
            // arrange
            String value = "test+user@example.com";

            // act
            Email email = new Email(value);

            // assert
            assertThat(email.value()).isEqualTo(value);
        }

        @Test
        @DisplayName("앞뒤 공백이 있는 경우, 공백이 제거되어 저장된다.")
        void createsEmail_whenHasWhitespace_thenTrimmed() {
            // arrange
            String value = "  test@example.com  ";

            // act
            Email email = new Email(value);

            // assert
            assertThat(email.value()).isEqualTo("test@example.com");
        }
    }

    @Nested
    @DisplayName("실패 케이스")
    class Failure {

        @Test
        @DisplayName("@가 없는 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNoAtSign() {
            // arrange
            String value = "testexample.com";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new Email(value));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("도메인이 없는 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNoDomain() {
            // arrange
            String value = "test@";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new Email(value));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("로컬 파트가 없는 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNoLocalPart() {
            // arrange
            String value = "@example.com";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new Email(value));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("TLD가 없는 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNoTld() {
            // arrange
            String value = "test@example";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new Email(value));

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
                    () -> new Email(value));

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
                    () -> new Email(value));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}
