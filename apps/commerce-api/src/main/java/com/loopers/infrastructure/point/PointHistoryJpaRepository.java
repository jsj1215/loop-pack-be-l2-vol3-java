package com.loopers.infrastructure.point;

import com.loopers.domain.point.PointHistory;
import org.springframework.data.jpa.repository.JpaRepository;

/*
    Repository
    : JPA Repository - PointHistory 전용
 */
public interface PointHistoryJpaRepository extends JpaRepository<PointHistory, Long> {
}
