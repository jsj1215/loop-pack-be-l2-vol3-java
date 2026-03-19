package com.loopers.domain.payment;

/**
 * 결제 상태 (Payment Status) — 비동기 PG 연동에서의 결제 생명주기(Lifecycle)를 표현
 *
 * 상태 전이 다이어그램:
 *
 *   [생성]
 *     │
 *     ▼
 *   REQUESTED ──── PG 접수 성공 ────→ PENDING ──── PG 콜백(SUCCESS) ──→ SUCCESS (최종)
 *     │                                 │
 *     │                                 └── PG 콜백(FAILED) ──→ FAILED (최종)
 *     │
 *     ├── PG 타임아웃 ──→ UNKNOWN ──── verify 조회 ──→ SUCCESS 또는 FAILED
 *     │
 *     └── PG 실패(서킷 OPEN, 리트라이 소진 등) ──→ FAILED (최종, 재시도 가능)
 *
 *   최종 상태(Finalized): SUCCESS, FAILED → 더 이상 상태 변경 불가
 *   FAILED 상태의 결제는 resetForRetry()로 REQUESTED로 되돌려 재시도 가능
 */
public enum PaymentStatus {
    /** 결제 요청 생성됨 — PG API 호출 전 초기 상태 */
    REQUESTED,

    /** PG 접수 완료 — PG가 결제 요청을 수신하고 transactionId를 발급함, 비동기 처리 중 (콜백 대기) */
    PENDING,

    /** 결제 성공 — PG 콜백 또는 verify 조회를 통해 확인된 최종 성공 상태 */
    SUCCESS,

    /** 결제 실패 — PG 거부, 서킷브레이커 OPEN, 리트라이 소진 등의 최종 실패 상태 (재시도 가능) */
    FAILED,

    /**
     * 상태 불명 — PG 응답 타임아웃 등으로 결제 처리 결과를 확인할 수 없는 모호한 상태.
     * PG가 요청을 수신했을 수도, 수신하지 못했을 수도 있다.
     * verify API로 PG에 직접 조회하거나 PG 콜백을 대기하여 최종 상태를 확정짓는다.
     */
    UNKNOWN
}
