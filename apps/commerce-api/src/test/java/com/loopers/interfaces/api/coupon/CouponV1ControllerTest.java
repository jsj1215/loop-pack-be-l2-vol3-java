package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.CouponFacade;
import com.loopers.application.coupon.CouponInfo;
import com.loopers.application.coupon.MyCouponInfo;
import com.loopers.config.WebMvcConfig;
import com.loopers.domain.auth.LdapAuthService;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.DiscountType;
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

import java.time.ZonedDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CouponV1Controller.class)
@Import({WebMvcConfig.class, MemberAuthInterceptor.class, LoginMemberArgumentResolver.class, AdminAuthInterceptor.class, LoginAdminArgumentResolver.class})
@DisplayName("CouponV1Controller 단위 테스트")
class CouponV1ControllerTest {

    private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
    private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CouponFacade couponFacade;

    @MockBean
    private MemberService memberService;

    @MockBean
    private LdapAuthService ldapAuthService;

    @Nested
    @DisplayName("GET /api/v1/coupons")
    class GetAvailableCoupons {

        @Test
        @DisplayName("인증 없이도 200 OK와 쿠폰 목록을 반환한다.")
        void returnsOk_withoutAuth() throws Exception {
            // given
            ZonedDateTime now = ZonedDateTime.now();
            CouponInfo couponInfo = new CouponInfo(
                    1L, "신규 가입 쿠폰", CouponScope.CART, DiscountType.FIXED_AMOUNT,
                    5000, 10000, 0, now.minusDays(1), now.plusDays(30), 90);

            when(couponFacade.getAvailableCoupons()).thenReturn(List.of(couponInfo));

            // when & then
            mockMvc.perform(get("/api/v1/coupons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("신규 가입 쿠폰"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/coupons/{couponId}/download")
    class DownloadCoupon {

        @Test
        @DisplayName("인증된 사용자가 쿠폰을 다운로드하면 200 OK를 반환한다.")
        void returnsOk_whenAuthenticated() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            ZonedDateTime now = ZonedDateTime.now();
            CouponInfo couponInfo = new CouponInfo(
                    1L, "신규 가입 쿠폰", CouponScope.CART, DiscountType.FIXED_AMOUNT,
                    5000, 10000, 0, now.minusDays(1), now.plusDays(30), 89);

            when(couponFacade.downloadCoupon(any(Member.class), anyLong())).thenReturn(couponInfo);

            // when & then
            mockMvc.perform(post("/api/v1/coupons/1/download")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("신규 가입 쿠폰"));
        }

        @Test
        @DisplayName("인증 없이 쿠폰을 다운로드하면 401을 반환한다.")
        void returnsUnauthorized_whenNoAuth() throws Exception {
            // when & then
            mockMvc.perform(post("/api/v1/coupons/1/download"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("이미 다운로드한 쿠폰이면 409를 반환한다.")
        void returnsConflict_whenAlreadyDownloaded() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            when(couponFacade.downloadCoupon(any(Member.class), anyLong()))
                .thenThrow(new CoreException(ErrorType.CONFLICT, "이미 다운로드한 쿠폰입니다."));

            // when & then
            mockMvc.perform(post("/api/v1/coupons/1/download")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.result").value("FAIL"))
                .andExpect(jsonPath("$.meta.message").value("이미 다운로드한 쿠폰입니다."));
        }

        @Test
        @DisplayName("발급 불가능한 쿠폰이면 400을 반환한다.")
        void returnsBadRequest_whenNotIssuable() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            when(couponFacade.downloadCoupon(any(Member.class), anyLong()))
                .thenThrow(new CoreException(ErrorType.BAD_REQUEST, "쿠폰 발급이 불가합니다."));

            // when & then
            mockMvc.perform(post("/api/v1/coupons/1/download")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.result").value("FAIL"))
                .andExpect(jsonPath("$.meta.message").value("쿠폰 발급이 불가합니다."));
        }

        @Test
        @DisplayName("쿠폰이 존재하지 않으면 404를 반환한다.")
        void returnsNotFound_whenCouponNotExists() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            when(couponFacade.downloadCoupon(any(Member.class), anyLong()))
                .thenThrow(new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));

            // when & then
            mockMvc.perform(post("/api/v1/coupons/1/download")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.result").value("FAIL"))
                .andExpect(jsonPath("$.meta.message").value("쿠폰을 찾을 수 없습니다."));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/coupons/me")
    class GetMyCoupons {

        @Test
        @DisplayName("인증된 사용자가 내 쿠폰 목록을 조회하면 200 OK를 반환한다.")
        void returnsOk_whenAuthenticated() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            ZonedDateTime now = ZonedDateTime.now();
            MyCouponInfo myCouponInfo = new MyCouponInfo(
                    10L, "신규 가입 쿠폰", CouponScope.CART, DiscountType.FIXED_AMOUNT,
                    5000, 10000, 0, now.plusDays(30));

            when(couponFacade.getMyCoupons(any(Member.class))).thenReturn(List.of(myCouponInfo));

            // when & then
            mockMvc.perform(get("/api/v1/coupons/me")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].memberCouponId").value(10))
                .andExpect(jsonPath("$.data[0].couponName").value("신규 가입 쿠폰"));
        }

        @Test
        @DisplayName("인증 없이 내 쿠폰 목록을 조회하면 401을 반환한다.")
        void returnsUnauthorized_whenNoAuth() throws Exception {
            // when & then
            mockMvc.perform(get("/api/v1/coupons/me"))
                .andExpect(status().isUnauthorized());
        }
    }
}
