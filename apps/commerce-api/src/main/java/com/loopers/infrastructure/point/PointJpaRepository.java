package com.loopers.infrastructure.point;

import com.loopers.domain.point.Point;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/*
    Repository
    : JPA Repository - Point 전용
 */
public interface PointJpaRepository extends JpaRepository<Point, Long> {

    Optional<Point> findByMemberIdAndDeletedAtIsNull(Long memberId);
}
