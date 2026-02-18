package com.loopers.application.member;

import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberService;
import com.loopers.domain.member.SignupCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
  [단위 테스트 - Facade with Mock]
  대상 : MemberFacade
  사용 라이브러리 : JUnit 5, AssertJ, Mockito
  테스트 더블 : Mock(MemberService)

  어노테이션 설명:
  - @ExtendWith(MockitoExtension.class): Mockito-JUnit 5 통합
  - @Mock: MemberService를 Mock 객체로 생성
  - @InjectMocks: Mock을 MemberFacade에 주입

  Mockito 메서드 설명:
  - mock(Class): 특정 클래스의 Mock 객체 동적 생성
  - when().thenReturn(): Stub - 메서드 호출 시 반환값 지정
  - verify(): Mock - 메서드 호출 여부/횟수 검증

  특징:
  - Spring Context 불필요 → 빠른 실행
  - Docker/DB 불필요
  - Facade가 Service를 올바르게 호출하는지 검증
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("MemberFacade 단위 테스트")
class MemberFacadeTest {

    @Mock
    private MemberService memberService;

    @InjectMocks
    private MemberFacade memberFacade;

    @Nested
    @DisplayName("회원가입을 할 때,")
    class Signup {

        @Test
        @DisplayName("MemberService를 호출하고 MemberInfo로 변환하여 반환한다.")
        void callsServiceAndReturnsMemberInfo() {
            // arrange
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");

            // Stub - Member Mock 객체 설정
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(mockMember.getLoginId()).thenReturn("testuser1");
            when(mockMember.getName()).thenReturn("홍길동");
            when(mockMember.getEmail()).thenReturn("test@example.com");
            when(mockMember.getBirthDate()).thenReturn("19990101");

            when(memberService.signup(any(SignupCommand.class))).thenReturn(mockMember);

            // act
            MemberInfo info = memberFacade.signup(command);

            // assert
            assertAll(
                    () -> assertThat(info.id()).isEqualTo(1L),
                    () -> assertThat(info.loginId()).isEqualTo("testuser1"),
                    () -> assertThat(info.name()).isEqualTo("홍길동"),
                    () -> assertThat(info.email()).isEqualTo("test@example.com"),
                    () -> assertThat(info.birthDate()).isEqualTo("19990101"),
                    () -> verify(memberService, times(1)).signup(command));
        }

        @Test
        @DisplayName("전달받은 SignupCommand를 그대로 MemberService에 전달한다.")
        void passesCommandToService() {
            // arrange
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");

            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(mockMember.getLoginId()).thenReturn("testuser1");
            when(mockMember.getName()).thenReturn("홍길동");
            when(mockMember.getEmail()).thenReturn("test@example.com");
            when(mockMember.getBirthDate()).thenReturn("19990101");

            when(memberService.signup(command)).thenReturn(mockMember);

            // act
            memberFacade.signup(command);

            // assert - 정확히 동일한 command 객체가 전달되었는지 검증
            verify(memberService).signup(command);
        }

        @Test
        @DisplayName("MemberService에서 중복 ID 예외 발생 시 그대로 전파한다.")
        void throwsException_whenDuplicateLoginId() {
            // given
            SignupCommand command = new SignupCommand(
                    "duplicateId",
                    "Password1!",
                    "홍길동",
                    "test@example.com",
                    "19990101");

            when(memberService.signup(command))
                    .thenThrow(new CoreException(ErrorType.CONFLICT, "이미 사용 중인 로그인 ID입니다."));

            // when & then
            assertThatThrownBy(() -> memberFacade.signup(command))
                    .isInstanceOf(CoreException.class)
                    .satisfies(exception -> {
                        CoreException coreException = (CoreException) exception;
                        assertThat(coreException.getErrorType()).isEqualTo(ErrorType.CONFLICT);
                    });
        }

        @Test
        @DisplayName("MemberService에서 이메일 중복 예외 발생 시 그대로 전파한다.")
        void throwsException_whenDuplicateEmail() {
            // given
            SignupCommand command = new SignupCommand(
                    "testuser1",
                    "Password1!",
                    "홍길동",
                    "duplicate@example.com",
                    "19990101");

            when(memberService.signup(command))
                    .thenThrow(new CoreException(ErrorType.CONFLICT, "이미 사용 중인 이메일입니다."));

            // when & then
            assertThatThrownBy(() -> memberFacade.signup(command))
                    .isInstanceOf(CoreException.class)
                    .satisfies(exception -> {
                        CoreException coreException = (CoreException) exception;
                        assertThat(coreException.getErrorType()).isEqualTo(ErrorType.CONFLICT);
                    });
        }
    }

