package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.AdminCouponFacade;
import com.loopers.application.coupon.CouponDetailInfo;
import com.loopers.application.coupon.CouponInfo;
import com.loopers.config.WebMvcConfig;
import com.loopers.domain.auth.Admin;
import com.loopers.domain.auth.LdapAuthService;
import com.loopers.domain.coupon.CouponScope;
import com.loopers.domain.coupon.DiscountType;
import com.loopers.domain.member.MemberService;
import com.loopers.interfaces.api.auth.AdminAuthInterceptor;
import com.loopers.interfaces.api.auth.LoginAdminArgumentResolver;
import com.loopers.interfaces.api.auth.LoginMemberArgumentResolver;
import com.loopers.interfaces.api.auth.MemberAuthInterceptor;
import com.loopers.interfaces.api.coupon.dto.CouponV1Dto;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZonedDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminCouponV1Controller.class)
@Import({WebMvcConfig.class, MemberAuthInterceptor.class, LoginMemberArgumentResolver.class, AdminAuthInterceptor.class, LoginAdminArgumentResolver.class})
@DisplayName("AdminCouponV1Controller 단위 테스트")
class AdminCouponV1ControllerTest {

    private static final String HEADER_LDAP = "X-Loopers-Ldap";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AdminCouponFacade adminCouponFacade;

    @MockBean
    private MemberService memberService;

    @MockBean
    private LdapAuthService ldapAuthService;

    @Nested
    @DisplayName("POST /api-admin/v1/coupons")
    class CreateCoupon {

        @Test
        @DisplayName("관리자 인증 성공 시 201 Created와 쿠폰 정보를 반환한다.")
        void returnsCreated_whenAdminAuthenticated() throws Exception {
            // given
            Admin admin = new Admin("loopers.admin");
            when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            ZonedDateTime now = ZonedDateTime.now();
            CouponV1Dto.CreateCouponRequest request = new CouponV1Dto.CreateCouponRequest(
                    "신규 가입 쿠폰", CouponScope.CART, null, DiscountType.FIXED_AMOUNT,
                    5000, 10000, 0, 100, now.minusDays(1), now.plusDays(30));

            CouponInfo couponInfo = new CouponInfo(
                    1L, "신규 가입 쿠폰", CouponScope.CART, DiscountType.FIXED_AMOUNT,
                    5000, 10000, 0, now.minusDays(1), now.plusDays(30), 100);

            when(adminCouponFacade.createCoupon(any())).thenReturn(couponInfo);

            // when & then
            mockMvc.perform(post("/api-admin/v1/coupons")
                    .header(HEADER_LDAP, "loopers.admin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("신규 가입 쿠폰"));
        }

        @Test
        @DisplayName("관리자 인증 없이 쿠폰을 생성하면 401을 반환한다.")
        void returnsUnauthorized_whenNoAdminAuth() throws Exception {
            // given
            ZonedDateTime now = ZonedDateTime.now();
            CouponV1Dto.CreateCouponRequest request = new CouponV1Dto.CreateCouponRequest(
                    "신규 가입 쿠폰", CouponScope.CART, null, DiscountType.FIXED_AMOUNT,
                    5000, 10000, 0, 100, now.minusDays(1), now.plusDays(30));

            // when & then
            mockMvc.perform(post("/api-admin/v1/coupons")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api-admin/v1/coupons")
    class GetCoupons {

        @Test
        @DisplayName("관리자 인증 성공 시 200 OK와 쿠폰 목록을 반환한다.")
        void returnsOk_whenAdminAuthenticated() throws Exception {
            // given
            Admin admin = new Admin("loopers.admin");
            when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            ZonedDateTime now = ZonedDateTime.now();
            CouponInfo couponInfo = new CouponInfo(
                    1L, "신규 가입 쿠폰", CouponScope.CART, DiscountType.FIXED_AMOUNT,
                    5000, 10000, 0, now.minusDays(1), now.plusDays(30), 100);

            Pageable pageable = PageRequest.of(0, 20);
            when(adminCouponFacade.getCoupons(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(couponInfo), pageable, 1));

            // when & then
            mockMvc.perform(get("/api-admin/v1/coupons")
                    .header(HEADER_LDAP, "loopers.admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].id").value(1));
        }

        @Test
        @DisplayName("관리자 인증 없이 쿠폰 목록을 조회하면 401을 반환한다.")
        void returnsUnauthorized_whenNoAdminAuth() throws Exception {
            // when & then
            mockMvc.perform(get("/api-admin/v1/coupons"))
                .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api-admin/v1/coupons/{couponId}")
    class GetCoupon {

        @Test
        @DisplayName("관리자 인증 성공 시 200 OK와 쿠폰 상세 정보를 반환한다.")
        void returnsOk_whenAdminAuthenticated() throws Exception {
            // given
            Admin admin = new Admin("loopers.admin");
            when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            ZonedDateTime now = ZonedDateTime.now();
            CouponDetailInfo couponDetailInfo = new CouponDetailInfo(
                    1L, "신규 가입 쿠폰", CouponScope.CART, null, DiscountType.FIXED_AMOUNT,
                    5000, 10000, 0, 100, 10,
                    now.minusDays(1), now.plusDays(30), now.minusDays(1), now.minusDays(1));

            when(adminCouponFacade.getCoupon(1L)).thenReturn(couponDetailInfo);

            // when & then
            mockMvc.perform(get("/api-admin/v1/coupons/1")
                    .header(HEADER_LDAP, "loopers.admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("신규 가입 쿠폰"))
                .andExpect(jsonPath("$.data.totalQuantity").value(100))
                .andExpect(jsonPath("$.data.issuedQuantity").value(10));
        }

        @Test
        @DisplayName("존재하지 않는 쿠폰이면 404를 반환한다.")
        void returnsNotFound_whenCouponNotExists() throws Exception {
            // given
            Admin admin = new Admin("loopers.admin");
            when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            when(adminCouponFacade.getCoupon(anyLong()))
                .thenThrow(new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));

            // when & then
            mockMvc.perform(get("/api-admin/v1/coupons/999")
                    .header(HEADER_LDAP, "loopers.admin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.result").value("FAIL"))
                .andExpect(jsonPath("$.meta.message").value("쿠폰을 찾을 수 없습니다."));
        }

        @Test
        @DisplayName("관리자 인증 없이 쿠폰 상세를 조회하면 401을 반환한다.")
        void returnsUnauthorized_whenNoAdminAuth() throws Exception {
            // when & then
            mockMvc.perform(get("/api-admin/v1/coupons/1"))
                .andExpect(status().isUnauthorized());
        }
    }
}
