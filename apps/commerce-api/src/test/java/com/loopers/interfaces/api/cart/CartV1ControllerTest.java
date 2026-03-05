package com.loopers.interfaces.api.cart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.cart.CartFacade;
import com.loopers.config.WebMvcConfig;
import com.loopers.domain.auth.LdapAuthService;
import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberService;
import com.loopers.interfaces.api.auth.AdminAuthInterceptor;
import com.loopers.interfaces.api.auth.LoginAdminArgumentResolver;
import com.loopers.interfaces.api.auth.LoginMemberArgumentResolver;
import com.loopers.interfaces.api.auth.MemberAuthInterceptor;
import com.loopers.interfaces.api.cart.dto.CartV1Dto;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CartV1Controller.class)
@Import({WebMvcConfig.class, MemberAuthInterceptor.class, LoginMemberArgumentResolver.class, AdminAuthInterceptor.class, LoginAdminArgumentResolver.class})
@DisplayName("CartV1Controller 단위 테스트")
class CartV1ControllerTest {

    private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
    private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CartFacade cartFacade;

    @MockBean
    private MemberService memberService;

    @MockBean
    private LdapAuthService ldapAuthService;

    @Nested
    @DisplayName("POST /api/v1/cart")
    class AddToCart {

        @Test
        @DisplayName("인증된 사용자가 장바구니에 추가하면 201 Created를 반환한다.")
        void returnsCreated_whenAuthenticated() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);
            doNothing().when(cartFacade).addToCart(1L, 10L, 2);

            CartV1Dto.AddToCartRequest request = new CartV1Dto.AddToCartRequest(10L, 2);

            // when & then
            mockMvc.perform(post("/api/v1/cart")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"));
        }

        @Test
        @DisplayName("인증 없이 장바구니에 추가하면 401을 반환한다.")
        void returnsUnauthorized_whenNoAuth() throws Exception {
            // given
            CartV1Dto.AddToCartRequest request = new CartV1Dto.AddToCartRequest(10L, 2);

            // when & then
            mockMvc.perform(post("/api/v1/cart")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("재고 부족이면 400을 반환한다.")
        void returnsBadRequest_whenOutOfStock() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);
            doThrow(new CoreException(ErrorType.BAD_REQUEST, "상품 옵션이 존재하지 않거나 재고가 부족합니다."))
                .when(cartFacade).addToCart(anyLong(), anyLong(), anyInt());

            CartV1Dto.AddToCartRequest request = new CartV1Dto.AddToCartRequest(10L, 999);

            // when & then
            mockMvc.perform(post("/api/v1/cart")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        }
    }
}
