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
     */
    public void chargePoint(Long memberId, int amount, String description) {
        Point point = pointRepository.findByMemberId(memberId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "포인트 정보를 찾을 수 없습니다."));

        if (amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충전 금액은 0보다 커야 합니다.");
        }

        point.charge(amount);
        pointRepository.save(point);

        PointHistory history = PointHistory.createCharge(memberId, amount, point.getBalance(), description);
        pointHistoryRepository.save(history);
    }

    /**
     * 포인트 사용
     */
    public void usePoint(Long memberId, int amount, String description, Long orderId) {
        Point point = pointRepository.findByMemberId(memberId)
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
