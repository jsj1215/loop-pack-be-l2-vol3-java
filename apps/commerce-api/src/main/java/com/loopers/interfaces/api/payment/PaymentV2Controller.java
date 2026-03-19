package com.loopers.interfaces.api.payment;

import com.loopers.application.payment.PaymentFacade;
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
 * 결제 API V2 컨트롤러 — Resilience4j 어노테이션 기반 장애 대응 버전
 *
 * V1과의 차이점:
 *   - V1: ManualPaymentFacade → ManualPgApiClient (서킷브레이커·리트라이를 직접 구현)
 *   - V2: PaymentFacade → PgApiClient (Resilience4j @TimeLimiter/@CircuitBreaker/@Retry 선언적 적용)
 *
 * 비동기 PG 결제 처리 흐름:
 *   1. POST /api/v2/payments          — 결제 요청: PG에 결제를 접수하고, 콜백 대기 상태(PENDING)로 전환
 *   2. POST /api/v2/payments/callback  — PG 콜백 수신: PG가 비동기 처리 완료 후 결과를 Webhook으로 전달
 *   3. GET  /api/v2/payments/verify    — 결제 검증: 콜백 유실 시 PG에 직접 조회하여 상태 동기화
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v2/payments")
public class PaymentV2Controller {

    private final PaymentFacade paymentFacade;

    /**
     * 결제 요청 API
     *
     * 처리 흐름:
     *   1. 주문 검증 + Payment 엔티티 생성 (상태: REQUESTED)
     *   2. PG API 호출 (Resilience4j의 TimeLimiter/CircuitBreaker/Retry 적용)
     *   3. PG 응답에 따라 상태 전이: PENDING(접수 성공) / UNKNOWN(타임아웃) / FAILED(실패)
     *
     * @param member  @LoginMember 어노테이션으로 인증된 사용자 정보를 주입받는다 (커스텀 ArgumentResolver)
     * @param request 결제에 필요한 주문 ID, 카드 종류, 카드 번호
     * @return 생성된 Payment 정보 (현재 상태, PG 트랜잭션 ID 등 포함)
     */
    @PostMapping
    public ApiResponse<PaymentV1Dto.PaymentResponse> createPayment(
            @LoginMember Member member,
            @Valid @RequestBody PaymentV1Dto.CreatePaymentRequest request) {

        PaymentInfo info = paymentFacade.processPayment(
                member.getId(),
                request.orderId(),
                request.cardType(),
                request.cardNo());

        return ApiResponse.success(PaymentV1Dto.PaymentResponse.from(info));
    }

    /**
     * PG 콜백 수신 API (Webhook 엔드포인트)
     *
     * PG사가 결제 처리를 완료한 뒤, 우리 서버로 결과를 알려주는 서버 간(S2S) API이다.
     * 비동기 결제 모델에서는 결제 요청 시점에 즉시 성공/실패가 결정되지 않고,
     * PG가 비동기로 처리한 뒤 이 콜백을 통해 최종 결과(SUCCESS/FAILED 등)를 전달한다.
     *
     * 콜백 데이터:
     *   - transactionId: PG사가 발급한 고유 트랜잭션 ID (결제 추적 및 무결성 검증에 사용)
     *   - orderId: 우리 시스템의 주문 ID (Payment 엔티티와 매핑)
     *   - status: PG 처리 결과 ("SUCCESS", "FAILED", "LIMIT_EXCEEDED", "INVALID_CARD" 등)
     *
     * @LoginMember 인증이 없는 이유: PG 서버가 호출하는 서버 간(S2S) API이기 때문이다.
     */
    @PostMapping("/callback")
    public ApiResponse<Object> handleCallback(
            @RequestBody PaymentV1Dto.PgCallbackRequest request) {

        Long orderId = parseOrderId(request.orderId());

        paymentFacade.handleCallback(
                orderId,
                request.transactionId(),
                request.status());

        return ApiResponse.success();
    }

    /**
     * 문자열 orderId를 Long으로 안전하게 파싱한다.
     * PG 콜백은 외부 시스템 입력이므로 시스템 경계(System Boundary)에서 입력값을 검증한다.
     */
    private Long parseOrderId(String orderId) {
        try {
            return Long.valueOf(orderId);
        } catch (NumberFormatException e) {
            throw new CoreException(ErrorType.BAD_REQUEST, "유효하지 않은 주문 ID입니다: " + orderId);
        }
    }

    /**
     * 결제 상태 검증 API (보상 메커니즘)
     *
     * 콜백이 유실(네트워크 장애, 서버 다운 등)됐을 때 사용하는 보상(Compensation) 엔드포인트이다.
     * 클라이언트가 결제 상태가 PENDING/UNKNOWN인 경우 이 API를 호출하면:
     *   1. PG에 직접 결제 상태를 조회 (GET /api/v1/payments?orderId=...)
     *   2. PG 응답이 완료 상태이면 우리 DB의 Payment/Order 상태를 동기화
     *   3. 동기화된 최종 상태를 반환
     *
     * 이를 통해 "콜백 미수신 → 결제 상태 불일치" 문제를 해소한다.
     */
    @GetMapping("/verify")
    public ApiResponse<PaymentV1Dto.PaymentResponse> verifyPayment(
            @LoginMember Member member,
            @RequestParam Long orderId) {

        PaymentInfo info = paymentFacade.verifyPayment(member.getId(), orderId);
        return ApiResponse.success(PaymentV1Dto.PaymentResponse.from(info));
    }
}
