package com.loopers.application.event;

import com.loopers.domain.event.OrderFailedEvent;
import com.loopers.domain.event.OrderPaidEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문-결제 관련 이벤트를 수신하여 비즈니스 알림/로깅을 처리하는 리스너.
 *
 * 주문 플로우의 부가 로직(알림, 후처리 등)을 주요 로직에서 분리한다.
 * 현재는 로깅만 수행하지만, 향후 알림(이메일/푸시), 통계 적재 등으로 확장 가능.
 *
 * 트랜잭션 전략:
 * - {@code AFTER_COMMIT} — 주문/결제 상태가 확정된 후에만 실행
 * - {@code fallbackExecution=true} — 트랜잭션 컨텍스트 없이 이벤트가 발행되더라도 실행.
 *   OrderPaymentFacade의 processPayment는 트랜잭션 밖에서 이벤트를 발행하므로 이 설정이 필요하다.
 */
@Slf4j
@Component
public class OrderEventListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleOrderPaid(OrderPaidEvent event) {
        log.info("주문 결제 완료: orderId={}, memberId={}, totalAmount={}",
                event.orderId(), event.memberId(), event.totalAmount());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleOrderFailed(OrderFailedEvent event) {
        log.info("주문 결제 실패: orderId={}, memberId={}, reason={}",
                event.orderId(), event.memberId(), event.reason());
    }
}
