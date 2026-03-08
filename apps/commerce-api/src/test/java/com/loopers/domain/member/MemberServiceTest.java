package com.loopers.domain.member;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [단위 테스트 - Service with Mock]
 *
 * 테스트 대상: MemberService
 * 테스트 유형: 단위 테스트 (Mock 사용)
 * 테스트 더블: Mock (MemberRepository, PasswordEncoder)
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 🎭 테스트 더블 (Test Double) 개념 정리
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 테스트 더블이란?
 * - 영화의 "스턴트 더블"처럼, 테스트에서 실제 객체 대신 사용하는 가짜 객체
 * - 마틴 파울러가 명명한 용어
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ 유형 │ 역할 │ Mockito에서 │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │ Dummy │ 매개변수 채우기용 (사용 안됨) │ mock() 생성 후 미사용 │
 * │ Stub │ 고정된 응답 반환 │ when().thenReturn() │
 * │ Spy │ 실제 객체 + 일부 감시/변경 │ @Spy, spy() │
 * │ Mock │ 호출 여부/횟수 검증 │ verify() │
 * │ Fake │ 간단한 작동 구현체 │ 직접 구현 (MemoryRepo 등) │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * 📌 이 테스트 클래스에서 사용되는 테스트 더블:
 * - Stub: when().thenReturn()으로 고정 응답 설정
 * - Mock: verify()로 메서드 호출 검증
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 사용 라이브러리:
 * - JUnit 5 (org.junit.jupiter)
 * - Mockito (org.mockito)
 * - AssertJ (org.assertj.core.api)
 *
 * 어노테이션 설명:
 * - @ExtendWith(MockitoExtension.class): Mockito를 JUnit 5와 통합
 * (org.mockito.junit.jupiter.MockitoExtension)
 * → @Mock, @InjectMocks 어노테이션을 활성화
 *
 * - @Mock: 가짜 객체(Mock) 생성 (org.mockito.Mock)
 * → 실제 구현체 대신 동작을 시뮬레이션하는 객체
 * → when().thenReturn()으로 반환값 지정 (Stub 역할)
 * → verify()로 메서드 호출 여부 검증 (Mock 역할)
 *
 * - @InjectMocks: Mock 객체들을 자동 주입하여 테스트 대상 생성 (org.mockito.InjectMocks)
 * → 생성자/세터/필드 주입을 자동으로 수행
 *
 * 특징:
 * - Spring Context 불필요 → 빠른 실행
 * - Docker/DB 불필요
 * - 의존성을 Mock으로 대체하여 테스트 대상만 격리 테스트
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberService 단위 테스트")
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private MemberService memberService;

    @Nested
    @DisplayName("회원가입을 할 때,")
    class Signup {

        @Test
        @DisplayName("비밀번호가 암호화되어 저장된다.")
        void encryptsPassword_whenSignup() {
            // arrange
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");

            // ═════════════════════════════════════════════════════════════════
            // 🔷 STUB (스텁) - 고정된 응답을 반환하도록 설정
            // ═════════════════════════════════════════════════════════════════
            // when().thenReturn() = Stub 패턴
            // → 특정 메서드가 호출되면 미리 정해진 값을 반환
            // → 실제 Repository/Encoder 없이 테스트 가능
            when(memberRepository.existsByLoginId(anyString())).thenReturn(false); // Stub: false 반환
            when(memberRepository.existsByEmail(anyString())).thenReturn(false); // Stub: false 반환
            when(passwordEncoder.encode("Password1!")).thenReturn("$2a$10$encodedPassword"); // Stub: 암호화된 값 반환
            when(memberRepository.save(any(Member.class))).thenAnswer(
                    invocation -> invocation.getArgument(0)); // Stub: 전달받은 객체 그대로 반환

            // act
            Member member = memberService.signup(command);

            // ═════════════════════════════════════════════════════════════════
            // 🔶 MOCK (목) - 메서드 호출 여부/횟수 검증
            // ═════════════════════════════════════════════════════════════════
            // verify() = Mock 패턴
            // → 특정 메서드가 몇 번 호출되었는지 검증
            // → 테스트 대상이 의존 객체를 올바르게 사용하는지 확인
            assertAll(
                    () -> verify(passwordEncoder, times(1)).encode("Password1!"), // Mock 검증: encode가 1번 호출됨
                    () -> verify(memberRepository, times(1)).save(any(Member.class)), // Mock 검증: save가 1번 호출됨
                    () -> assertThat(member.getPassword()).isEqualTo("$2a$10$encodedPassword")); // 상태 검증
        }

        @Test
        @DisplayName("중복 로그인 ID 검사가 수행된다.")
        void checksExistingLoginId_whenSignup() {
            // arrange
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");

            // 🔷 STUB - 중복 ID가 존재한다고 가정
            when(memberRepository.existsByLoginId("testuser1")).thenReturn(true); // Stub: true 반환

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.signup(command));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                    // 🔶 MOCK - 호출 검증
                    () -> verify(memberRepository, times(1)).existsByLoginId("testuser1"), // Mock: 1번 호출됨
                    () -> verify(memberRepository, never()).save(any(Member.class))); // Mock: 호출 안됨
        }

        @Test
        @DisplayName("Value Object 유효성 검사가 먼저 수행된다.")
        void validatesValueObjects_beforeCheckingDuplicate() {
            // arrange - 잘못된 로그인 ID
            SignupCommand command = new SignupCommand(
                    "ab", // 4자 미만
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.signup(command));

            // 🔶 MOCK 검증 - Repository 메서드가 호출되지 않아야 함
            // → Value Object 유효성 검사에서 실패하면 Repository까지 도달하지 않음
            // → never() = 한 번도 호출되지 않았음을 검증
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> verify(memberRepository, never()).existsByLoginId(anyString()), // Mock: 호출 안됨
                    () -> verify(memberRepository, never()).save(any(Member.class))); // Mock: 호출 안됨
        }

        @Test
        @DisplayName("비밀번호에 생년월일 포함 검사가 수행된다.")
        void validatesBirthDateInPassword_beforeSaving() {
            // arrange
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Pass19990101!", // 생년월일 포함
                    "홍길동",
                    "test@example.com",
                    "19990101");

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.signup(command));

            // assert - 생년월일 검사는 중복 검사 전에 수행되므로 Repository는 호출되지 않음
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("생년월일"),
                    () -> verify(memberRepository, never()).existsByLoginId(anyString()),
                    () -> verify(memberRepository, never()).save(any(Member.class)));
        }

        @Test
        @DisplayName("입력값의 공백이 제거된 후 저장된다.")
        void trimsWhitespace_whenSignup() {
            // arrange
            SignupCommand command = new SignupCommand(
                    "  testuser1  ",
                    "  Password1!  ",
                    "  홍길동  ",
                    "  test@example.com  ",
                    "  19990101  ");

            when(memberRepository.existsByLoginId("testuser1")).thenReturn(false);
            when(memberRepository.existsByEmail("test@example.com")).thenReturn(false);
            when(passwordEncoder.encode("Password1!")).thenReturn("$2a$encoded");
            when(memberRepository.save(any(Member.class))).thenAnswer(
                    invocation -> invocation.getArgument(0));

            // act
            Member member = memberService.signup(command);

            // assert
            assertAll(
                    () -> assertThat(member.getLoginId()).isEqualTo("testuser1"),
                    () -> assertThat(member.getName()).isEqualTo("홍길동"),
                    () -> assertThat(member.getEmail()).isEqualTo("test@example.com"),
                    () -> assertThat(member.getBirthDate()).isEqualTo("19990101"),
                    () -> verify(memberRepository).existsByLoginId("testuser1"));
        }

        @Test
        @DisplayName("중복 이메일 검사가 수행된다.")
        void checksExistingEmail_whenSignup() {
            // arrange
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");

            // Stub - 중복 이메일 존재
            when(memberRepository.existsByLoginId("testuser1")).thenReturn(false);
            when(memberRepository.existsByEmail("test@example.com")).thenReturn(true);

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.signup(command));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                    () -> assertThat(exception.getMessage()).contains("이메일"),
                    () -> verify(memberRepository, times(1)).existsByEmail("test@example.com"),
                    () -> verify(memberRepository, never()).save(any(Member.class)));
        }
    }

    @Nested
    @DisplayName("회원 인증을 할 때,")
    class Authenticate {

        @Test
        @DisplayName("로그인 ID와 비밀번호가 일치하면 회원을 반환한다.")
        void returnsMember_whenCredentialsMatch() {
            // arrange
            String loginId = "testuser1";
            String rawPassword = "Password1!";
            String encodedPassword = "$2a$10$encodedPassword";

            Member member = new Member(
                    new LoginId(loginId),
                    encodedPassword,
                    new MemberName("홍길동"),
                    new Email("test@example.com"),
                    new BirthDate("19990101"));

            when(memberRepository.findByLoginId(loginId)).thenReturn(java.util.Optional.of(member));
            when(passwordEncoder.matches(rawPassword, encodedPassword)).thenReturn(true);

            // act
            Member result = memberService.authenticate(loginId, rawPassword);

            // assert
            assertAll(
                    () -> assertThat(result).isNotNull(),
                    () -> assertThat(result.getLoginId()).isEqualTo(loginId),
                    () -> verify(memberRepository, times(1)).findByLoginId(loginId),
                    () -> verify(passwordEncoder, times(1)).matches(rawPassword, encodedPassword));
        }

        @Test
        @DisplayName("로그인 ID가 존재하지 않으면 UNAUTHORIZED 예외가 발생한다.")
        void throwsException_whenLoginIdNotFound() {
            // arrange
            String loginId = "nonexistent";
            String rawPassword = "Password1!";

            when(memberRepository.findByLoginId(loginId)).thenReturn(java.util.Optional.empty());

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.authenticate(loginId, rawPassword));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED),
                    () -> verify(memberRepository, times(1)).findByLoginId(loginId),
                    () -> verify(passwordEncoder, never()).matches(anyString(), anyString()));
        }

        @Test
        @DisplayName("비밀번호가 일치하지 않으면 UNAUTHORIZED 예외가 발생한다.")
        void throwsException_whenPasswordNotMatches() {
            // arrange
            String loginId = "testuser1";
            String rawPassword = "WrongPassword!";
            String encodedPassword = "$2a$10$encodedPassword";

            Member member = new Member(
                    new LoginId(loginId),
                    encodedPassword,
                    new MemberName("홍길동"),
                    new Email("test@example.com"),
                    new BirthDate("19990101"));

            when(memberRepository.findByLoginId(loginId)).thenReturn(java.util.Optional.of(member));
            when(passwordEncoder.matches(rawPassword, encodedPassword)).thenReturn(false);

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.authenticate(loginId, rawPassword));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED),
                    () -> verify(passwordEncoder, times(1)).matches(rawPassword, encodedPassword));
        }

        @Test
        @DisplayName("로그인 ID가 null이면 UNAUTHORIZED 예외가 발생한다.")
        void throwsException_whenLoginIdIsNull() {
            // arrange
            String loginId = null;
            String rawPassword = "Password1!";

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.authenticate(loginId, rawPassword));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }

        @Test
        @DisplayName("비밀번호가 null이면 UNAUTHORIZED 예외가 발생한다.")
        void throwsException_whenPasswordIsNull() {
            // arrange
            String loginId = "testuser1";
            String rawPassword = null;

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.authenticate(loginId, rawPassword));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("비밀번호 변경을 할 때,")
    class ChangePassword {

        @Test
        @DisplayName("유효한 새 비밀번호로 변경하면 암호화되어 저장된다.")
        void changesPassword_whenValidNewPassword() {
            // arrange
            String currentPassword = "Password1!";
            String newPassword = "NewPass123!";
            String encodedCurrentPassword = "$2a$10$encodedCurrent";
            String encodedNewPassword = "$2a$10$encodedNew";

            Member member = new Member(
                    new LoginId("testuser1"),
                    encodedCurrentPassword,
                    new MemberName("홍길동"),
                    new Email("test@example.com"),
                    new BirthDate("19990101"));

            when(passwordEncoder.matches(currentPassword, encodedCurrentPassword)).thenReturn(true);
            when(passwordEncoder.matches(newPassword, encodedCurrentPassword)).thenReturn(false);
            when(passwordEncoder.encode(newPassword)).thenReturn(encodedNewPassword);

            // act
            memberService.changePassword(member, currentPassword, newPassword);

            // assert
            assertAll(
                    () -> verify(passwordEncoder, times(1)).encode(newPassword),
                    () -> assertThat(member.getPassword()).isEqualTo(encodedNewPassword));
        }

        @Test
        @DisplayName("기존 비밀번호가 일치하지 않으면 BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenCurrentPasswordNotMatches() {
            // arrange
            String currentPassword = "WrongPassword!";
            String newPassword = "NewPass123!";
            String encodedCurrentPassword = "$2a$10$encodedCurrent";

            Member member = new Member(
                    new LoginId("testuser1"),
                    encodedCurrentPassword,
                    new MemberName("홍길동"),
                    new Email("test@example.com"),
                    new BirthDate("19990101"));

            when(passwordEncoder.matches(currentPassword, encodedCurrentPassword)).thenReturn(false);

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.changePassword(member, currentPassword, newPassword));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("기존 비밀번호"),
                    () -> verify(passwordEncoder, never()).encode(anyString()));
        }

        @Test
        @DisplayName("새 비밀번호가 현재 비밀번호와 동일하면 BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNewPasswordSameAsCurrent() {
            // arrange
            String currentPassword = "Password1!";
            String newPassword = "Password1!"; // 현재 비밀번호와 동일
            String encodedCurrentPassword = "$2a$10$encodedCurrent";

            Member member = new Member(
                    new LoginId("testuser1"),
                    encodedCurrentPassword,
                    new MemberName("홍길동"),
                    new Email("test@example.com"),
                    new BirthDate("19990101"));

            when(passwordEncoder.matches(currentPassword, encodedCurrentPassword)).thenReturn(true);
            when(passwordEncoder.matches(newPassword, encodedCurrentPassword)).thenReturn(true);

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.changePassword(member, currentPassword, newPassword));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("현재 비밀번호"),
                    () -> verify(passwordEncoder, never()).encode(anyString()));
        }

        @Test
        @DisplayName("새 비밀번호에 생년월일이 포함되면 BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNewPasswordContainsBirthDate() {
            // arrange
            String currentPassword = "Password1!";
            String newPassword = "Pass19990101!"; // 생년월일 포함
            String encodedCurrentPassword = "$2a$10$encodedCurrent";

            Member member = new Member(
                    new LoginId("testuser1"),
                    encodedCurrentPassword,
                    new MemberName("홍길동"),
                    new Email("test@example.com"),
                    new BirthDate("19990101"));

            when(passwordEncoder.matches(currentPassword, encodedCurrentPassword)).thenReturn(true);
            when(passwordEncoder.matches(newPassword, encodedCurrentPassword)).thenReturn(false);

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.changePassword(member, currentPassword, newPassword));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("생년월일"),
                    () -> verify(passwordEncoder, never()).encode(anyString()));
        }

        @Test
        @DisplayName("새 비밀번호가 유효성 검사에 실패하면 BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenNewPasswordInvalid() {
            // arrange
            String currentPassword = "Password1!";
            String newPassword = "short"; // 8자 미만
            String encodedCurrentPassword = "$2a$10$encodedCurrent";

            Member member = new Member(
                    new LoginId("testuser1"),
                    encodedCurrentPassword,
                    new MemberName("홍길동"),
                    new Email("test@example.com"),
                    new BirthDate("19990101"));

            when(passwordEncoder.matches(currentPassword, encodedCurrentPassword)).thenReturn(true);
            when(passwordEncoder.matches(newPassword, encodedCurrentPassword)).thenReturn(false);

            // act
            CoreException exception = assertThrows(CoreException.class,
                    () -> memberService.changePassword(member, currentPassword, newPassword));

            // assert
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> verify(passwordEncoder, never()).encode(anyString()));
        }
    }
}
