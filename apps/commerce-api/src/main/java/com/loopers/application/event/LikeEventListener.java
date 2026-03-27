package com.loopers.application.event;

import com.loopers.domain.event.ProductLikedEvent;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 좋아요 집계 처리를 담당하는 이벤트 리스너.
 *
 * 좋아요/취소의 주요 로직(상태 변경)과 부가 로직(집계)을 분리하여,
 * 집계 실패가 좋아요 자체의 성공에 영향을 주지 않도록 한다 (Eventual Consistency).
 *
 * 트랜잭션 전략:
 * - {@code AFTER_COMMIT} — 좋아요 상태 변경이 커밋된 후에만 집계 실행
 * - {@code REQUIRES_NEW} — 발행자의 트랜잭션과 별도 트랜잭션으로 집계 처리.
 *   AFTER_COMMIT 시점에는 기존 트랜잭션이 이미 종료되었으므로
 *   새 트랜잭션을 열어야 DB 쓰기가 가능하다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class LikeEventListener {

    private final ProductService productService;

    /**
     * 좋아요 이벤트를 수신하여 상품의 likeCount를 증가/감소시킨다.
     *
     * 집계 실패 시 예외를 삼켜(catch) 로그만 남긴다.
     * 좋아요 상태는 이미 커밋되었으므로, 집계 실패가 사용자 응답에 영향을 주지 않는다.
     * 향후 Kafka 기반 집계로 전환 시 이 리스너는 Kafka Producer로 대체될 수 있다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleProductLiked(ProductLikedEvent event) {
        try {
            if (event.liked()) {
                productService.incrementLikeCount(event.productId());
            } else {
                productService.decrementLikeCount(event.productId());
            }
            log.info("좋아요 집계 완료: productId={}, memberId={}, liked={}",
                    event.productId(), event.memberId(), event.liked());
        } catch (Exception e) {
            log.error("좋아요 집계 실패: productId={}, memberId={}, liked={}",
                    event.productId(), event.memberId(), event.liked(), e);
        }
    }
}
