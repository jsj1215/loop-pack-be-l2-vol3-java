package com.loopers.domain.member;

import com.loopers.infrastructure.member.MemberJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [통합 테스트 - Service Integration]
 *
 * 테스트 대상: MemberService (Domain Layer)
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: Service → Repository → Database
 *
 * 사용 라이브러리:
 * - JUnit 5 (org.junit.jupiter)
 * - Spring Boot Test (org.springframework.boot.test.context)
 * - Testcontainers (org.testcontainers) - testFixtures 모듈에서 제공
 * - AssertJ (org.assertj.core.api)
 *
 * 어노테이션 설명:
 * - @SpringBootTest: 전체 Spring ApplicationContext 로드
 * (org.springframework.boot.test.context.SpringBootTest)
 * → 모든 빈을 실제로 생성하고 주입
 * → 실제 환경과 유사한 테스트 가능
 *
 * - @Autowired: Spring이 관리하는 실제 빈 주입
 * → Mock이 아닌 실제 구현체 사용
 *
 * - @AfterEach: 각 테스트 후 실행되는 메서드
 * → 테스트 간 데이터 격리를 위해 DB 정리
 *
 * 특징:
 * - 전체 Spring Context 로드 → 단위 테스트보다 느림
 * - 실제 DB 연동 테스트 (Testcontainers로 MySQL 컨테이너 자동 실행)
 * - Docker Daemon 필수
 * - Service와 Repository의 실제 연동 검증
 * - BCrypt 암호화 실제 동작 검증
 */
@SpringBootTest // Testcontainers 사용 -> MySQL 컨테이너 시작하고 datasource 프로퍼티 동적으로 실행.
class MemberServiceIntegrationTest {

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberJpaRepository memberJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach // 테스트 종료 후, 데이터 삭제
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("회원가입을 할 때,")
    @Nested
    class Signup {

        @Test
        @DisplayName("유효한 정보로 가입하면, 회원이 생성되고 비밀번호가 암호화된다.")
        void createsMember_whenValidInput() {
            // arrange
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");

            // act
            Member member = memberService.signup(command);

            // assert
            assertAll(
                    () -> assertThat(member.getId()).isNotNull(),
                    () -> assertThat(member.getLoginId()).isEqualTo("testuser1"),
                    () -> assertThat(member.getPassword()).isNotEqualTo("Password1!"),
                    () -> assertThat(member.getPassword()).startsWith("$2a$"),
                    () -> assertThat(member.getName()).isEqualTo("홍길동"),
                    () -> assertThat(member.getEmail()).isEqualTo("test@example.com"),
                    () -> assertThat(member.getBirthDate()).isEqualTo("19990101"));
        }

        @Test
        @DisplayName("중복된 로그인 ID로 가입하면, CONFLICT 예외가 발생한다.")
        void throwsException_whenDuplicateLoginId() {
            // arrange
            SignupCommand firstCommand = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test1@example.com",
                    "19990101");
            memberService.signup(firstCommand);

