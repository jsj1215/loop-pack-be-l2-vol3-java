package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductDetailInfo;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.product.ProductInfo;
import com.loopers.application.product.ProductOptionInfo;
import com.loopers.config.WebMvcConfig;
import com.loopers.domain.auth.LdapAuthService;
import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberService;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.ProductStatus;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZonedDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductV1Controller.class)
@Import({WebMvcConfig.class, MemberAuthInterceptor.class, LoginMemberArgumentResolver.class, AdminAuthInterceptor.class, LoginAdminArgumentResolver.class})
@DisplayName("ProductV1Controller 단위 테스트")
class ProductV1ControllerTest {

    private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
    private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductFacade productFacade;

    @MockBean
    private MemberService memberService;

    @MockBean
    private LdapAuthService ldapAuthService;

    @Nested
    @DisplayName("GET /api/v1/products")
    class GetProducts {

        @Test
        @DisplayName("인증 없이 상품 목록 조회 시 200 OK를 반환한다.")
        void returnsOk_withoutAuth() throws Exception {
            // given
            ProductInfo info = new ProductInfo(1L, 1L, "나이키", "에어맥스", 100000, 10000, 3000, 0, ProductStatus.ON_SALE, "Y", ZonedDateTime.now());
            Page<ProductInfo> page = new PageImpl<>(List.of(info));
            when(productFacade.getProducts(any(), any())).thenReturn(page);

            // when & then
            mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"));
        }

        @Test
        @DisplayName("빈 상품 목록 조회 시 200 OK와 빈 목록을 반환한다.")
        void returnsOk_whenEmpty() throws Exception {
            // given
            Page<ProductInfo> emptyPage = new PageImpl<>(List.of());
            when(productFacade.getProducts(any(), any())).thenReturn(emptyPage);

            // when & then
            mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/products/{productId}")
    class GetProduct {

        @Test
        @DisplayName("인증 없이 상품 상세 조회 시 200 OK를 반환한다.")
        void returnsOk_withoutAuth() throws Exception {
            // given
            ProductOptionInfo optInfo = new ProductOptionInfo(1L, "M사이즈", 100);
            ProductDetailInfo info = new ProductDetailInfo(1L, 1L, "나이키", "에어맥스", 100000, 80000, 10000, 3000, 0, "설명", MarginType.AMOUNT, ProductStatus.ON_SALE, "Y", List.of(optInfo), ZonedDateTime.now());
            when(productFacade.getProduct(1L, null)).thenReturn(info);

            // when & then
            mockMvc.perform(get("/api/v1/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("에어맥스"));
        }

        @Test
        @DisplayName("존재하지 않는 상품 조회 시 404를 반환한다.")
        void returnsNotFound_whenProductNotExists() throws Exception {
            // given
            when(productFacade.getProduct(999L, null))
                .thenThrow(new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));

            // when & then
            mockMvc.perform(get("/api/v1/products/999"))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/products/{productId}/likes")
    class Like {

        @Test
        @DisplayName("인증된 사용자가 좋아요를 하면 200 OK를 반환한다.")
        void returnsOk_whenAuthenticated() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            // when & then
            mockMvc.perform(post("/api/v1/products/1/likes")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!"))
                .andExpect(status().isOk());

            verify(productFacade).like(1L, 1L);
        }

        @Test
        @DisplayName("인증 없이 좋아요를 하면 401을 반환한다.")
        void returnsUnauthorized_whenNoAuth() throws Exception {
            // when & then
            mockMvc.perform(post("/api/v1/products/1/likes"))
                .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/products/{productId}/likes")
    class Unlike {

        @Test
        @DisplayName("인증된 사용자가 좋아요 취소를 하면 200 OK를 반환한다.")
        void returnsOk_whenAuthenticated() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            // when & then
            mockMvc.perform(delete("/api/v1/products/1/likes")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!"))
                .andExpect(status().isOk());

            verify(productFacade).unlike(1L, 1L);
        }
    }
}
