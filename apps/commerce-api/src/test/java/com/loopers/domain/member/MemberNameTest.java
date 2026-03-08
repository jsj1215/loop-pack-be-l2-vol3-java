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
  대상 : MemberName VO
  사용 라이브러리 : JUnit 5, AssertJ
 
  특징:
  - Spring Context 불필요 → 빠른 실행
  - Docker/DB 불필요
 */
@DisplayName("이름을 생성할 때,")
class MemberNameTest {

    @Nested
    @DisplayName("성공 케이스")
    class Success {

        @Test
        @DisplayName("한글로 2~10자인 경우, 정상적으로 생성된다.")
        void createsMemberName_whenValidFormat() {
            // arrange
            String value = "홍길동";

            // act
            MemberName memberName = new MemberName(value);

            // assert
            assertThat(memberName.value()).isEqualTo(value);
        }

        @Test
        @DisplayName("정확히 2자인 경우, 정상적으로 생성된다.")
        void createsMemberName_whenExactly2Characters() {
            // arrange
            String value = "홍길";

            // act
            MemberName memberName = new MemberName(value);

            // assert
            assertThat(memberName.value()).isEqualTo(value);
        }

        @Test
        @DisplayName("정확히 10자인 경우, 정상적으로 생성된다.")
        void createsMemberName_whenExactly10Characters() {
            // arrange
            String value = "가나다라마바사아자차";

            // act
            MemberName memberName = new MemberName(value);

            // assert
            assertThat(memberName.value()).isEqualTo(value);
        }

        @Test
        @DisplayName("앞뒤 공백이 있는 경우, 공백이 제거되어 저장된다.")
        void createsMemberName_whenHasWhitespace_thenTrimmed() {
            // arrange
            String value = "  홍길동  ";

            // act
            MemberName memberName = new MemberName(value);

            // assert
            assertThat(memberName.value()).isEqualTo("홍길동");
        }
    }

    @Nested
    @DisplayName("실패 케이스")
    class Failure {

        @Test
        @DisplayName("2자 미만인 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenLessThan2Characters() {
            // arrange
            String value = "홍";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new MemberName(value));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("2", "10"));
        }

        @Test
        @DisplayName("10자 초과인 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenMoreThan10Characters() {
            // arrange
            String value = "가나다라마바사아자차카";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new MemberName(value));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("2", "10"));
        }

        @Test
        @DisplayName("영문이 포함된 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenContainsEnglish() {
            // arrange
            String value = "홍길dong";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new MemberName(value));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("숫자가 포함된 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenContainsNumber() {
            // arrange
            String value = "홍길동123";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new MemberName(value));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @Test
        @DisplayName("특수문자가 포함된 경우, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenContainsSpecialCharacters() {
            // arrange
            String value = "홍길동!";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> new MemberName(value));

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
                    () -> new MemberName(value));

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
                    () -> new MemberName(value));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("이름 마스킹 시,")
    class Masking {

        @Test
        @DisplayName("3글자 이름의 마지막 글자를 *로 마스킹한다.")
        void masksLastCharacter_when3Characters() {
            // arrange
            MemberName memberName = new MemberName("홍길동");

            // act
            String masked = memberName.masked();

            // assert
            assertThat(masked).isEqualTo("홍길*");
        }

        @Test
        @DisplayName("2글자 이름의 마지막 글자를 *로 마스킹한다.")
        void masksLastCharacter_when2Characters() {
            // arrange
            MemberName memberName = new MemberName("홍길");

            // act
            String masked = memberName.masked();

            // assert
            assertThat(masked).isEqualTo("홍*");
        }

        @Test
        @DisplayName("10글자 이름의 마지막 글자를 *로 마스킹한다.")
        void masksLastCharacter_when10Characters() {
            // arrange
            MemberName memberName = new MemberName("가나다라마바사아자차");

            // act
            String masked = memberName.masked();

            // assert
            assertThat(masked).isEqualTo("가나다라마바사아자*");
        }
    }
}
