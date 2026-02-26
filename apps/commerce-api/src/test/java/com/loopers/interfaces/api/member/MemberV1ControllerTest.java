package com.loopers.interfaces.api.member;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.member.MemberFacade;
import com.loopers.application.member.MemberInfo;
import com.loopers.application.member.MyInfo;
import com.loopers.config.WebMvcConfig;
import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberService;
import com.loopers.domain.auth.LdapAuthService;
import com.loopers.interfaces.api.auth.AdminAuthInterceptor;
import com.loopers.interfaces.api.auth.LoginAdminArgumentResolver;
import com.loopers.interfaces.api.auth.LoginMemberArgumentResolver;
import com.loopers.interfaces.api.auth.MemberAuthInterceptor;
import com.loopers.interfaces.api.member.dto.MemberV1Dto;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [단위 테스트 - Controller with MockMvc]
 *
 * 테스트 대상: MemberV1Controller (Interfaces Layer)
 * 테스트 유형: 슬라이스 테스트 (Controller만 로드)
 * 테스트 더블: MockBean (MemberFacade, MemberService)
 *
 * 사용 라이브러리:
 * - JUnit 5 (org.junit.jupiter)
 * - Spring Test (org.springframework.test.web.servlet)
 * - Mockito (org.mockito)
 * - Spring Boot Test (org.springframework.boot.test)
 *
 * 어노테이션 설명:
 * - @WebMvcTest(Controller.class): 지정한 Controller만 Spring Context에 로드
 *   (org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest)
 *   → Web Layer만 테스트 (Controller, ControllerAdvice, Filter 등)
 *   → Service, Repository 등은 로드하지 않음
 *   → MockMvc를 자동 구성
 *
 * - @Import: WebMvcConfig, MemberAuthInterceptor, LoginMemberArgumentResolver를 로드
 *   → @WebMvcTest는 @Configuration을 자동 로드하지 않으므로 명시적으로 Import
 *   → Interceptor가 MemberService에 의존하므로 MockBean으로 등록
 *
 * - @MockBean: Spring Context에 Mock 빈을 등록
 *   (org.springframework.boot.test.mock.mockito.MockBean)
 *   → @Mock과 달리 Spring ApplicationContext에 빈으로 등록됨
 *   → Controller가 의존하는 빈을 Mock으로 대체
 *
 * - @Autowired MockMvc: HTTP 요청을 시뮬레이션하는 테스트 도구
 *   → perform(): 요청 실행
 *   → andExpect(): 응답 검증 (status, jsonPath, header 등)
 *
 * 특징:
 * - 전체 Spring Context 대신 Web Layer만 로드 → 빠른 실행
 * - 실제 HTTP 요청/응답 시뮬레이션
 * - Docker/DB 불필요
 */
