package com.loopers.domain.event;

/**
 * 상품 좋아요/취소 시 발행되는 도메인 이벤트.
 *
 * 주요 로직(좋아요 상태 변경)과 부가 로직(좋아요 수 집계)을 분리하기 위해 이벤트로 설계.
 * 좋아요 상태 변경은 즉시 커밋되고, 집계는 이벤트 리스너에서 별도 트랜잭션으로 처리된다.
 * → 집계 실패와 무관하게 좋아요 자체는 성공 (Eventual Consistency)
 *
 * 구독자:
 * - {@code LikeEventListener} — likeCount 증가/감소 (AFTER_COMMIT, REQUIRES_NEW)
 * - {@code UserActionEventListener} — 유저 행동(PRODUCT_LIKED/UNLIKED) 로깅
 *
 * @param productId 좋아요 대상 상품 ID
 * @param memberId  좋아요를 누른 회원 ID
 * @param liked     true=좋아요, false=좋아요 취소
 */
public record ProductLikedEvent(Long productId, Long memberId, boolean liked) {
}
