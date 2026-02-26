package com.loopers.interfaces.api.point;

import com.loopers.application.point.AdminPointFacade;
import com.loopers.domain.auth.Admin;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginAdmin;
import com.loopers.interfaces.api.point.dto.PointV1Dto;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/points")
public class AdminPointV1Controller {

    private final AdminPointFacade adminPointFacade;

    @PostMapping
    public ApiResponse<Void> chargePoint(
            @LoginAdmin Admin admin,
            @RequestBody PointV1Dto.ChargePointRequest request) {
        if (request.amount() <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충전 금액은 0보다 커야 합니다.");
        }

        adminPointFacade.chargePoint(request.memberId(), request.amount(), request.description());
        return ApiResponse.success(null);
    }
}
