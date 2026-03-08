package com.loopers.domain.member;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/*
  [단위 테스트]
  대상 : SignupCommand
  사용 라이브러리 : JUnit 5, AssertJ
 
  특징:
  - Spring Context 불필요 → 빠른 실행
  - Docker/DB 불필요
 */
@DisplayName("SignupCommand")
class SignupCommandTest {

    @Test
    @DisplayName("모든 필드를 포함하여 생성된다.")
    void createsCommand_withAllFields() {
        // given
        String loginId = "testuser1";
        String password = "Password1!";
        String name = "홍길동";
        String email = "test@example.com";
        String birthDate = "19990101";

        // when
        SignupCommand command = new SignupCommand(loginId, password, name, email, birthDate);

        // then
        assertAll(
                () -> assertThat(command.loginId()).isEqualTo("testuser1"),
                () -> assertThat(command.password()).isEqualTo("Password1!"),
                () -> assertThat(command.name()).isEqualTo("홍길동"),
                () -> assertThat(command.email()).isEqualTo("test@example.com"),
                () -> assertThat(command.birthDate()).isEqualTo("19990101"));
    }

    @Test
    @DisplayName("record의 컴포넌트에 직접 접근할 수 있다.")
    void accessesRecordComponents_directly() {
        // given
        SignupCommand command = new SignupCommand(
                "testuser1",
                "Password1!",
                "홍길동",
                "test@example.com",
                "19990101");

        // when & then
        assertAll(
                () -> assertThat(command.loginId()).isEqualTo("testuser1"),
                () -> assertThat(command.password()).isEqualTo("Password1!"),
                () -> assertThat(command.name()).isEqualTo("홍길동"),
                () -> assertThat(command.email()).isEqualTo("test@example.com"),
                () -> assertThat(command.birthDate()).isEqualTo("19990101"));
    }

    @Test
    @DisplayName("동일한 값을 가진 두 Command는 같다.")
    void equals_whenSameValues() {
        // given
        SignupCommand command1 = new SignupCommand(
                "testuser1",
                "Password1!",
                "홍길동",
                "test@example.com",
                "19990101");
        SignupCommand command2 = new SignupCommand(
                "testuser1",
                "Password1!",
                "홍길동",
                "test@example.com",
                "19990101");

        // when & then
        assertThat(command1).isEqualTo(command2);
    }

    @Test
    @DisplayName("다른 값을 가진 두 Command는 다르다.")
    void notEquals_whenDifferentValues() {
        // given
        SignupCommand command1 = new SignupCommand(
                "testuser1",
                "Password1!",
                "홍길동",
                "test@example.com",
                "19990101");
        SignupCommand command2 = new SignupCommand(
                "testuser2",
                "Password2!",
                "김철수",
                "test2@example.com",
                "19900101");

        // when & then
        assertThat(command1).isNotEqualTo(command2);
    }
}
