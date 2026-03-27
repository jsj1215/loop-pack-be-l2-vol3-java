package com.loopers.application.event;

import com.loopers.domain.event.OrderFailedEvent;
import com.loopers.domain.event.OrderPaidEvent;
import com.loopers.domain.event.ProductLikedEvent;
import com.loopers.domain.event.ProductViewedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 유저 행동 로깅 전용 이벤트 리스너.
 *
 * 모든 유저 행동(주문, 좋아요, 조회 등)을 서버 레벨에서 통합 로깅한다.
 * 각 비즈니스 리스너(OrderEventListener, LikeEventListener)와 관심사를 분리하여,
 * 비즈니스 처리와 유저 행동 추적을 독립적으로 관리한다.
 *
 * 향후 Kafka로 유저 행동 이벤트를 발행하면 외부 분석 시스템(analytics)에서 소비할 수 있다.
 *
 * 트랜잭션 전략:
 * - {@code AFTER_COMMIT} — 실제로 커밋된 행동만 로깅 (롤백된 행동은 기록하지 않음)
 * - {@code fallbackExecution=true} — 트랜잭션 없이 발행된 이벤트도 수신.
 *   주문 이벤트(OrderPaymentFacade)와 상품 조회 이벤트(readOnly 트랜잭션)가 이에 해당
 * - ProductLikedEvent만 fallbackExecution 미설정 — 좋아요는 반드시 쓰기 트랜잭션 내에서
 *   발행되므로 AFTER_COMMIT으로 충분
 */
@Slf4j
@Component
public class UserActionEventListener {

    /** 주문 결제 성공 행동 로깅 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void logOrderPaid(OrderPaidEvent event) {
        log.info("[UserAction] ORDER_PAID: memberId={}, orderId={}, totalAmount={}",
                event.memberId(), event.orderId(), event.totalAmount());
    }

    /** 주문 결제 실패 행동 로깅 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void logOrderFailed(OrderFailedEvent event) {
        log.info("[UserAction] ORDER_FAILED: memberId={}, orderId={}, reason={}",
                event.memberId(), event.orderId(), event.reason());
    }

    /** 상품 좋아요/취소 행동 로깅. 쓰기 트랜잭션 커밋 후에만 실행된다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void logProductLiked(ProductLikedEvent event) {
        String action = event.liked() ? "PRODUCT_LIKED" : "PRODUCT_UNLIKED";
        log.info("[UserAction] {}: memberId={}, productId={}",
                action, event.memberId(), event.productId());
    }

    /** 상품 조회 행동 로깅. readOnly 트랜잭션이므로 fallbackExecution으로 수신. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void logProductViewed(ProductViewedEvent event) {
        log.info("[UserAction] PRODUCT_VIEWED: memberId={}, productId={}",
                event.memberId(), event.productId());
    }
}