    @Nested
    @DisplayName("내 정보 조회를 할 때,")
    class GetMyInfo {

        @Test
        @DisplayName("인증된 Member로부터 마스킹된 이름으로 MyInfo를 반환한다.")
        void returnsMaskedMyInfo() {
            // arrange
            Member mockMember = mock(Member.class);
            when(mockMember.getLoginId()).thenReturn("testuser1");
            when(mockMember.getName()).thenReturn("홍길동");
            when(mockMember.getEmail()).thenReturn("test@example.com");
            when(mockMember.getBirthDate()).thenReturn("19990101");

            // act
            MyInfo info = memberFacade.getMyInfo(mockMember);

            // assert
            assertAll(
                    () -> assertThat(info.loginId()).isEqualTo("testuser1"),
                    () -> assertThat(info.name()).isEqualTo("홍길*"), // 마스킹된 이름
                    () -> assertThat(info.email()).isEqualTo("test@example.com"),
                    () -> assertThat(info.birthDate()).isEqualTo("19990101"));
        }

        @Test
        @DisplayName("2글자 이름인 경우 마지막 글자가 마스킹된다.")
        void masksLastCharacter_when2CharacterName() {
            // arrange
            Member mockMember = mock(Member.class);
            when(mockMember.getLoginId()).thenReturn("testuser1");
            when(mockMember.getName()).thenReturn("홍길");
            when(mockMember.getEmail()).thenReturn("test@example.com");
            when(mockMember.getBirthDate()).thenReturn("19990101");

            // act
            MyInfo info = memberFacade.getMyInfo(mockMember);

            // assert
            assertThat(info.name()).isEqualTo("홍*");
        }
    }

    @Nested
    @DisplayName("비밀번호 변경을 할 때,")
    class ChangePassword {

        @Test
        @DisplayName("MemberService를 호출하여 비밀번호를 변경한다.")
        void callsServiceToChangePassword() {
            // arrange
            String currentPassword = "Password1!";
            String newPassword = "NewPass123!";

            Member mockMember = mock(Member.class);

            // act
            memberFacade.changePassword(mockMember, currentPassword, newPassword);

            // assert
            verify(memberService, times(1)).changePassword(mockMember, currentPassword, newPassword);
        }

        @Test
        @DisplayName("현재 비밀번호가 일치하지 않으면 예외가 전파된다.")
        void throwsException_whenCurrentPasswordMismatch() {
            // given
            String wrongCurrentPassword = "WrongCurrent1!";
            String newPassword = "NewPass123!";

            Member mockMember = mock(Member.class);
            doThrow(new CoreException(ErrorType.BAD_REQUEST, "기존 비밀번호가 일치하지 않습니다."))
                    .when(memberService).changePassword(mockMember, wrongCurrentPassword, newPassword);

            // when & then
            assertThatThrownBy(
                    () -> memberFacade.changePassword(mockMember, wrongCurrentPassword, newPassword))
                    .isInstanceOf(CoreException.class)
                    .satisfies(exception -> {
                        CoreException coreException = (CoreException) exception;
                        assertThat(coreException.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                    });
        }

        @Test
        @DisplayName("새 비밀번호가 유효하지 않으면 예외가 전파된다.")
        void throwsException_whenNewPasswordInvalid() {
            // given
            String currentPassword = "Password1!";
            String invalidNewPassword = "short";

            Member mockMember = mock(Member.class);
            doThrow(new CoreException(ErrorType.BAD_REQUEST, "비밀번호는 8자 이상 16자 이하여야 합니다."))
                    .when(memberService).changePassword(mockMember, currentPassword, invalidNewPassword);

            // when & then
            assertThatThrownBy(
                    () -> memberFacade.changePassword(mockMember, currentPassword, invalidNewPassword))
                    .isInstanceOf(CoreException.class)
                    .satisfies(exception -> {
                        CoreException coreException = (CoreException) exception;
                        assertThat(coreException.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                    });
        }
    }
}
