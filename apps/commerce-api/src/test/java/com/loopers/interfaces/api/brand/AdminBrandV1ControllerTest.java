package com.loopers.interfaces.api.brand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.brand.AdminBrandFacade;
import com.loopers.application.brand.BrandInfo;
import com.loopers.config.WebMvcConfig;
import com.loopers.domain.auth.Admin;
import com.loopers.domain.auth.LdapAuthService;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.member.MemberService;
import com.loopers.interfaces.api.auth.AdminAuthInterceptor;
import com.loopers.interfaces.api.auth.LoginAdminArgumentResolver;
import com.loopers.interfaces.api.auth.LoginMemberArgumentResolver;
import com.loopers.interfaces.api.auth.MemberAuthInterceptor;
import com.loopers.interfaces.api.brand.dto.BrandV1Dto;
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

import java.time.ZonedDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminBrandV1Controller.class)
@Import({WebMvcConfig.class, MemberAuthInterceptor.class, LoginMemberArgumentResolver.class, AdminAuthInterceptor.class, LoginAdminArgumentResolver.class})
@DisplayName("AdminBrandV1Controller 단위 테스트")
class AdminBrandV1ControllerTest {

    private static final String ADMIN_HEADER = "X-Loopers-Ldap";
    private static final String ADMIN_LDAP_ID = "loopers.admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AdminBrandFacade adminBrandFacade;

    @MockBean
    private MemberService memberService;

    @MockBean
    private LdapAuthService ldapAuthService;

    @Nested
    @DisplayName("POST /api-admin/v1/brands")
    class CreateBrand {

        @Test
        @DisplayName("유효한 요청이면 201 Created를 반환한다.")
        void returnsCreated_whenValidRequest() throws Exception {
            // given
            when(ldapAuthService.authenticate(ADMIN_LDAP_ID)).thenReturn(new Admin(ADMIN_LDAP_ID));
            BrandInfo info = new BrandInfo(1L, "나이키", "스포츠 브랜드", BrandStatus.PENDING, ZonedDateTime.now(), ZonedDateTime.now());
            when(adminBrandFacade.createBrand(anyString(), anyString())).thenReturn(info);

            BrandV1Dto.CreateBrandRequest request = new BrandV1Dto.CreateBrandRequest("나이키", "스포츠 브랜드");

            // when & then
            mockMvc.perform(post("/api-admin/v1/brands")
                    .header(ADMIN_HEADER, ADMIN_LDAP_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("나이키"));
        }

        @Test
        @DisplayName("인증 헤더가 없으면 401을 반환한다.")
        void returnsUnauthorized_whenNoAuthHeader() throws Exception {
            // given
            BrandV1Dto.CreateBrandRequest request = new BrandV1Dto.CreateBrandRequest("나이키", "스포츠 브랜드");

            // when & then
            mockMvc.perform(post("/api-admin/v1/brands")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("중복 브랜드명이면 409를 반환한다.")
        void returnsConflict_whenDuplicateName() throws Exception {
            // given
            when(ldapAuthService.authenticate(ADMIN_LDAP_ID)).thenReturn(new Admin(ADMIN_LDAP_ID));
            when(adminBrandFacade.createBrand(anyString(), anyString()))
                .thenThrow(new CoreException(ErrorType.CONFLICT, "이미 존재하는 브랜드명입니다."));

            BrandV1Dto.CreateBrandRequest request = new BrandV1Dto.CreateBrandRequest("나이키", "스포츠 브랜드");

            // when & then
            mockMvc.perform(post("/api-admin/v1/brands")
                    .header(ADMIN_HEADER, ADMIN_LDAP_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("GET /api-admin/v1/brands/{brandId}")
    class GetBrand {

        @Test
        @DisplayName("존재하는 브랜드 조회 시 200 OK를 반환한다.")
        void returnsOk_whenBrandExists() throws Exception {
            // given
            when(ldapAuthService.authenticate(ADMIN_LDAP_ID)).thenReturn(new Admin(ADMIN_LDAP_ID));
            BrandInfo info = new BrandInfo(1L, "나이키", "스포츠 브랜드", BrandStatus.ACTIVE, ZonedDateTime.now(), ZonedDateTime.now());
            when(adminBrandFacade.getBrand(1L)).thenReturn(info);

            // when & then
            mockMvc.perform(get("/api-admin/v1/brands/1")
                    .header(ADMIN_HEADER, ADMIN_LDAP_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));
        }

        @Test
        @DisplayName("존재하지 않는 브랜드 조회 시 404를 반환한다.")
        void returnsNotFound_whenBrandNotExists() throws Exception {
            // given
            when(ldapAuthService.authenticate(ADMIN_LDAP_ID)).thenReturn(new Admin(ADMIN_LDAP_ID));
            when(adminBrandFacade.getBrand(999L))
                .thenThrow(new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다."));

            // when & then
            mockMvc.perform(get("/api-admin/v1/brands/999")
                    .header(ADMIN_HEADER, ADMIN_LDAP_ID))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PUT /api-admin/v1/brands/{brandId}")
    class UpdateBrand {

        @Test
        @DisplayName("유효한 요청이면 200 OK를 반환한다.")
        void returnsOk_whenValidRequest() throws Exception {
            // given
            when(ldapAuthService.authenticate(ADMIN_LDAP_ID)).thenReturn(new Admin(ADMIN_LDAP_ID));
            BrandInfo info = new BrandInfo(1L, "나이키 코리아", "스포츠 브랜드 수정", BrandStatus.ACTIVE, ZonedDateTime.now(), ZonedDateTime.now());
            when(adminBrandFacade.updateBrand(eq(1L), anyString(), anyString(), any(BrandStatus.class))).thenReturn(info);

            BrandV1Dto.UpdateBrandRequest request = new BrandV1Dto.UpdateBrandRequest("나이키 코리아", "스포츠 브랜드 수정", BrandStatus.ACTIVE);

            // when & then
            mockMvc.perform(put("/api-admin/v1/brands/1")
                    .header(ADMIN_HEADER, ADMIN_LDAP_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("나이키 코리아"));
        }
    }

    @Nested
    @DisplayName("DELETE /api-admin/v1/brands/{brandId}")
    class DeleteBrand {

        @Test
        @DisplayName("유효한 요청이면 200 OK를 반환한다.")
        void returnsOk_whenValidRequest() throws Exception {
            // given
            when(ldapAuthService.authenticate(ADMIN_LDAP_ID)).thenReturn(new Admin(ADMIN_LDAP_ID));

            // when & then
            mockMvc.perform(delete("/api-admin/v1/brands/1")
                    .header(ADMIN_HEADER, ADMIN_LDAP_ID))
                .andExpect(status().isOk());
        }

        @Test
        @DisplayName("인증 헤더가 없으면 401을 반환한다.")
        void returnsUnauthorized_whenNoAuthHeader() throws Exception {
            // when & then
            mockMvc.perform(delete("/api-admin/v1/brands/1"))
                .andExpect(status().isUnauthorized());
        }
    }
}
