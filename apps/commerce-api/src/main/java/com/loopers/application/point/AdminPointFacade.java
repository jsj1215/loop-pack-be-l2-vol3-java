package com.loopers.application.point;

import com.loopers.domain.point.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class AdminPointFacade {

    private final PointService pointService;

    @Transactional
    public void chargePoint(Long memberId, int amount, String description) {
        pointService.chargePoint(memberId, amount, description);
    }
}
