package com.loopers.interfaces.api.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.AdminProductFacade;
import com.loopers.application.product.ProductDetailInfo;
import com.loopers.application.product.ProductInfo;
import com.loopers.application.product.ProductOptionInfo;
import com.loopers.config.WebMvcConfig;
import com.loopers.domain.auth.Admin;
import com.loopers.domain.auth.LdapAuthService;
import com.loopers.domain.member.MemberService;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.ProductStatus;
import com.loopers.interfaces.api.auth.AdminAuthInterceptor;
import com.loopers.interfaces.api.auth.LoginAdminArgumentResolver;
import com.loopers.interfaces.api.auth.LoginMemberArgumentResolver;
import com.loopers.interfaces.api.auth.MemberAuthInterceptor;
import com.loopers.interfaces.api.product.dto.ProductV1Dto;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminProductV1Controller.class)
@Import({WebMvcConfig.class, MemberAuthInterceptor.class, LoginMemberArgumentResolver.class, AdminAuthInterceptor.class, LoginAdminArgumentResolver.class})
@DisplayName("AdminProductV1Controller 단위 테스트")
class AdminProductV1ControllerTest {

    private static final String HEADER_LDAP = "X-Loopers-Ldap";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AdminProductFacade adminProductFacade;

    @MockBean
    private MemberService memberService;

    @MockBean
    private LdapAuthService ldapAuthService;

    @Nested
    @DisplayName("GET /api-admin/v1/products")
    class GetProducts {

        @Test
        @DisplayName("관리자 인증 성공 시 200 OK와 상품 목록을 반환한다.")
        void returnsOk_whenAdminAuthenticated() throws Exception {
            // given
            Admin admin = new Admin("loopers.admin");
            when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            ZonedDateTime now = ZonedDateTime.now();
            ProductInfo productInfo = new ProductInfo(
                    1L, 1L, "나이키", "운동화", 50000, 45000, 3000,
                    10, ProductStatus.ON_SALE, "Y", now);

            Pageable pageable = PageRequest.of(0, 20);
            when(adminProductFacade.getProducts(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(productInfo), pageable, 1));

            // when & then
            mockMvc.perform(get("/api-admin/v1/products")
                    .header(HEADER_LDAP, "loopers.admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].id").value(1))
                .andExpect(jsonPath("$.data.content[0].name").value("운동화"));
        }

