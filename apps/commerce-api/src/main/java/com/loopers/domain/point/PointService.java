package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
/*
    Service
    : 비즈니스 로직을 처리하는 객체

    - @RequiredArgsConstructor : 생성자 주입
    - @Component : 스프링 빈으로 등록
*/
@RequiredArgsConstructor
@Component
public class PointService {

    private final PointRepository pointRepository;
    private final PointHistoryRepository pointHistoryRepository;

    /**
     * 포인트 생성 (회원가입 시 초기 잔액 0)
     */
    public Point createPoint(Long memberId) {
        Point point = Point.create(memberId, 0);
        return pointRepository.save(point);
    }

    /**
     * 포인트 충전
     * 반드시 상위 레이어(@Transactional)의 트랜잭션 내에서 호출되어야 한다.
     */
    public void chargePoint(Long memberId, int amount, String description) {
        Point point = pointRepository.findByMemberIdWithLock(memberId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "포인트 정보를 찾을 수 없습니다."));

        point.charge(amount);
        pointRepository.save(point);

        PointHistory history = PointHistory.createCharge(memberId, amount, point.getBalance(), description);
        pointHistoryRepository.save(history);
    }

    /**
     * 포인트 사용
     * 반드시 상위 레이어(@Transactional)의 트랜잭션 내에서 호출되어야 한다.
     */
    public void usePoint(Long memberId, int amount, String description, Long orderId) {
        Point point = pointRepository.findByMemberIdWithLock(memberId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "포인트 정보를 찾을 수 없습니다."));

        point.use(amount);
        pointRepository.save(point);

        PointHistory history = PointHistory.createUse(memberId, amount, point.getBalance(), description, orderId);
        pointHistoryRepository.save(history);
    }

    /**
     * 포인트 잔액 조회
     */
    public int getBalance(Long memberId) {
        Point point = pointRepository.findByMemberId(memberId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "포인트 정보를 찾을 수 없습니다."));

        return point.getBalance();
    }
}
