package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponIssueFacade;
import com.loopers.application.coupon.CouponIssueStatusInfo;
import com.loopers.config.WebMvcConfig;
import com.loopers.domain.auth.LdapAuthService;
import com.loopers.domain.coupon.MemberCouponStatus;
import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberService;
import com.loopers.interfaces.api.auth.AdminAuthInterceptor;
import com.loopers.interfaces.api.auth.LoginAdminArgumentResolver;
import com.loopers.interfaces.api.auth.LoginMemberArgumentResolver;
import com.loopers.interfaces.api.auth.MemberAuthInterceptor;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CouponIssueV1Controller.class)
@Import({WebMvcConfig.class, MemberAuthInterceptor.class, LoginMemberArgumentResolver.class,
        AdminAuthInterceptor.class, LoginAdminArgumentResolver.class})
@DisplayName("CouponIssueV1Controller 단위 테스트")
class CouponIssueV1ControllerTest {

    private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
    private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CouponIssueFacade couponIssueFacade;

    @MockBean
    private MemberService memberService;

    @MockBean
    private LdapAuthService ldapAuthService;

    @Nested
    @DisplayName("POST /api/v1/coupons/{couponId}/issue-request")
    class RequestIssue {

        @Test
        @DisplayName("인증된 사용자가 발급 요청하면 202 Accepted를 반환한다")
        void returns202_whenAuthenticated() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            CouponIssueStatusInfo info = new CouponIssueStatusInfo(MemberCouponStatus.REQUESTED, null);
            when(couponIssueFacade.requestIssue(any(Member.class), anyLong())).thenReturn(info);

            // when & then
            mockMvc.perform(post("/api/v1/coupons/1/issue-request")
                            .header(HEADER_LOGIN_ID, "testuser1")
                            .header(HEADER_LOGIN_PW, "Password1!"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                    .andExpect(jsonPath("$.data.failReason").doesNotExist());
        }

        @Test
        @DisplayName("인증 없이 요청하면 401을 반환한다")
        void returns401_whenNoAuth() throws Exception {
            // when & then
            mockMvc.perform(post("/api/v1/coupons/1/issue-request"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("이미 발급 요청한 쿠폰이면 409를 반환한다")
        void returns409_whenAlreadyRequested() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            when(couponIssueFacade.requestIssue(any(Member.class), anyLong()))
                    .thenThrow(new CoreException(ErrorType.CONFLICT, "이미 발급 요청한 쿠폰입니다."));

            // when & then
            mockMvc.perform(post("/api/v1/coupons/1/issue-request")
                            .header(HEADER_LOGIN_ID, "testuser1")
                            .header(HEADER_LOGIN_PW, "Password1!"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.meta.result").value("FAIL"))
                    .andExpect(jsonPath("$.meta.message").value("이미 발급 요청한 쿠폰입니다."));
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰이면 404를 반환한다")
        void returns404_whenCouponNotFound() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            when(couponIssueFacade.requestIssue(any(Member.class), anyLong()))
                    .thenThrow(new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));

            // when & then
            mockMvc.perform(post("/api/v1/coupons/1/issue-request")
                            .header(HEADER_LOGIN_ID, "testuser1")
                            .header(HEADER_LOGIN_PW, "Password1!"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("선착순 쿠폰이 아니면 400을 반환한다")
        void returns400_whenNotLimitedCoupon() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            when(couponIssueFacade.requestIssue(any(Member.class), anyLong()))
                    .thenThrow(new CoreException(ErrorType.BAD_REQUEST, "선착순 쿠폰이 아닙니다."));

            // when & then
            mockMvc.perform(post("/api/v1/coupons/1/issue-request")
                            .header(HEADER_LOGIN_ID, "testuser1")
                            .header(HEADER_LOGIN_PW, "Password1!"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/coupons/{couponId}/issue-status")
    class GetIssueStatus {

        @Test
        @DisplayName("REQUESTED 상태를 반환한다")
        void returnsRequestedStatus() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            CouponIssueStatusInfo info = new CouponIssueStatusInfo(MemberCouponStatus.REQUESTED, null);
            when(couponIssueFacade.getIssueStatus(any(Member.class), anyLong())).thenReturn(info);

            // when & then
            mockMvc.perform(get("/api/v1/coupons/1/issue-status")
                            .header(HEADER_LOGIN_ID, "testuser1")
                            .header(HEADER_LOGIN_PW, "Password1!"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("REQUESTED"));
        }

        @Test
        @DisplayName("AVAILABLE 상태를 반환한다")
        void returnsAvailableStatus() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            CouponIssueStatusInfo info = new CouponIssueStatusInfo(MemberCouponStatus.AVAILABLE, null);
            when(couponIssueFacade.getIssueStatus(any(Member.class), anyLong())).thenReturn(info);

            // when & then
            mockMvc.perform(get("/api/v1/coupons/1/issue-status")
                            .header(HEADER_LOGIN_ID, "testuser1")
                            .header(HEADER_LOGIN_PW, "Password1!"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("AVAILABLE"));
        }

        @Test
        @DisplayName("FAILED 상태와 실패 사유를 반환한다")
        void returnsFailedStatusWithReason() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            CouponIssueStatusInfo info = new CouponIssueStatusInfo(MemberCouponStatus.FAILED, "수량 초과");
            when(couponIssueFacade.getIssueStatus(any(Member.class), anyLong())).thenReturn(info);

            // when & then
            mockMvc.perform(get("/api/v1/coupons/1/issue-status")
                            .header(HEADER_LOGIN_ID, "testuser1")
                            .header(HEADER_LOGIN_PW, "Password1!"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("FAILED"))
                    .andExpect(jsonPath("$.data.failReason").value("수량 초과"));
        }

        @Test
        @DisplayName("발급 요청이 없으면 404를 반환한다")
        void returns404_whenNotRequested() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            when(couponIssueFacade.getIssueStatus(any(Member.class), anyLong()))
                    .thenThrow(new CoreException(ErrorType.NOT_FOUND, "발급 요청을 찾을 수 없습니다."));

            // when & then
            mockMvc.perform(get("/api/v1/coupons/1/issue-status")
                            .header(HEADER_LOGIN_ID, "testuser1")
                            .header(HEADER_LOGIN_PW, "Password1!"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("인증 없이 조회하면 401을 반환한다")
        void returns401_whenNoAuth() throws Exception {
            // when & then
            mockMvc.perform(get("/api/v1/coupons/1/issue-status"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
