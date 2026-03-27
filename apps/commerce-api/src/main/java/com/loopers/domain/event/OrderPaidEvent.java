package com.loopers.domain.event;

import java.util.List;

/**
 * 주문 결제 성공 시 발행되는 도메인 이벤트.
 *
 * 발행 시점:
 * - 전액 할인으로 결제 없이 주문 완료된 경우
 * - PG 결제가 SUCCESS로 확정된 경우
 *
 * 구독자:
 * - {@code OrderEventListener} — 주문 결제 완료 로깅
 * - {@code UserActionEventListener} — 유저 행동(ORDER_PAID) 로깅
 *
 * @param orderId          결제가 완료된 주문 ID
 * @param memberId         주문한 회원 ID
 * @param totalAmount      할인 전 주문 총 금액 (집계/통계용)
 * @param orderedProducts  주문 상품 목록 — Consumer가 product_metrics의 order_count를 상품별로 집계하기 위해 필요
 */
public record OrderPaidEvent(Long orderId, Long memberId, int totalAmount,
                              List<OrderedProduct> orderedProducts) {

    /**
     * 주문에 포함된 개별 상품 정보.
     *
     * @param productId 상품 ID
     * @param quantity  주문 수량
     */
    public record OrderedProduct(Long productId, int quantity) {}
}
