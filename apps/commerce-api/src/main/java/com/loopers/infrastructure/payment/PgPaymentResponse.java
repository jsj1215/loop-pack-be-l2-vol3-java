package com.loopers.infrastructure.payment;

/**
 * PG 결제 요청 응답 DTO — PG API 호출 결과 또는 Fallback에서 생성되는 응답 객체
 *
 * PG API가 정상 응답한 경우와, Resilience4j Fallback/수동 Fallback에서 생성한 경우를 모두 표현한다.
 * record: Java 16+에서 도입된 불변(Immutable) 데이터 클래스로, 필드/생성자/equals/hashCode/toString을 자동 생성한다.
 *
 * 응답 유형:
 *   - PG 접수 성공: transactionId가 존재 → isAccepted()=true → Facade에서 PENDING 처리
 *   - 타임아웃:     status="TIMEOUT"       → isTimeout()=true → Facade에서 UNKNOWN 처리
 *   - 실패:         status="FAILED"        → 둘 다 false      → Facade에서 FAILED 처리
 *
 * @param transactionId PG가 발급한 고유 트랜잭션 ID (접수 성공 시에만 존재, 실패/타임아웃 시 null)
 * @param orderId       우리 시스템의 주문 ID
 * @param status        PG 처리 상태 ("ACCEPTED", "TIMEOUT", "FAILED" 등)
 * @param message       상세 메시지 (실패 사유 등)
 */
public record PgPaymentResponse(
        String transactionId,
        String orderId,
        String status,
        String message) {

    /**
     * PG 접수 성공 여부 — transactionId가 발급되었으면 PG가 결제를 접수한 것이다.
     * PG 접수 ≠ 결제 완료: 접수 후 비동기로 처리하며, 콜백으로 최종 결과를 전달한다.
     */
    public boolean isAccepted() {
        return transactionId != null && !transactionId.isBlank()
                && !"FAILED".equals(status) && !"TIMEOUT".equals(status);
    }

    /**
     * 타임아웃 여부 — PG 응답을 받지 못한 상태.
     * PG가 요청을 수신했을 수도 있으므로 FAILED가 아닌 UNKNOWN으로 처리해야 한다.
     */
    public boolean isTimeout() {
        return "TIMEOUT".equals(status);
    }

    /** Fallback용 팩토리 — PG 호출 실패(서킷 OPEN, 리트라이 소진, PG 거부 등) 시 생성 */
    public static PgPaymentResponse failed(String message) {
        return new PgPaymentResponse(null, null, "FAILED", message);
    }

    /** Fallback용 팩토리 — PG 응답 타임아웃(TimeLimiter 초과, SocketTimeout) 시 생성 */
    public static PgPaymentResponse timeout() {
        return new PgPaymentResponse(null, null, "TIMEOUT", "PG 응답 타임아웃");
    }
}