@WebMvcTest(MemberV1Controller.class)
@Import({WebMvcConfig.class, MemberAuthInterceptor.class, LoginMemberArgumentResolver.class, AdminAuthInterceptor.class, LoginAdminArgumentResolver.class})
@DisplayName("MemberV1Controller 단위 테스트")
class MemberV1ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MemberFacade memberFacade;

    @MockBean
    private MemberService memberService;

    @MockBean
    private LdapAuthService ldapAuthService;

    @Nested
    @DisplayName("POST /api/v1/members/signup")
    class Signup {

        @Test
        @DisplayName("성공 시 200 OK와 회원 ID를 반환한다.")
        void returnsOkWithMemberId_whenSuccess() throws Exception {
            // arrange
            MemberV1Dto.SignupRequest request = new MemberV1Dto.SignupRequest(
                "testuser1",
                "Password1!",
                "홍길동",
                "test@example.com",
                "19990101"
            );

            MemberInfo mockInfo = new MemberInfo(
                1L, "testuser1", "홍길동", "test@example.com", "19990101"
            );
            when(memberFacade.signup(any())).thenReturn(mockInfo);

            // act & assert
            mockMvc.perform(post("/api/v1/members/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.memberId").value(1));

            verify(memberFacade, times(1)).signup(any());
        }

        @Test
        @DisplayName("성공 시 응답 헤더에 로그인 정보가 포함된다.")
        void includesLoginInfoInHeaders_whenSuccess() throws Exception {
            // arrange
            MemberV1Dto.SignupRequest request = new MemberV1Dto.SignupRequest(
                "testuser1",
                "Password1!",
                "홍길동",
                "test@example.com",
                "19990101"
            );

            MemberInfo mockInfo = new MemberInfo(
                1L, "testuser1", "홍길동", "test@example.com", "19990101"
            );
            when(memberFacade.signup(any())).thenReturn(mockInfo);

            // act & assert
            mockMvc.perform(post("/api/v1/members/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Loopers-LoginId", "testuser1"))
                .andExpect(header().string("X-Loopers-LoginPw", "Password1!"));
        }

        @Test
        @DisplayName("Facade에서 BAD_REQUEST 예외 발생 시 400을 반환한다.")
        void returnsBadRequest_whenFacadeThrowsBadRequest() throws Exception {
            // arrange
            MemberV1Dto.SignupRequest request = new MemberV1Dto.SignupRequest(
                "ab",
                "Password1!",
                "홍길동",
                "test@example.com",
                "19990101"
            );

            when(memberFacade.signup(any())).thenThrow(
                new CoreException(ErrorType.BAD_REQUEST, "로그인 아이디는 영문, 숫자만 사용하여 4~20자로 입력해주세요.")
            );

            // act & assert
            mockMvc.perform(post("/api/v1/members/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.result").value("FAIL"))
                .andExpect(jsonPath("$.meta.message").value("로그인 아이디는 영문, 숫자만 사용하여 4~20자로 입력해주세요."));
        }

        @Test
        @DisplayName("Facade에서 CONFLICT 예외 발생 시 409를 반환한다.")
        void returnsConflict_whenFacadeThrowsConflict() throws Exception {
            // arrange
            MemberV1Dto.SignupRequest request = new MemberV1Dto.SignupRequest(
                "testuser1",
                "Password1!",
                "홍길동",
                "test@example.com",
                "19990101"
            );

            when(memberFacade.signup(any())).thenThrow(
                new CoreException(ErrorType.CONFLICT, "이미 사용 중인 로그인 아이디입니다.")
            );

            // act & assert
            mockMvc.perform(post("/api/v1/members/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.result").value("FAIL"))
                .andExpect(jsonPath("$.meta.message").value("이미 사용 중인 로그인 아이디입니다."));
        }

        @Test
        @DisplayName("요청 본문이 없으면 400을 반환한다.")
        void returnsBadRequest_whenNoRequestBody() throws Exception {
            // act & assert
            mockMvc.perform(post("/api/v1/members/signup")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("잘못된 JSON 형식이면 400을 반환한다.")
        void returnsBadRequest_whenInvalidJson() throws Exception {
            // act & assert
            mockMvc.perform(post("/api/v1/members/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{invalid json}"))
                .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/members/me")
    class GetMyInfo {

        private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
        private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

        @Test
        @DisplayName("인증 성공 시 200 OK와 마스킹된 내 정보를 반환한다.")
        void returnsOkWithMaskedInfo_whenAuthSuccess() throws Exception {
            // arrange
            Member mockMember = mock(Member.class);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            MyInfo mockInfo = new MyInfo(
                "testuser1",
                "홍길*",  // 마스킹된 이름
                "test@example.com",
                "19990101",
                5000
            );
            when(memberFacade.getMyInfo(any(Member.class))).thenReturn(mockInfo);

            // act & assert
            mockMvc.perform(get("/api/v1/members/me")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.loginId").value("testuser1"))
                .andExpect(jsonPath("$.data.name").value("홍길*"))
                .andExpect(jsonPath("$.data.email").value("test@example.com"))
                .andExpect(jsonPath("$.data.birthDate").value("19990101"));

            verify(memberFacade, times(1)).getMyInfo(any(Member.class));
        }

        @Test
        @DisplayName("인증 헤더가 없으면 401을 반환한다.")
        void returnsUnauthorized_whenNoAuthHeaders() throws Exception {
            // act & assert
            mockMvc.perform(get("/api/v1/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.meta.result").value("FAIL"));
        }

        @Test
        @DisplayName("로그인 ID 헤더만 없으면 401을 반환한다.")
        void returnsUnauthorized_whenNoLoginIdHeader() throws Exception {
            // act & assert
            mockMvc.perform(get("/api/v1/members/me")
                    .header(HEADER_LOGIN_PW, "Password1!"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("비밀번호 헤더만 없으면 401을 반환한다.")
        void returnsUnauthorized_whenNoPasswordHeader() throws Exception {
            // act & assert
            mockMvc.perform(get("/api/v1/members/me")
                    .header(HEADER_LOGIN_ID, "testuser1"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("인증 실패 시 401을 반환한다.")
        void returnsUnauthorized_whenAuthenticationFails() throws Exception {
            // arrange
            when(memberService.authenticate("testuser1", "WrongPassword!")).thenThrow(
                new CoreException(ErrorType.UNAUTHORIZED, "인증에 실패했습니다.")
            );

            // act & assert
            mockMvc.perform(get("/api/v1/members/me")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "WrongPassword!"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.meta.result").value("FAIL"))
                .andExpect(jsonPath("$.meta.message").value("인증에 실패했습니다."));
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/members/me/password")
    class ChangePassword {

        private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
        private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

        @Test
        @DisplayName("성공 시 200 OK와 응답 헤더에 새 비밀번호를 반환한다.")
        void returnsOkWithNewPasswordInHeader_whenSuccess() throws Exception {
            // arrange
            Member mockMember = mock(Member.class);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            MemberV1Dto.ChangePasswordRequest request = new MemberV1Dto.ChangePasswordRequest(
                "Password1!",
                "NewPass123!"
            );

            // act & assert
            mockMvc.perform(patch("/api/v1/members/me/password")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(header().string(HEADER_LOGIN_PW, "NewPass123!"));

            verify(memberFacade, times(1)).changePassword(any(Member.class), eq("Password1!"), eq("NewPass123!"));
        }

        @Test
        @DisplayName("인증 헤더가 없으면 401을 반환한다.")
        void returnsUnauthorized_whenNoAuthHeaders() throws Exception {
            // arrange
            MemberV1Dto.ChangePasswordRequest request = new MemberV1Dto.ChangePasswordRequest(
                "Password1!",
                "NewPass123!"
            );

            // act & assert
            mockMvc.perform(patch("/api/v1/members/me/password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("인증 실패 시 401을 반환한다.")
        void returnsUnauthorized_whenAuthenticationFails() throws Exception {
            // arrange
            when(memberService.authenticate("testuser1", "WrongPassword!")).thenThrow(
                new CoreException(ErrorType.UNAUTHORIZED, "인증에 실패했습니다.")
            );

            MemberV1Dto.ChangePasswordRequest request = new MemberV1Dto.ChangePasswordRequest(
                "Password1!",
                "NewPass123!"
            );

            // act & assert
            mockMvc.perform(patch("/api/v1/members/me/password")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "WrongPassword!")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.meta.result").value("FAIL"));
        }

        @Test
        @DisplayName("Facade에서 BAD_REQUEST 예외 발생 시 400을 반환한다.")
        void returnsBadRequest_whenFacadeThrowsBadRequest() throws Exception {
            // arrange
            Member mockMember = mock(Member.class);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            MemberV1Dto.ChangePasswordRequest request = new MemberV1Dto.ChangePasswordRequest(
                "WrongPassword!",
                "NewPass123!"
            );

            org.mockito.Mockito.doThrow(new CoreException(ErrorType.BAD_REQUEST, "기존 비밀번호가 일치하지 않습니다."))
                .when(memberFacade).changePassword(any(Member.class), anyString(), anyString());

            // act & assert
            mockMvc.perform(patch("/api/v1/members/me/password")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.result").value("FAIL"))
                .andExpect(jsonPath("$.meta.message").value("기존 비밀번호가 일치하지 않습니다."));
        }

        @Test
        @DisplayName("요청 본문이 없으면 400을 반환한다.")
        void returnsBadRequest_whenNoRequestBody() throws Exception {
            // arrange
            Member mockMember = mock(Member.class);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            // act & assert
            mockMvc.perform(patch("/api/v1/members/me/password")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!")
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        }
    }
}
