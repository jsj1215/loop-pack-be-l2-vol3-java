package com.loopers.interfaces.api.payment;

import com.loopers.application.payment.ManualPaymentFacade;
import com.loopers.application.payment.PaymentInfo;
import com.loopers.domain.member.Member;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginMember;
import com.loopers.interfaces.api.payment.dto.PaymentV1Dto;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 API V1 컨트롤러(사용안함) - 수동 Resilience 적용
 * : 타임아웃, 리트라이, 서킷브레이커, 폴백
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentV1Controller {

    private final ManualPaymentFacade manualPaymentFacade;

    @PostMapping
    public ApiResponse<PaymentV1Dto.PaymentResponse> createPayment(
            @LoginMember Member member,
            @Valid @RequestBody PaymentV1Dto.CreatePaymentRequest request) {

        PaymentInfo info = manualPaymentFacade.processPayment(
                member.getId(),
                request.orderId(),
                request.cardType(),
                request.cardNo());

        return ApiResponse.success(PaymentV1Dto.PaymentResponse.from(info));
    }

    @PostMapping("/callback")
    public ApiResponse<Object> handleCallback(
            @RequestBody PaymentV1Dto.PgCallbackRequest request) {

        Long orderId = parseOrderId(request.orderId());

        manualPaymentFacade.handleCallback(
                orderId,
                request.transactionId(),
                request.status());

        return ApiResponse.success();
    }

    private Long parseOrderId(String orderId) {
        try {
            return Long.valueOf(orderId);
        } catch (NumberFormatException e) {
            throw new CoreException(ErrorType.BAD_REQUEST, "유효하지 않은 주문 ID입니다: " + orderId);
        }
    }

    @GetMapping("/verify")
    public ApiResponse<PaymentV1Dto.PaymentResponse> verifyPayment(
            @LoginMember Member member,
            @RequestParam Long orderId) {

        PaymentInfo info = manualPaymentFacade.verifyPayment(member.getId(), orderId);
        return ApiResponse.success(PaymentV1Dto.PaymentResponse.from(info));
    }
}
