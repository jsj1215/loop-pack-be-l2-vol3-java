package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

/**
 * 결제 도메인 엔티티 — 주문 1건에 대한 결제 정보와 상태 전이 로직을 캡슐화
 *
 * 설계 원칙:
 *   - 정적 팩토리 메서드(create)로만 생성 가능하며, 생성 시 필수 파라미터 검증을 수행한다.
 *   - 상태 전이 메서드(markXxx)에서 비즈니스 규칙(최종 상태 변경 불가)을 도메인 내부에서 보장한다.
 *   - Order와 1:1 관계 (orderId unique) — 하나의 주문에 하나의 결제만 존재한다.
 *
 * protected 기본 생성자: JPA가 리플렉션으로 엔티티를 생성하기 위해 필요하다.
 *   외부에서 new Payment()로 생성하는 것을 방지하면서도 JPA의 프록시 생성을 허용한다.
 */
@Getter
@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

    /** 결제 대상 주문 ID — Order 엔티티와 1:1 매핑, unique 제약으로 중복 결제 방지 */
    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "amount", nullable = false)
    private int amount;

    @Column(name = "card_type", nullable = false)
    private String cardType;

    @Column(name = "card_no", nullable = false)
    private String cardNo;

    /**
     * @Enumerated(EnumType.STRING): enum 값을 문자열("REQUESTED", "PENDING" 등)로 DB에 저장한다.
     * EnumType.ORDINAL(기본값)은 enum 순서 변경 시 데이터 불일치가 발생하므로 STRING을 사용한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    /** PG사가 발급한 고유 트랜잭션 ID — PG 접수 성공(PENDING) 이후 설정됨, 무결성 검증에 사용 */
    @Column(name = "pg_transaction_id")
    private String pgTransactionId;

    /** 결제 실패 사유 — FAILED 상태일 때 설정됨 (PG 거부 사유, 서킷브레이커 OPEN 등) */
    @Column(name = "failure_reason")
    private String failureReason;

    /** 낙관적 락 — 동시 상태 변경(PG 응답 + 콜백 동시 도착 등) 시 Lost Update 방지 */
    @Version
    @Column(name = "version")
    private Long version;

    /** JPA용 기본 생성자 — protected로 외부 직접 생성을 방지 */
    protected Payment() {}

    /** 내부 생성자 — 정적 팩토리 메서드(create)를 통해서만 호출됨, 초기 상태는 REQUESTED */
    private Payment(Long orderId, Long memberId, int amount, String cardType, String cardNo) {
        this.orderId = orderId;
        this.memberId = memberId;
        this.amount = amount;
        this.cardType = cardType;
        this.cardNo = cardNo;
        this.status = PaymentStatus.REQUESTED;
    }

    /**
     * 정적 팩토리 메서드 — Payment 생성 시 필수 파라미터를 검증한다.
     * 생성 즉시 REQUESTED 상태로 설정되며, PG API 호출 전의 초기 상태를 나타낸다.
     */
    public static Payment create(Long orderId, Long memberId, int amount, String cardType, String cardNo) {
        if (orderId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 ID는 필수입니다.");
        }
        if (memberId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "회원 ID는 필수입니다.");
        }
        if (amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 금액은 0보다 커야 합니다.");
        }
        if (cardType == null || cardType.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카드 종류는 필수입니다.");
        }
        if (cardNo == null || cardNo.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카드 번호는 필수입니다.");
        }
        return new Payment(orderId, memberId, amount, cardType, cardNo);
    }

    /** PG 접수 성공 → PENDING 전이: PG가 transactionId를 발급하고 비동기 처리를 시작한 상태 */
    public void markPending(String pgTransactionId) {
        validateNotFinalized();
        this.status = PaymentStatus.PENDING;
        this.pgTransactionId = pgTransactionId;
    }

    /** 결제 최종 성공 → SUCCESS 전이: PG 콜백 또는 verify 조회로 확인된 최종 상태 */
    public void markSuccess(String pgTransactionId) {
        validateNotFinalized();
        this.status = PaymentStatus.SUCCESS;
        this.pgTransactionId = pgTransactionId;
    }

    /** 결제 실패 → FAILED 전이: 실패 사유를 기록하며, 이후 resetForRetry()로 재시도 가능 */
    public void markFailed(String reason) {
        validateNotFinalized();
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
    }

    /** 상태 불명 → UNKNOWN 전이: PG 타임아웃 등으로 결과를 확인할 수 없을 때 */
    public void markUnknown() {
        validateNotFinalized();
        this.status = PaymentStatus.UNKNOWN;
    }

    /**
     * 실패한 결제를 재시도 가능 상태(REQUESTED)로 초기화한다.
     * FAILED 상태에서만 호출 가능하며, 결제 정보(금액, 카드)를 새로 설정하고
     * 이전 PG 트랜잭션 ID와 실패 사유를 초기화한다.
     * 이를 통해 동일 주문에 대해 새로운 결제 시도가 가능하다.
     */
    public void resetForRetry(int amount, String cardType, String cardNo) {
        if (this.status != PaymentStatus.FAILED) {
            throw new CoreException(ErrorType.CONFLICT, "실패한 결제만 재시도할 수 있습니다.");
        }
        this.amount = amount;
        this.cardType = cardType;
        this.cardNo = cardNo;
        this.status = PaymentStatus.REQUESTED;
        this.pgTransactionId = null;
        this.failureReason = null;
    }

    /**
     * 최종 상태(Finalized) 여부 — SUCCESS 또는 FAILED이면 더 이상 상태 변경이 불가하다.
     * 멱등성(Idempotency) 보장: 중복 콜백이나 중복 verify 조회가 와도 상태가 뒤집히지 않는다.
     */
    public boolean isFinalized() {
        return this.status == PaymentStatus.SUCCESS || this.status == PaymentStatus.FAILED;
    }

    /** 검증이 필요한 상태 여부 — REQUESTED/PENDING/UNKNOWN 상태는 verify API로 PG 직접 조회가 필요 */
    public boolean needsVerification() {
        return this.status == PaymentStatus.REQUESTED
                || this.status == PaymentStatus.PENDING
                || this.status == PaymentStatus.UNKNOWN;
    }

    /** 최종 상태에서의 상태 변경을 방지하는 가드(Guard) 메서드 */
    private void validateNotFinalized() {
        if (isFinalized()) {
            throw new CoreException(ErrorType.CONFLICT, "이미 처리 완료된 결제입니다.");
        }
    }
}
