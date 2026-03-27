package com.loopers.domain.event;

/**
 * 상품 상세 조회 시 발행되는 도메인 이벤트.
 *
 * 조회 자체는 읽기 전용이므로 트랜잭션 커밋과 무관하게 발행된다 (fallbackExecution=true).
 * 향후 Kafka를 통해 조회 수 집계(product_metrics)에 활용 예정.
 *
 * 구독자:
 * - {@code UserActionEventListener} — 유저 행동(PRODUCT_VIEWED) 로깅
 *
 * @param productId 조회된 상품 ID
 * @param memberId  조회한 회원 ID (비로그인 시 null)
 */
public record ProductViewedEvent(Long productId, Long memberId) {
}