            SignupCommand duplicateCommand = new SignupCommand(
                    "testuser1",
                    "Password2!",
                    "김철수",
                    "test2@example.com",
                    "19930101");

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.signup(duplicateCommand));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                    () -> assertThat(exception.getMessage()).contains("이미 사용 중인 로그인 아이디"));
        }

        @Test
        @DisplayName("비밀번호에 생년월일이 포함되면, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenPasswordContainsBirthDate() {
            // arrange
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Pass19990101!",
                    "홍길동",
                    "test@example.com",
                    "19990101");

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.signup(command));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("생년월일"));
        }

        @Test
        @DisplayName("입력 데이터에 공백이 있으면, 공백이 제거되어 저장된다.")
        void trimWhitespace_whenInputHasWhitespace() {
            // arrange
            SignupCommand command = new SignupCommand(
                    "  testuser1  ",
                    "  Password1!  ",
                    "  홍길동  ",
                    "  test@example.com  ",
                    "  19990101  ");

            // act
            Member member = memberService.signup(command);

            // assert
            assertAll(
                    () -> assertThat(member.getLoginId()).isEqualTo("testuser1"),
                    () -> assertThat(member.getName()).isEqualTo("홍길동"),
                    () -> assertThat(member.getEmail()).isEqualTo("test@example.com"),
                    () -> assertThat(member.getBirthDate()).isEqualTo("19990101"));
        }

        @Test
        @DisplayName("중복된 이메일로 가입하면, CONFLICT 예외가 발생한다.")
        void throwsException_whenDuplicateEmail() {
            // arrange
            SignupCommand firstCommand = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");
            memberService.signup(firstCommand);

            SignupCommand duplicateCommand = new SignupCommand(
                    "testuser2",
                    "Password2!",
                    "김철수",
                    "test@example.com", // 중복된 이메일
                    "19930101");

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.signup(duplicateCommand));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                    () -> assertThat(exception.getMessage()).contains("이메일"));
        }
    }

    @DisplayName("회원 인증을 할 때,")
    @Nested
    class Authenticate {

        @Test
        @DisplayName("유효한 로그인 ID와 비밀번호면 회원을 반환한다.")
        void returnsMember_whenValidCredentials() {
            // arrange - 회원 생성
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");
            memberService.signup(command);

            // act
            Member member = memberService.authenticate("testuser1", "Password1!");

            // assert
            assertAll(
                    () -> assertThat(member).isNotNull(),
                    () -> assertThat(member.getLoginId()).isEqualTo("testuser1"),
                    () -> assertThat(member.getName()).isEqualTo("홍길동"),
                    () -> assertThat(member.getEmail()).isEqualTo("test@example.com"));
        }

        @Test
        @DisplayName("존재하지 않는 로그인 ID면 UNAUTHORIZED 예외가 발생한다.")
        void throwsException_whenLoginIdNotFound() {
            // arrange - 회원이 없음

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.authenticate("nonexistent", "Password1!"));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }

        @Test
        @DisplayName("비밀번호가 일치하지 않으면 UNAUTHORIZED 예외가 발생한다.")
        void throwsException_whenPasswordNotMatches() {
            // arrange - 회원 생성
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");
            memberService.signup(command);

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.authenticate("testuser1", "WrongPassword!"));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }

        @Test
        @DisplayName("로그인 ID가 null이면 UNAUTHORIZED 예외가 발생한다.")
        void throwsException_whenLoginIdIsNull() {
            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.authenticate(null, "Password1!"));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }

        @Test
        @DisplayName("비밀번호가 null이면 UNAUTHORIZED 예외가 발생한다.")
        void throwsException_whenPasswordIsNull() {
            // arrange - 회원 생성
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");
            memberService.signup(command);

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.authenticate("testuser1", null));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }

        @Test
        @DisplayName("비밀번호 변경 후 새 비밀번호로 인증에 성공한다.")
        void authenticates_withNewPassword_afterPasswordChange() {
            // arrange - 회원 생성
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");
            memberService.signup(command);

            // 인증하여 영속 상태의 Member 조회 후 비밀번호 변경
            Member authenticatedMember = memberService.authenticate("testuser1", "Password1!");
            memberService.changePassword(authenticatedMember, "Password1!", "NewPass123!");

            // act
            Member member = memberService.authenticate("testuser1", "NewPass123!");

            // assert
            assertThat(member).isNotNull();
            assertThat(member.getLoginId()).isEqualTo("testuser1");
        }

        @Test
        @DisplayName("비밀번호 변경 후 기존 비밀번호로 인증에 실패한다.")
        void failsAuthentication_withOldPassword_afterPasswordChange() {
            // arrange - 회원 생성
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");
            memberService.signup(command);

            // 인증하여 영속 상태의 Member 조회 후 비밀번호 변경
            Member authenticatedMember = memberService.authenticate("testuser1", "Password1!");
            memberService.changePassword(authenticatedMember, "Password1!", "NewPass123!");

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.authenticate("testuser1", "Password1!"));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }
    }

    @DisplayName("비밀번호 변경을 할 때,")
    @Nested
    class ChangePassword {

        @Test
        @DisplayName("유효한 새 비밀번호로 변경하면 암호화되어 저장된다.")
        void changesPassword_whenValidNewPassword() {
            // arrange - 회원 생성
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");
            Member signedUpMember = memberService.signup(command);

            // act
            memberService.changePassword(signedUpMember, "Password1!", "NewPass123!");

            // assert
            Member member = memberJpaRepository.findByLoginId("testuser1").orElseThrow();
            assertAll(
                    () -> assertThat(member.getPassword()).startsWith("$2a$"),
                    () -> assertThat(member.getPassword()).isNotEqualTo("$2a$10$") // 암호화됨
            );
        }

        @Test
        @DisplayName("기존 비밀번호가 일치하지 않으면 BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenCurrentPasswordNotMatches() {
            // arrange - 회원 생성
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");
            Member member = memberService.signup(command);

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.changePassword(member, "WrongPassword!", "NewPass123!"));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("기존 비밀번호"));
        }

        @Test
        @DisplayName("새 비밀번호가 현재 비밀번호와 동일하면 BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNewPasswordSameAsCurrent() {
            // arrange - 회원 생성
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");
            Member member = memberService.signup(command);

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.changePassword(member, "Password1!", "Password1!"));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("현재 비밀번호"));
        }

        @Test
        @DisplayName("새 비밀번호에 생년월일이 포함되면 BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNewPasswordContainsBirthDate() {
            // arrange - 회원 생성
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");
            Member member = memberService.signup(command);

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.changePassword(member, "Password1!", "Pass19990101!"));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("생년월일"));
        }
    }
}
