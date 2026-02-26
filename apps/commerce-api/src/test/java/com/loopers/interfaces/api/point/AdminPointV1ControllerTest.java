package com.loopers.interfaces.api.point;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.point.AdminPointFacade;
import com.loopers.config.WebMvcConfig;
import com.loopers.domain.auth.LdapAuthService;
import com.loopers.domain.member.MemberService;
import com.loopers.interfaces.api.auth.AdminAuthInterceptor;
import com.loopers.interfaces.api.auth.LoginAdminArgumentResolver;
import com.loopers.interfaces.api.auth.LoginMemberArgumentResolver;
import com.loopers.interfaces.api.auth.MemberAuthInterceptor;
import com.loopers.interfaces.api.point.dto.PointV1Dto;
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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [단위 테스트 - Controller with MockMvc]
 *
 * 테스트 대상: AdminPointV1Controller (Interfaces Layer)
 * 테스트 유형: 슬라이스 테스트 (Controller만 로드)
 * 테스트 더블: MockBean (AdminPointFacade, MemberService, LdapAuthService)
 *
 * 특징:
 * - 전체 Spring Context 대신 Web Layer만 로드 -> 빠른 실행
 * - 실제 HTTP 요청/응답 시뮬레이션
 * - Docker/DB 불필요
 */
@WebMvcTest(AdminPointV1Controller.class)
@Import({WebMvcConfig.class, MemberAuthInterceptor.class, LoginMemberArgumentResolver.class, AdminAuthInterceptor.class, LoginAdminArgumentResolver.class})
@DisplayName("AdminPointV1Controller 단위 테스트")
class AdminPointV1ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AdminPointFacade adminPointFacade;

    @MockBean
    private MemberService memberService;

    @MockBean
    private LdapAuthService ldapAuthService;

    private static final String HEADER_LDAP = "X-Loopers-Ldap";

    @Nested
    @DisplayName("POST /api-admin/v1/points")
    class ChargePoint {

        @Test
        @DisplayName("성공 시 200 OK를 반환한다.")
        void returnsOk_whenSuccess() throws Exception {
            // given
            PointV1Dto.ChargePointRequest request = new PointV1Dto.ChargePointRequest(
                    1L, 1000, "관리자 충전"
            );

            com.loopers.domain.auth.Admin admin = new com.loopers.domain.auth.Admin("loopers.admin");
            org.mockito.Mockito.when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            // when & then
            mockMvc.perform(post("/api-admin/v1/points")
                            .header(HEADER_LDAP, "loopers.admin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.result").value("SUCCESS"));

            verify(adminPointFacade, times(1)).chargePoint(1L, 1000, "관리자 충전");
        }

        @Test
        @DisplayName("충전 금액이 0 이하이면 400을 반환한다.")
        void returnsBadRequest_whenAmountIsZeroOrNegative() throws Exception {
            // given
            PointV1Dto.ChargePointRequest request = new PointV1Dto.ChargePointRequest(
                    1L, 0, "관리자 충전"
            );

            com.loopers.domain.auth.Admin admin = new com.loopers.domain.auth.Admin("loopers.admin");
            org.mockito.Mockito.when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            // when & then
            mockMvc.perform(post("/api-admin/v1/points")
                            .header(HEADER_LDAP, "loopers.admin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.result").value("FAIL"))
                    .andExpect(jsonPath("$.meta.message").value("충전 금액은 0보다 커야 합니다."));

            verify(adminPointFacade, never()).chargePoint(anyLong(), anyInt(), anyString());
        }

        @Test
        @DisplayName("포인트 정보가 없으면 404를 반환한다.")
        void returnsNotFound_whenPointNotExists() throws Exception {
            // given
            PointV1Dto.ChargePointRequest request = new PointV1Dto.ChargePointRequest(
                    999L, 1000, "관리자 충전"
            );

            com.loopers.domain.auth.Admin admin = new com.loopers.domain.auth.Admin("loopers.admin");
            org.mockito.Mockito.when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            doThrow(new CoreException(ErrorType.NOT_FOUND, "포인트 정보를 찾을 수 없습니다."))
                    .when(adminPointFacade).chargePoint(999L, 1000, "관리자 충전");

            // when & then
            mockMvc.perform(post("/api-admin/v1/points")
                            .header(HEADER_LDAP, "loopers.admin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.meta.result").value("FAIL"))
                    .andExpect(jsonPath("$.meta.message").value("포인트 정보를 찾을 수 없습니다."));
        }

        @Test
        @DisplayName("인증 헤더가 없으면 401을 반환한다.")
        void returnsUnauthorized_whenNoAuthHeader() throws Exception {
            // given
            PointV1Dto.ChargePointRequest request = new PointV1Dto.ChargePointRequest(
                    1L, 1000, "관리자 충전"
            );

            // when & then
            mockMvc.perform(post("/api-admin/v1/points")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.meta.result").value("FAIL"));
        }
    }
}
