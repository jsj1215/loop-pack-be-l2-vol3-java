package com.loopers.infrastructure.point;

import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/*
    PointRepository 구현체
    : 도메인 모델(Point)이 JPA 엔티티 역할을 겸하므로 변환 없이 직접 사용.
 */
@RequiredArgsConstructor
@Component
public class PointRepositoryImpl implements PointRepository {

    private final PointJpaRepository pointJpaRepository;

    @Override
    public Optional<Point> findByMemberId(Long memberId) {
        return pointJpaRepository.findByMemberIdAndDeletedAtIsNull(memberId);
    }

    @Override
    public Optional<Point> findByMemberIdWithLock(Long memberId) {
        return pointJpaRepository.findByMemberIdWithLockAndDeletedAtIsNull(memberId);
    }

    @Override
    public Point save(Point point) {
        return pointJpaRepository.save(point);
    }
}
