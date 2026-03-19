package com.loopers.interfaces.api.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.ManualPaymentFacade;
import com.loopers.application.payment.PaymentInfo;
import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberService;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.interfaces.api.auth.AdminAuthInterceptor;
import com.loopers.interfaces.api.auth.LoginAdminArgumentResolver;
import com.loopers.interfaces.api.auth.LoginMemberArgumentResolver;
import com.loopers.interfaces.api.auth.MemberAuthInterceptor;
import com.loopers.interfaces.api.payment.dto.PaymentV1Dto;
import com.loopers.config.WebMvcConfig;
import com.loopers.domain.auth.LdapAuthService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentV1Controller.class)
@Import({WebMvcConfig.class, MemberAuthInterceptor.class,
        LoginMemberArgumentResolver.class, AdminAuthInterceptor.class,
        LoginAdminArgumentResolver.class})
@DisplayName("PaymentV1Controller 단위 테스트")
class PaymentV1ControllerTest {

    private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
    private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ManualPaymentFacade manualPaymentFacade;

    @MockBean
    private MemberService memberService;

    @MockBean
    private LdapAuthService ldapAuthService;

    private PaymentInfo createPaymentInfo(PaymentStatus status) {
        return new PaymentInfo(
                1L, 100L, 1L, 50000, "SAMSUNG",
                status, "20250316:TR:abc123", null,
                ZonedDateTime.now());
    }

    private PaymentInfo createPaymentInfo(PaymentStatus status, String failureReason) {
        return new PaymentInfo(
                1L, 100L, 1L, 50000, "SAMSUNG",
                status, null, failureReason,
                ZonedDateTime.now());
    }

    @Nested
    @DisplayName("POST /api/v1/payments")
    class CreatePayment {

        @Test
        @DisplayName("인증된 사용자가 결제를 요청하면 200 OK를 반환한다.")
        void returnsOk_whenAuthenticated() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);
            when(manualPaymentFacade.processPayment(anyLong(), anyLong(), anyString(), anyString()))
                    .thenReturn(createPaymentInfo(PaymentStatus.PENDING));

            PaymentV1Dto.CreatePaymentRequest request =
                    new PaymentV1Dto.CreatePaymentRequest(100L, "SAMSUNG", "1234-5678-9012-3456");

            // when & then
            mockMvc.perform(post("/api/v1/payments")
                            .header(HEADER_LOGIN_ID, "testuser1")
                            .header(HEADER_LOGIN_PW, "Password1!")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.orderId").value(100))
                    .andExpect(jsonPath("$.data.status").value("PENDING"));
        }

        @Test
        @DisplayName("PG 장애로 결제 실패 시에도 200 OK + FAILED 상태를 반환한다.")
        void returnsOkWithFailedStatus_whenPgDown() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);
            when(manualPaymentFacade.processPayment(anyLong(), anyLong(), anyString(), anyString()))
                    .thenReturn(createPaymentInfo(PaymentStatus.FAILED, "PG 시스템 장애"));

            PaymentV1Dto.CreatePaymentRequest request =
                    new PaymentV1Dto.CreatePaymentRequest(100L, "SAMSUNG", "1234-5678-9012-3456");

            // when & then
            mockMvc.perform(post("/api/v1/payments")
                            .header(HEADER_LOGIN_ID, "testuser1")
                            .header(HEADER_LOGIN_PW, "Password1!")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.status").value("FAILED"));
        }

        @Test
        @DisplayName("PG 타임아웃 시 200 OK + UNKNOWN 상태를 반환한다.")
        void returnsOkWithUnknownStatus_whenTimeout() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);
            when(manualPaymentFacade.processPayment(anyLong(), anyLong(), anyString(), anyString()))
                    .thenReturn(createPaymentInfo(PaymentStatus.UNKNOWN));

            PaymentV1Dto.CreatePaymentRequest request =
                    new PaymentV1Dto.CreatePaymentRequest(100L, "SAMSUNG", "1234-5678-9012-3456");

            // when & then
            mockMvc.perform(post("/api/v1/payments")
                            .header(HEADER_LOGIN_ID, "testuser1")
                            .header(HEADER_LOGIN_PW, "Password1!")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.status").value("UNKNOWN"));
        }

        @Test
        @DisplayName("인증 헤더가 없으면 인증 실패 응답을 반환한다.")
        void returnsUnauthorized_whenNoAuthHeaders() throws Exception {
            // given
            PaymentV1Dto.CreatePaymentRequest request =
                    new PaymentV1Dto.CreatePaymentRequest(100L, "SAMSUNG", "1234");

            // when & then
            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(jsonPath("$.meta.result").value("FAIL"))
                    .andExpect(jsonPath("$.meta.errorCode").value("Unauthorized"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/payments/callback")
    class HandleCallback {

        @Test
        @DisplayName("PG 콜백을 수신하면 200 OK를 반환한다.")
        void returnsOk_whenCallbackReceived() throws Exception {
            // given
            PaymentV1Dto.PgCallbackRequest request =
                    new PaymentV1Dto.PgCallbackRequest("20250316:TR:abc123", "100", "SUCCESS", null);

            // when & then
            mockMvc.perform(post("/api/v1/payments/callback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.result").value("SUCCESS"));

            verify(manualPaymentFacade).handleCallback(100L, "20250316:TR:abc123", "SUCCESS");
        }

        @Test
        @DisplayName("orderId가 숫자가 아니면 실패 응답을 반환한다.")
        void returnsFail_whenOrderIdIsNotNumeric() throws Exception {
            // given
            PaymentV1Dto.PgCallbackRequest request =
                    new PaymentV1Dto.PgCallbackRequest("20250316:TR:abc123", "invalid-id", "SUCCESS", null);

            // when & then
            mockMvc.perform(post("/api/v1/payments/callback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(jsonPath("$.meta.result").value("FAIL"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/payments/verify")
    class VerifyPayment {

        @Test
        @DisplayName("인증된 사용자가 상태 확인을 요청하면 200 OK를 반환한다.")
        void returnsOk_whenVerifyRequested() throws Exception {
            // given
            Member mockMember = mock(Member.class);
            when(mockMember.getId()).thenReturn(1L);
            when(memberService.authenticate("testuser1", "Password1!")).thenReturn(mockMember);
            when(manualPaymentFacade.verifyPayment(1L, 100L))
                    .thenReturn(createPaymentInfo(PaymentStatus.SUCCESS));

            // when & then
            mockMvc.perform(get("/api/v1/payments/verify")
                            .header(HEADER_LOGIN_ID, "testuser1")
                            .header(HEADER_LOGIN_PW, "Password1!")
                            .param("orderId", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.result").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        }
    }
}
