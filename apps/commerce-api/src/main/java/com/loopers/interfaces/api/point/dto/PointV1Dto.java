package com.loopers.interfaces.api.point.dto;

public class PointV1Dto {

    public record ChargePointRequest(
            Long memberId,
            int amount,
            String description) {
    }
}