        @Test
        @DisplayName("관리자 인증 없이 상품 목록을 조회하면 401을 반환한다.")
        void returnsUnauthorized_whenNoAdminAuth() throws Exception {
            // when & then
            mockMvc.perform(get("/api-admin/v1/products"))
                .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api-admin/v1/products/{productId}")
    class GetProduct {

        @Test
        @DisplayName("관리자 인증 성공 시 200 OK와 상품 상세 정보를 반환한다.")
        void returnsOk_whenAdminAuthenticated() throws Exception {
            // given
            Admin admin = new Admin("loopers.admin");
            when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            ZonedDateTime now = ZonedDateTime.now();
            ProductOptionInfo optionInfo = new ProductOptionInfo(1L, "270mm", 100);
            ProductDetailInfo productDetailInfo = new ProductDetailInfo(
                    1L, 1L, "나이키", "운동화", 50000, 40000, 45000, 3000,
                    10, "편안한 운동화", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y",
                    List.of(optionInfo), now);

            when(adminProductFacade.getProduct(1L)).thenReturn(productDetailInfo);

            // when & then
            mockMvc.perform(get("/api-admin/v1/products/1")
                    .header(HEADER_LDAP, "loopers.admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("운동화"))
                .andExpect(jsonPath("$.data.options[0].optionName").value("270mm"));
        }

        @Test
        @DisplayName("상품이 존재하지 않으면 404를 반환한다.")
        void returnsNotFound_whenProductNotExists() throws Exception {
            // given
            Admin admin = new Admin("loopers.admin");
            when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            when(adminProductFacade.getProduct(anyLong()))
                .thenThrow(new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));

            // when & then
            mockMvc.perform(get("/api-admin/v1/products/999")
                    .header(HEADER_LDAP, "loopers.admin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.result").value("FAIL"))
                .andExpect(jsonPath("$.meta.message").value("상품을 찾을 수 없습니다."));
        }

        @Test
        @DisplayName("관리자 인증 없이 상품 상세를 조회하면 401을 반환한다.")
        void returnsUnauthorized_whenNoAdminAuth() throws Exception {
            // when & then
            mockMvc.perform(get("/api-admin/v1/products/1"))
                .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /api-admin/v1/products")
    class CreateProduct {

        @Test
        @DisplayName("관리자 인증 성공 시 201 Created와 상품 상세 정보를 반환한다.")
        void returnsCreated_whenAdminAuthenticated() throws Exception {
            // given
            Admin admin = new Admin("loopers.admin");
            when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            ZonedDateTime now = ZonedDateTime.now();
            ProductOptionInfo optionInfo = new ProductOptionInfo(1L, "270mm", 100);
            ProductDetailInfo productDetailInfo = new ProductDetailInfo(
                    1L, 1L, "나이키", "운동화", 50000, 40000, 45000, 3000,
                    0, "편안한 운동화", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y",
                    List.of(optionInfo), now);

            when(adminProductFacade.createProduct(anyLong(), anyString(), anyInt(), any(MarginType.class),
                    anyInt(), anyInt(), anyInt(), anyString(), any()))
                .thenReturn(productDetailInfo);

            ProductV1Dto.CreateProductRequest request = new ProductV1Dto.CreateProductRequest(
                    1L, "운동화", 50000, MarginType.AMOUNT, 10000,
                    45000, 3000, "편안한 운동화",
                    List.of(new ProductV1Dto.ProductOptionRequest("270mm", 100)));

            // when & then
            mockMvc.perform(post("/api-admin/v1/products")
                    .header(HEADER_LDAP, "loopers.admin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("운동화"));
        }

        @Test
        @DisplayName("관리자 인증 없이 상품을 생성하면 401을 반환한다.")
        void returnsUnauthorized_whenNoAdminAuth() throws Exception {
            // given
            ProductV1Dto.CreateProductRequest request = new ProductV1Dto.CreateProductRequest(
                    1L, "운동화", 50000, MarginType.AMOUNT, 10000,
                    45000, 3000, "편안한 운동화",
                    List.of(new ProductV1Dto.ProductOptionRequest("270mm", 100)));

            // when & then
            mockMvc.perform(post("/api-admin/v1/products")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("브랜드가 존재하지 않으면 404를 반환한다.")
        void returnsNotFound_whenBrandNotExists() throws Exception {
            // given
            Admin admin = new Admin("loopers.admin");
            when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            when(adminProductFacade.createProduct(anyLong(), anyString(), anyInt(), any(MarginType.class),
                    anyInt(), anyInt(), anyInt(), anyString(), any()))
                .thenThrow(new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다."));

            ProductV1Dto.CreateProductRequest request = new ProductV1Dto.CreateProductRequest(
                    999L, "운동화", 50000, MarginType.AMOUNT, 10000,
                    45000, 3000, "편안한 운동화",
                    List.of(new ProductV1Dto.ProductOptionRequest("270mm", 100)));

            // when & then
            mockMvc.perform(post("/api-admin/v1/products")
                    .header(HEADER_LDAP, "loopers.admin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.result").value("FAIL"));
        }
    }

    @Nested
    @DisplayName("PUT /api-admin/v1/products/{productId}")
    class UpdateProduct {

        @Test
        @DisplayName("관리자 인증 성공 시 200 OK와 수정된 상품 정보를 반환한다.")
        void returnsOk_whenAdminAuthenticated() throws Exception {
            // given
            Admin admin = new Admin("loopers.admin");
            when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            ZonedDateTime now = ZonedDateTime.now();
            ProductOptionInfo optionInfo = new ProductOptionInfo(1L, "270mm", 50);
            ProductDetailInfo productDetailInfo = new ProductDetailInfo(
                    1L, 1L, "나이키", "운동화 v2", 55000, 42000, 50000, 3000,
                    10, "더 편안한 운동화", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y",
                    List.of(optionInfo), now);

            when(adminProductFacade.updateProduct(anyLong(), anyString(), anyInt(), anyInt(),
                    anyInt(), anyInt(), anyString(), any(ProductStatus.class), anyString(), any()))
                .thenReturn(productDetailInfo);

            ProductV1Dto.UpdateProductRequest request = new ProductV1Dto.UpdateProductRequest(
                    "운동화 v2", 55000, 42000, 50000, 3000,
                    "더 편안한 운동화", ProductStatus.ON_SALE, "Y",
                    List.of(new ProductV1Dto.ProductOptionRequest("270mm", 50)));

            // when & then
            mockMvc.perform(put("/api-admin/v1/products/1")
                    .header(HEADER_LDAP, "loopers.admin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("운동화 v2"));
        }

        @Test
        @DisplayName("상품이 존재하지 않으면 404를 반환한다.")
        void returnsNotFound_whenProductNotExists() throws Exception {
            // given
            Admin admin = new Admin("loopers.admin");
            when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            when(adminProductFacade.updateProduct(anyLong(), anyString(), anyInt(), anyInt(),
                    anyInt(), anyInt(), anyString(), any(ProductStatus.class), anyString(), any()))
                .thenThrow(new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));

            ProductV1Dto.UpdateProductRequest request = new ProductV1Dto.UpdateProductRequest(
                    "운동화 v2", 55000, 42000, 50000, 3000,
                    "더 편안한 운동화", ProductStatus.ON_SALE, "Y",
                    List.of(new ProductV1Dto.ProductOptionRequest("270mm", 50)));

            // when & then
            mockMvc.perform(put("/api-admin/v1/products/999")
                    .header(HEADER_LDAP, "loopers.admin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.result").value("FAIL"));
        }

        @Test
        @DisplayName("관리자 인증 없이 상품을 수정하면 401을 반환한다.")
        void returnsUnauthorized_whenNoAdminAuth() throws Exception {
            // given
            ProductV1Dto.UpdateProductRequest request = new ProductV1Dto.UpdateProductRequest(
                    "운동화 v2", 55000, 42000, 50000, 3000,
                    "더 편안한 운동화", ProductStatus.ON_SALE, "Y",
                    List.of(new ProductV1Dto.ProductOptionRequest("270mm", 50)));

            // when & then
            mockMvc.perform(put("/api-admin/v1/products/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("DELETE /api-admin/v1/products/{productId}")
    class DeleteProduct {

        @Test
        @DisplayName("관리자 인증 성공 시 200 OK를 반환한다.")
        void returnsOk_whenAdminAuthenticated() throws Exception {
            // given
            Admin admin = new Admin("loopers.admin");
            when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            doNothing().when(adminProductFacade).deleteProduct(1L);

            // when & then
            mockMvc.perform(delete("/api-admin/v1/products/1")
                    .header(HEADER_LDAP, "loopers.admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"));

            verify(adminProductFacade, times(1)).deleteProduct(1L);
        }

        @Test
        @DisplayName("상품이 존재하지 않으면 404를 반환한다.")
        void returnsNotFound_whenProductNotExists() throws Exception {
            // given
            Admin admin = new Admin("loopers.admin");
            when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            doThrow(new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."))
                .when(adminProductFacade).deleteProduct(999L);

            // when & then
            mockMvc.perform(delete("/api-admin/v1/products/999")
                    .header(HEADER_LDAP, "loopers.admin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.result").value("FAIL"));
        }

        @Test
        @DisplayName("관리자 인증 없이 상품을 삭제하면 401을 반환한다.")
        void returnsUnauthorized_whenNoAdminAuth() throws Exception {
            // when & then
            mockMvc.perform(delete("/api-admin/v1/products/1"))
                .andExpect(status().isUnauthorized());
        }
    }
}
