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
        return new Point(memberId, initialBalance);
    }

    /**
     * 포인트 충전
     */
    public void charge(int amount) {
        this.balance += amount;
    }

    /**
     * 포인트 사용
     */
    public void use(int amount) {
        if (this.balance < amount) {
            throw new CoreException(ErrorType.BAD_REQUEST, "포인트가 부족합니다.");
        }
        this.balance -= amount;
    }
}
