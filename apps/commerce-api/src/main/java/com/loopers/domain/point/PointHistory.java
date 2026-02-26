package com.loopers.domain.point;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "point_history")
public class PointHistory extends BaseEntity {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private PointType type;

    @Column(name = "amount", nullable = false)
    private int amount;

    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    @Column(name = "description")
    private String description;

    @Column(name = "order_id")
    private Long orderId;

    protected PointHistory() {}

    private PointHistory(Long memberId, PointType type, int amount, int balanceAfter,
                         String description, Long orderId) {
        this.memberId = memberId;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.description = description;
        this.orderId = orderId;
    }

    /**
     * 충전 이력 생성
     */
    public static PointHistory createCharge(Long memberId, int amount, int balanceAfter, String description) {
        return new PointHistory(memberId, PointType.CHARGE, amount, balanceAfter, description, null);
    }

    /**
     * 사용 이력 생성
     */
    public static PointHistory createUse(Long memberId, int amount, int balanceAfter, String description, Long orderId) {
        return new PointHistory(memberId, PointType.USE, amount, balanceAfter, description, orderId);
    }
}
