package com.loopers.domain.event;

/**
 * 주문 결제 실패 시 발행되는 도메인 이벤트.
 *
 * 발행 시점:
 * - PG 결제 응답이 FAILED인 경우 (보상 트랜잭션 실행 후 발행)
 * - 결제 처리 중 예외가 발생한 경우 (보상 트랜잭션 실행 후 발행)
 *
 * 구독자:
 * - {@code OrderEventListener} — 주문 결제 실패 로깅
 * - {@code UserActionEventListener} — 유저 행동(ORDER_FAILED) 로깅
 *
 * @param orderId  결제가 실패한 주문 ID
 * @param memberId 주문한 회원 ID
 * @param reason   실패 사유 (PG 응답 메시지 또는 예외 메시지)
 */
public record OrderFailedEvent(Long orderId, Long memberId, String reason) {
}
