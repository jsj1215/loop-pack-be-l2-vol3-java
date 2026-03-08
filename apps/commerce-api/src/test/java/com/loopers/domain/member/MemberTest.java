package com.loopers.domain.member;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/*
  [단위 테스트]
  대상 : Member
  사용 라이브러리 : JUnit 5, AssertJ
 
  특징:
  - Spring Context 불필요 → 빠른 실행
  - Docker/DB 불필요
 */
@DisplayName("Member 엔티티")
class MemberTest {

    @Nested
    @DisplayName("생성할 때,")
    class Create {

        @Test
        @DisplayName("유효한 Value Object로 생성하면 모든 필드가 올바르게 설정된다.")
        void createsEntity_whenValidValueObjects() {
            // given
            LoginId loginId = new LoginId("testuser1");
            String encodedPassword = "$2a$10$encodedPassword";
            MemberName name = new MemberName("홍길동");
            Email email = new Email("test@example.com");
            BirthDate birthDate = new BirthDate("19990101");

            // when
            Member member = new Member(loginId, encodedPassword, name, email, birthDate);

            // then
            assertAll(
                    () -> assertThat(member.getLoginId()).isEqualTo("testuser1"),
                    () -> assertThat(member.getPassword()).isEqualTo("$2a$10$encodedPassword"),
                    () -> assertThat(member.getName()).isEqualTo("홍길동"),
                    () -> assertThat(member.getEmail()).isEqualTo("test@example.com"),
                    () -> assertThat(member.getBirthDate()).isEqualTo("19990101"));
        }

        @Test
        @DisplayName("Value Object의 값이 trim된 상태로 저장된다.")
        void storesTrimmedValues_whenValueObjectsHaveWhitespace() {
            // given
            LoginId loginId = new LoginId("  testuser1  ");
            String encodedPassword = "$2a$10$encodedPassword";
            MemberName name = new MemberName("  홍길동  ");
            Email email = new Email("  test@example.com  ");
            BirthDate birthDate = new BirthDate("  19990101  ");

            // when
            Member member = new Member(loginId, encodedPassword, name, email, birthDate);

            // then
            assertAll(
                    () -> assertThat(member.getLoginId()).isEqualTo("testuser1"),
                    () -> assertThat(member.getName()).isEqualTo("홍길동"),
                    () -> assertThat(member.getEmail()).isEqualTo("test@example.com"),
                    () -> assertThat(member.getBirthDate()).isEqualTo("19990101"));
        }
    }

    @Nested
    @DisplayName("비밀번호를 변경할 때,")
    class ChangePassword {

        @Test
        @DisplayName("새 암호화된 비밀번호로 변경된다.")
        void changesPassword_toNewEncodedPassword() {
            // given
            Member member = new Member(
                    new LoginId("testuser1"),
                    "$2a$10$oldPassword",
                    new MemberName("홍길동"),
                    new Email("test@example.com"),
                    new BirthDate("19990101"));
            String newEncodedPassword = "$2a$10$newPassword";

            // when
            member.changePassword(newEncodedPassword);

            // then
            assertThat(member.getPassword()).isEqualTo("$2a$10$newPassword");
        }

        @Test
        @DisplayName("기존 비밀번호와 다른 값으로 변경된다.")
        void passwordIsDifferent_afterChange() {
            // given
            String oldPassword = "$2a$10$oldPassword";
            Member member = new Member(
                    new LoginId("testuser1"),
                    oldPassword,
                    new MemberName("홍길동"),
                    new Email("test@example.com"),
                    new BirthDate("19990101"));
            String newEncodedPassword = "$2a$10$newPassword";

            // when
            member.changePassword(newEncodedPassword);

            // then
            assertThat(member.getPassword()).isNotEqualTo(oldPassword);
        }
    }
}
