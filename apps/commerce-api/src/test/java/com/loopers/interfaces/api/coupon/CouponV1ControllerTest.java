package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.application.coupon.CouponInfo;
import com.loopers.application.coupon.MyCouponInfo;
import com.loopers.config.WebMvcConfig;
import com.loopers.domain.auth.LdapAuthService;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.DiscountType;
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

    @MockBean
    private CouponFacade couponFacade;

    @MockBean
    private MemberService memberService;

    @MockBean
    private LdapAuthService ldapAuthService;

    @Nested
    @DisplayName("POST /api/v1/coupons/{couponId}/issue")
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
                    5000, 10000, 0, now.minusDays(1), now.plusDays(30));

            when(couponFacade.downloadCoupon(any(Member.class), anyLong())).thenReturn(couponInfo);

            // when & then
            mockMvc.perform(post("/api/v1/coupons/1/issue")
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
            mockMvc.perform(post("/api/v1/coupons/1/issue"))
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
            mockMvc.perform(post("/api/v1/coupons/1/issue")
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
            mockMvc.perform(post("/api/v1/coupons/1/issue")
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
            mockMvc.perform(post("/api/v1/coupons/1/issue")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.result").value("FAIL"))
                .andExpect(jsonPath("$.meta.message").value("쿠폰을 찾을 수 없습니다."));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/users/me/coupons")
    class GetMyCoupons {

        @Test
        @DisplayName("인증된 사용자가 내 쿠폰 목록을 조회하면 200 OK와 상태를 포함한 쿠폰 목록을 반환한다.")
        void returnsOk_whenAuthenticated() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            ZonedDateTime now = ZonedDateTime.now();
            MyCouponInfo availableCoupon = new MyCouponInfo(
                    10L, "사용 가능 쿠폰", CouponScope.CART, DiscountType.FIXED_AMOUNT,
                    5000, 10000, 0, now.plusDays(30), MemberCouponStatus.AVAILABLE);
            MyCouponInfo usedCoupon = new MyCouponInfo(
                    11L, "사용된 쿠폰", CouponScope.CART, DiscountType.FIXED_AMOUNT,
                    3000, 5000, 0, now.plusDays(10), MemberCouponStatus.USED);
            MyCouponInfo expiredCoupon = new MyCouponInfo(
                    12L, "만료된 쿠폰", CouponScope.CART, DiscountType.FIXED_RATE,
                    10, 0, 5000, now.minusDays(1), MemberCouponStatus.EXPIRED);

            when(couponFacade.getMyCoupons(any(Member.class)))
                    .thenReturn(List.of(availableCoupon, usedCoupon, expiredCoupon));

            // when & then
            mockMvc.perform(get("/api/v1/users/me/coupons")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].memberCouponId").value(10))
                .andExpect(jsonPath("$.data[0].couponName").value("사용 가능 쿠폰"))
                .andExpect(jsonPath("$.data[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data[1].memberCouponId").value(11))
                .andExpect(jsonPath("$.data[1].status").value("USED"))
                .andExpect(jsonPath("$.data[2].memberCouponId").value(12))
                .andExpect(jsonPath("$.data[2].status").value("EXPIRED"));
        }

        @Test
        @DisplayName("인증 없이 내 쿠폰 목록을 조회하면 401을 반환한다.")
        void returnsUnauthorized_whenNoAuth() throws Exception {
            // when & then
            mockMvc.perform(get("/api/v1/users/me/coupons"))
                .andExpect(status().isUnauthorized());
        }
    }

}
