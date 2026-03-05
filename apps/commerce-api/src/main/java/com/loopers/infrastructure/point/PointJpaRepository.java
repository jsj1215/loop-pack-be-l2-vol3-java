package com.loopers.infrastructure.point;

import com.loopers.domain.point.Point;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/*
    Repository
    : JPA Repository - Point 전용
 */
public interface PointJpaRepository extends JpaRepository<Point, Long> {

    Optional<Point> findByMemberIdAndDeletedAtIsNull(Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Point p WHERE p.memberId = :memberId AND p.deletedAt IS NULL")
    Optional<Point> findByMemberIdWithLockAndDeletedAtIsNull(@Param("memberId") Long memberId);
}
