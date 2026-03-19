package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 도메인 서비스 — 결제의 비즈니스 규칙과 트랜잭션 경계를 담당
 *
 * 각 메서드가 독립적인 @Transactional 단위이다.
 * Facade(PaymentFacade)가 이 서비스를 여러 번 호출하여 트랜잭션을 분리한다:
 *   [트랜잭션 1] initiatePayment → [트랜잭션 밖] PG 호출 → [트랜잭션 2] updatePaymentStatus
 *
 * Payment만 담당하며, Order 상태 변경은 Facade에서 조율한다.
 */
@RequiredArgsConstructor
@Component
public class PaymentService {

    private final PaymentRepository paymentRepository;

    /**
     * 결제 시작 — Payment 엔티티를 REQUESTED 상태로 생성/저장한다.
     *
     * 동작 흐름:
     *   1. 기존 결제가 있는 경우:
     *      - FAILED 상태 → resetForRetry()로 재시도 가능 상태로 초기화
     *      - 그 외(REQUESTED/PENDING/UNKNOWN/SUCCESS) → CONFLICT 예외 (중복 결제 방지)
     *   2. 기존 결제가 없는 경우 → 새 Payment 생성 후 저장
     *
     * 주문 검증(존재 여부, 소유자, 금액 계산)은 Facade에서 수행한다.
     *
     * @return 생성된(또는 재시도용으로 초기화된) Payment 엔티티
     */
    @Transactional
    public Payment initiatePayment(Long memberId, Long orderId, int amount, String cardType, String cardNo) {
        return paymentRepository.findByOrderId(orderId)
                .map(existing -> {
                    if (existing.getStatus() == PaymentStatus.FAILED) {
                        existing.resetForRetry(amount, cardType, cardNo);
                        return existing;
                    }
                    throw new CoreException(ErrorType.CONFLICT, "이미 결제가 진행 중인 주문입니다.");
                })
                .orElseGet(() -> paymentRepository.save(
                        Payment.create(orderId, memberId, amount, cardType, cardNo)));
    }

    /**
     * PG 게이트웨이 응답을 해석하여 Payment 상태를 업데이트한다.
     *
     * 3분기 분기:
     *   REQUESTED → PENDING  (PG 접수 성공, transactionId 발급됨)
     *   REQUESTED → UNKNOWN  (타임아웃, 결과 불확실)
     *   REQUESTED → FAILED   (명확한 실패: PG 거부, 서킷 OPEN 등)
     */
    @Transactional
    public void updatePaymentStatus(Long paymentId, PaymentGatewayResponse response) {
        Payment payment = findPayment(paymentId);

        if (response.accepted()) {
            payment.markPending(response.transactionId());
        } else if (response.timeout()) {
            payment.markUnknown();
        } else {
            payment.markFailed(response.message());
        }
    }

    /**
     * PG 응답 처리 — 원시값 기반 상태 전이 (테스트/통합 테스트에서 직접 호출)
     *
     *   - accepted=true  → markPending(txId)
     *   - accepted=false + failureReason!=null → markFailed(reason)
     *   - accepted=false + failureReason==null → markUnknown()
     */
    @Transactional
    public void handlePgResponse(Long paymentId, boolean accepted, String pgTransactionId, String failureReason) {
        Payment payment = findPayment(paymentId);

        if (accepted) {
            payment.markPending(pgTransactionId);
        } else if (failureReason != null) {
            payment.markFailed(failureReason);
        } else {
            payment.markUnknown();
        }
    }

    /**
     * PG 콜백/verify 결과를 Payment에 동기화한다.
     *
     * 동작 흐름:
     *   1. 이미 최종 상태(SUCCESS/FAILED)인 경우 → SKIPPED 반환 (멱등성 보장)
     *   2. PG 트랜잭션 ID 무결성 검증: 기존에 저장된 txId와 다르면 위변조 가능성 → BAD_REQUEST
     *   3. PG 상태에 따라 Payment 상태만 업데이트:
     *      - "SUCCESS" → Payment.markSuccess() → SyncResult.SUCCESS 반환
     *      - 그 외    → Payment.markFailed()  → SyncResult.FAILED 반환
     *
     * Order 상태 변경은 Facade에서 SyncResult에 따라 별도 트랜잭션으로 처리한다.
     */
    @Transactional
    public SyncResult syncPaymentResult(Long orderId, String pgTransactionId, String pgStatus) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "결제 정보를 찾을 수 없습니다."));

        if (payment.isFinalized()) {
            return SyncResult.SKIPPED;
        }

        validateTransactionId(payment, pgTransactionId);

        if ("SUCCESS".equals(pgStatus)) {
            payment.markSuccess(pgTransactionId);
            return SyncResult.SUCCESS;
        } else {
            payment.markFailed(pgStatus);
            return SyncResult.FAILED;
        }
    }

    private void validateTransactionId(Payment payment, String pgTransactionId) {
        if (payment.getPgTransactionId() != null
                && !payment.getPgTransactionId().equals(pgTransactionId)) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                    "PG 트랜잭션 ID가 일치하지 않습니다.");
        }
    }

    public Payment findPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "결제 정보를 찾을 수 없습니다."));
    }

    /**
     * REQUESTED 상태의 결제를 FAILED로 마킹한다.
     * PG에 요청이 도달하지 않은 것으로 판단되는 경우(verify 조회 결과 없음) 사용한다.
     *
     * @return true: 상태 변경됨 (REQUESTED → FAILED), false: 변경 없음 (REQUESTED가 아니거나 결제 없음)
     */
    @Transactional
    public boolean markRequestedAsFailed(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .filter(p -> p.getStatus() == PaymentStatus.REQUESTED)
                .map(p -> {
                    p.markFailed("PG 요청 미도달 — 상태 확인 시 결과 없음");
                    return true;
                })
                .orElse(false);
    }

    public Payment findPaymentByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "결제 정보를 찾을 수 없습니다."));
    }
}
