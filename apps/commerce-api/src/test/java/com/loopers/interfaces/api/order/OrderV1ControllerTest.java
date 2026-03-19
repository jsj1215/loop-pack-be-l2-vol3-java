package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderDetailInfo;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemInfo;
import com.loopers.application.order.OrderPaymentFacade;
import com.loopers.config.WebMvcConfig;
import com.loopers.domain.auth.LdapAuthService;
import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberService;
import com.loopers.interfaces.api.auth.AdminAuthInterceptor;
import com.loopers.interfaces.api.auth.LoginAdminArgumentResolver;
import com.loopers.interfaces.api.auth.LoginMemberArgumentResolver;
import com.loopers.interfaces.api.auth.MemberAuthInterceptor;
import com.loopers.interfaces.api.order.dto.OrderV1Dto;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderV1Controller.class)
@Import({WebMvcConfig.class, MemberAuthInterceptor.class, LoginMemberArgumentResolver.class, AdminAuthInterceptor.class, LoginAdminArgumentResolver.class})
@DisplayName("OrderV1Controller 단위 테스트")
class OrderV1ControllerTest {

    private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
    private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderFacade orderFacade;

    @MockBean
    private OrderPaymentFacade orderPaymentFacade;

    @MockBean
    private MemberService memberService;

    @MockBean
    private LdapAuthService ldapAuthService;

    @Nested
    @DisplayName("POST /api/v1/orders")
    class CreateOrder {

        @Test
        @DisplayName("인증된 사용자가 주문을 생성하면 200 OK를 반환한다.")
        void returnsOk_whenAuthenticated() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            ZonedDateTime now = ZonedDateTime.now();
            OrderItemInfo orderItemInfo = new OrderItemInfo(
                    1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 2, 100000, now, now);

            OrderDetailInfo orderDetailInfo = new OrderDetailInfo(
                    1L, 100000, 0, 0, 100000, List.of(orderItemInfo), now);

            when(orderPaymentFacade.createOrder(anyLong(), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(orderDetailInfo);

            OrderV1Dto.CreateOrderRequest request = new OrderV1Dto.CreateOrderRequest(
                    List.of(new OrderV1Dto.OrderItemRequest(1L, 1L, 2)),
                    null, 0, null, "SAMSUNG", "1234-5678-9012-3456");

            // when & then
            mockMvc.perform(post("/api/v1/orders")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.totalAmount").value(100000));
        }

        @Test
        @DisplayName("인증 없이 주문을 생성하면 401을 반환한다.")
        void returnsUnauthorized_whenNoAuth() throws Exception {
            // given
            OrderV1Dto.CreateOrderRequest request = new OrderV1Dto.CreateOrderRequest(
                    List.of(new OrderV1Dto.OrderItemRequest(1L, 1L, 2)),
                    null, 0, null, "SAMSUNG", "1234-5678-9012-3456");

            // when & then
            mockMvc.perform(post("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("상품을 찾을 수 없으면 404를 반환한다.")
        void returnsNotFound_whenProductNotFound() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            when(orderPaymentFacade.createOrder(anyLong(), any(), any(), anyInt(), any(), any(), any()))
                .thenThrow(new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));

            OrderV1Dto.CreateOrderRequest request = new OrderV1Dto.CreateOrderRequest(
                    List.of(new OrderV1Dto.OrderItemRequest(999L, 999L, 1)),
                    null, 0, null, "SAMSUNG", "1234-5678-9012-3456");

            // when & then
            mockMvc.perform(post("/api/v1/orders")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.result").value("FAIL"));
        }

        @Test
        @DisplayName("재고 부족이면 400을 반환한다.")
        void returnsBadRequest_whenOutOfStock() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            when(orderPaymentFacade.createOrder(anyLong(), any(), any(), anyInt(), any(), any(), any()))
                .thenThrow(new CoreException(ErrorType.BAD_REQUEST, "재고가 부족합니다."));

            OrderV1Dto.CreateOrderRequest request = new OrderV1Dto.CreateOrderRequest(
                    List.of(new OrderV1Dto.OrderItemRequest(1L, 1L, 999)),
                    null, 0, null, "SAMSUNG", "1234-5678-9012-3456");

            // when & then
            mockMvc.perform(post("/api/v1/orders")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.result").value("FAIL"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/orders")
    class GetOrders {

        @Test
        @DisplayName("인증된 사용자가 주문 목록을 조회하면 200 OK를 반환한다.")
        void returnsOk_whenAuthenticated() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            ZonedDateTime now = ZonedDateTime.now();
            OrderInfo orderInfo = new OrderInfo(1L, 100000, 5000, 1000, 94000, 2, now);

            Pageable pageable = PageRequest.of(0, 20);
            when(orderFacade.findOrders(anyLong(), any(ZonedDateTime.class), any(ZonedDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(orderInfo), pageable, 1));

            ZonedDateTime startAt = now.minusDays(30);
            ZonedDateTime endAt = now;

            // when & then
            mockMvc.perform(get("/api/v1/orders")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!")
                    .param("startAt", startAt.toString())
                    .param("endAt", endAt.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].id").value(1))
                .andExpect(jsonPath("$.data.content[0].totalAmount").value(100000));
        }

        @Test
        @DisplayName("인증 없이 주문 목록을 조회하면 401을 반환한다.")
        void returnsUnauthorized_whenNoAuth() throws Exception {
            // given
            ZonedDateTime now = ZonedDateTime.now();

            // when & then
            mockMvc.perform(get("/api/v1/orders")
                    .param("startAt", now.minusDays(30).toString())
                    .param("endAt", now.toString()))
                .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/orders/{orderId}")
    class GetOrderDetail {

        @Test
        @DisplayName("인증된 사용자가 주문 상세를 조회하면 200 OK를 반환한다.")
        void returnsOk_whenAuthenticated() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            ZonedDateTime now = ZonedDateTime.now();
            OrderItemInfo orderItemInfo = new OrderItemInfo(
                    1L, 1L, 1L, "운동화", "270mm", "나이키",
                    50000, 40000, 3000, 2, 100000, now, now);

            OrderDetailInfo orderDetailInfo = new OrderDetailInfo(
                    1L, 100000, 5000, 1000, 94000, List.of(orderItemInfo), now);

            when(orderFacade.findOrderDetail(1L, 1L)).thenReturn(orderDetailInfo);

            // when & then
            mockMvc.perform(get("/api/v1/orders/1")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.totalAmount").value(100000))
                .andExpect(jsonPath("$.data.discountAmount").value(5000))
                .andExpect(jsonPath("$.data.usedPoints").value(1000));
        }

        @Test
        @DisplayName("주문을 찾을 수 없으면 404를 반환한다.")
        void returnsNotFound_whenOrderNotFound() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);

            when(orderFacade.findOrderDetail(anyLong(), anyLong()))
                .thenThrow(new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));

            // when & then
            mockMvc.perform(get("/api/v1/orders/999")
                    .header(HEADER_LOGIN_ID, "testuser1")
                    .header(HEADER_LOGIN_PW, "Password1!"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.result").value("FAIL"))
                .andExpect(jsonPath("$.meta.message").value("주문을 찾을 수 없습니다."));
        }

        @Test
        @DisplayName("인증 없이 주문 상세를 조회하면 401을 반환한다.")
        void returnsUnauthorized_whenNoAuth() throws Exception {
            // when & then
            mockMvc.perform(get("/api/v1/orders/1"))
                .andExpect(status().isUnauthorized());
        }
    }
}
