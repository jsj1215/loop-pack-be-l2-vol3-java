package com.loopers.interfaces.api.payment.dto;

import com.loopers.application.payment.PaymentInfo;
import com.loopers.domain.payment.PaymentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.ZonedDateTime;

/**
 * 결제 API 요청/응답 DTO 모음 — Interfaces 계층에서 JSON 직렬화/역직렬화에 사용
 *
 * DTO(Data Transfer Object)를 내부 static record로 그룹화하여:
 *   - 관련 DTO를 한 파일에서 관리하고
 *   - PaymentV1Dto.CreatePaymentRequest 형태로 소속을 명확히 한다
 *
 * V1/V2 컨트롤러가 동일한 DTO를 공유한다 (요청/응답 형식이 동일하므로).
 */
public class PaymentV1Dto {

    /**
     * 결제 생성 요청 — 클라이언트가 결제를 시작할 때 전송하는 JSON 바디
     *
     * @param orderId  결제할 주문 ID
     * @param cardType 카드 종류 (VISA, MASTER 등)
     * @param cardNo   카드 번호
     */
    public record CreatePaymentRequest(
            @NotNull(message = "주문 ID는 필수입니다.")
            Long orderId,
            @NotBlank(message = "카드 종류는 필수입니다.")
            String cardType,
            @NotBlank(message = "카드 번호는 필수입니다.")
            String cardNo) {}

    /**
     * 결제 응답 — 결제 생성/검증 API의 응답 바디
     * Application 계층의 PaymentInfo를 Interfaces 계층 DTO로 변환한다.
     * memberId를 제외하는 이유: 클라이언트에게 다른 사용자의 ID를 노출할 필요가 없다.
     */
    public record PaymentResponse(
            Long id,
            Long orderId,
            int amount,
            String cardType,
            PaymentStatus status,
            String pgTransactionId,
            String failureReason,
            ZonedDateTime createdAt) {

        /** Application DTO(PaymentInfo)로부터 Interfaces DTO를 생성하는 팩토리 메서드 */
        public static PaymentResponse from(PaymentInfo info) {
            return new PaymentResponse(
                    info.id(),
                    info.orderId(),
                    info.amount(),
                    info.cardType(),
                    info.status(),
                    info.pgTransactionId(),
                    info.failureReason(),
                    info.createdAt());
        }
    }

    /**
     * PG 콜백 요청 — PG 서버가 결제 처리 완료 후 Webhook으로 전송하는 JSON 바디
     * PG가 보내는 데이터이므로 필드 타입이 모두 String이다 (외부 시스템 스펙에 맞춤).
     *
     * @param transactionId PG가 발급한 고유 트랜잭션 ID
     * @param orderId       우리 시스템의 주문 ID (String — PG 스펙)
     * @param status        PG 처리 결과 ("SUCCESS", "FAILED", "LIMIT_EXCEEDED", "INVALID_CARD" 등)
     * @param message       PG가 전달하는 상세 메시지
     */
    public record PgCallbackRequest(
            String transactionId,
            String orderId,
            String status,
            String message) {}
}
