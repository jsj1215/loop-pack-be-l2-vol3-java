package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * commerce-api의 Coupon 엔티티 복제 (읽기 전용).
 * 같은 DB를 공유하므로 직접 조회 가능.
 */
@Getter
@Entity
@Table(name = "coupon")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "max_issue_count", nullable = false)
    private int maxIssueCount;
}
