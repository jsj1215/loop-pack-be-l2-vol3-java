package com.loopers.interfaces.api.brand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.brand.BrandFacade;
import com.loopers.application.brand.BrandInfo;
import com.loopers.config.WebMvcConfig;
import com.loopers.domain.auth.LdapAuthService;
import com.loopers.domain.brand.BrandStatus;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BrandV1Controller.class)
@Import({WebMvcConfig.class, MemberAuthInterceptor.class, LoginMemberArgumentResolver.class, AdminAuthInterceptor.class, LoginAdminArgumentResolver.class})
@DisplayName("BrandV1Controller 단위 테스트")
class BrandV1ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BrandFacade brandFacade;

    @MockBean
    private MemberService memberService;

    @MockBean
    private LdapAuthService ldapAuthService;

    @Nested
    @DisplayName("GET /api/v1/brands/{brandId}")
    class GetBrand {

        @Test
        @DisplayName("존재하는 활성 브랜드 조회 시 200 OK를 반환한다.")
        void returnsOk_whenActiveBrandExists() throws Exception {
            // given
            BrandInfo info = new BrandInfo(1L, "나이키", "스포츠 브랜드", BrandStatus.ACTIVE, ZonedDateTime.now(), ZonedDateTime.now());
            when(brandFacade.getBrand(1L)).thenReturn(info);

            // when & then
            mockMvc.perform(get("/api/v1/brands/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("나이키"));
        }

        @Test
        @DisplayName("존재하지 않는 브랜드 조회 시 404를 반환한다.")
        void returnsNotFound_whenBrandNotExists() throws Exception {
            // given
            when(brandFacade.getBrand(999L)).thenThrow(
                new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다."));

            // when & then
            mockMvc.perform(get("/api/v1/brands/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.result").value("FAIL"));
        }

        @Test
        @DisplayName("인증 없이도 브랜드를 조회할 수 있다.")
        void allowsAccessWithoutAuth() throws Exception {
            // given - /api/v1/brands/** is excluded from auth interceptor
            BrandInfo info = new BrandInfo(1L, "나이키", "스포츠 브랜드", BrandStatus.ACTIVE, ZonedDateTime.now(), ZonedDateTime.now());
            when(brandFacade.getBrand(1L)).thenReturn(info);

            // when & then - no auth headers provided
            mockMvc.perform(get("/api/v1/brands/1"))
                .andExpect(status().isOk());
        }
    }
}
