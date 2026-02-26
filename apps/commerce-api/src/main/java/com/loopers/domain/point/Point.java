package com.loopers.domain.point;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "point")
public class Point extends BaseEntity {

    @Column(name = "member_id", nullable = false, unique = true)
    private Long memberId;

    @Column(name = "balance", nullable = false)
    private int balance;

    protected Point() {}

    private Point(Long memberId, int balance) {
        this.memberId = memberId;
        this.balance = balance;
    }

    /**
     * 신규 생성 시 사용하는 정적 팩토리 메서드
     */
    public static Point create(Long memberId, int initialBalance) {
        if (initialBalance < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "초기 잔액은 0 이상이어야 합니다.");
        }
        return new Point(memberId, initialBalance);
    }

    public void charge(int amount) {
        if (amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충전 금액은 0보다 커야 합니다.");
        }
        this.balance += amount;
    }

    public void use(int amount) {
        if (amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "사용 금액은 0보다 커야 합니다.");
        }
        if (this.balance < amount) {
            throw new CoreException(ErrorType.BAD_REQUEST, "포인트가 부족합니다.");
        }
        this.balance -= amount;
    }
}
