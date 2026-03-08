package com.loopers.interfaces.api.member.dto;

import com.loopers.application.member.MemberInfo;
import com.loopers.application.member.MyInfo;
import com.loopers.domain.member.SignupCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * [단위 테스트 - DTO]
 *
 * 테스트 대상: MemberV1Dto (Interface Layer DTO)
 * 테스트 유형: 순수 단위 테스트 (Pure Unit Test)
 * 외부 의존성: 없음
 *
 * 사용 라이브러리:
 * - JUnit 5 (org.junit.jupiter)
 * - AssertJ (org.assertj.core.api)
 *
 * 특징:
 * - Spring Context 불필요 → 빠른 실행
 * - Docker/DB 불필요
 * - DTO 변환 로직 검증
 */
@DisplayName("MemberV1Dto")
class MemberV1DtoTest {

    @Nested
    @DisplayName("SignupRequest")
    class SignupRequestTest {

        @Test
        @DisplayName("모든 필드를 포함하여 생성된다.")
        void createsRequest_withAllFields() {
            // given
            String loginId = "testuser1";
            String password = "Password1!";
            String name = "홍길동";
            String email = "test@example.com";
            String birthDate = "19990101";

            // when
            MemberV1Dto.SignupRequest request = new MemberV1Dto.SignupRequest(
                loginId, password, name, email, birthDate
            );

            // then
            assertAll(
                () -> assertThat(request.loginId()).isEqualTo("testuser1"),
                () -> assertThat(request.password()).isEqualTo("Password1!"),
                () -> assertThat(request.name()).isEqualTo("홍길동"),
                () -> assertThat(request.email()).isEqualTo("test@example.com"),
                () -> assertThat(request.birthDate()).isEqualTo("19990101")
            );
        }

        @Test
        @DisplayName("toCommand()로 SignupCommand로 변환된다.")
        void convertsToCommand() {
            // given
            MemberV1Dto.SignupRequest request = new MemberV1Dto.SignupRequest(
                "testuser1",
                "Password1!",
                "홍길동",
                "test@example.com",
                "19990101"
            );

            // when
            SignupCommand command = request.toCommand();

            // then
            assertAll(
                () -> assertThat(command.loginId()).isEqualTo("testuser1"),
                () -> assertThat(command.password()).isEqualTo("Password1!"),
                () -> assertThat(command.name()).isEqualTo("홍길동"),
                () -> assertThat(command.email()).isEqualTo("test@example.com"),
                () -> assertThat(command.birthDate()).isEqualTo("19990101")
            );
        }
    }

    @Nested
    @DisplayName("SignupResponse")
    class SignupResponseTest {

        @Test
        @DisplayName("MemberInfo로부터 생성하면 memberId가 올바르게 매핑된다.")
        void createsFromMemberInfo() {
            // given
            MemberInfo info = new MemberInfo(
                1L,
                "testuser1",
                "홍길동",
                "test@example.com",
                "19990101"
            );

            // when
            MemberV1Dto.SignupResponse response = MemberV1Dto.SignupResponse.from(info);

            // then
            assertThat(response.memberId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("직접 생성할 수 있다.")
        void createsDirectly() {
            // given
            Long memberId = 1L;

            // when
            MemberV1Dto.SignupResponse response = new MemberV1Dto.SignupResponse(memberId);

            // then
            assertThat(response.memberId()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("MyInfoResponse")
    class MyInfoResponseTest {

        @Test
        @DisplayName("MyInfo로부터 생성하면 모든 필드가 올바르게 매핑된다.")
        void createsFromMyInfo() {
            // given
            MyInfo info = new MyInfo(
                "testuser1",
                "홍길*",
                "test@example.com",
                "19990101",
                5000
            );

            // when
            MemberV1Dto.MyInfoResponse response = MemberV1Dto.MyInfoResponse.from(info);

            // then
            assertAll(
                () -> assertThat(response.loginId()).isEqualTo("testuser1"),
                () -> assertThat(response.name()).isEqualTo("홍길*"),
                () -> assertThat(response.email()).isEqualTo("test@example.com"),
                () -> assertThat(response.birthDate()).isEqualTo("19990101"),
                () -> assertThat(response.pointBalance()).isEqualTo(5000)
            );
        }

        @Test
        @DisplayName("직접 생성할 수 있다.")
        void createsDirectly() {
            // when
            MemberV1Dto.MyInfoResponse response = new MemberV1Dto.MyInfoResponse(
                "testuser1",
                "홍길*",
                "test@example.com",
                "19990101",
                1000
            );

            // then
            assertAll(
                () -> assertThat(response.loginId()).isEqualTo("testuser1"),
                () -> assertThat(response.name()).isEqualTo("홍길*"),
                () -> assertThat(response.email()).isEqualTo("test@example.com"),
                () -> assertThat(response.birthDate()).isEqualTo("19990101"),
                () -> assertThat(response.pointBalance()).isEqualTo(1000)
            );
        }
    }

    @Nested
    @DisplayName("ChangePasswordRequest")
    class ChangePasswordRequestTest {

        @Test
        @DisplayName("현재 비밀번호와 새 비밀번호로 생성된다.")
        void createsRequest_withPasswords() {
            // given
            String currentPassword = "Password1!";
            String newPassword = "NewPass123!";

            // when
            MemberV1Dto.ChangePasswordRequest request = new MemberV1Dto.ChangePasswordRequest(
                currentPassword, newPassword
            );

            // then
            assertAll(
                () -> assertThat(request.currentPassword()).isEqualTo("Password1!"),
                () -> assertThat(request.newPassword()).isEqualTo("NewPass123!")
            );
        }
    }
}
