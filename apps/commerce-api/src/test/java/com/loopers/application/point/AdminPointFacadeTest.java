package com.loopers.application.point;

import com.loopers.domain.point.PointService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminPointFacade 단위 테스트")
class AdminPointFacadeTest {

    @Mock
    private PointService pointService;

    @InjectMocks
    private AdminPointFacade adminPointFacade;

    @Nested
    @DisplayName("포인트를 충전할 때,")
    class ChargePoint {

        @Test
        @DisplayName("PointService를 호출하여 포인트를 충전한다.")
        void callsServiceToChargePoint() {
            // given
            Long memberId = 1L;
            int amount = 5000;
            String description = "관리자 충전";

            // when
            adminPointFacade.chargePoint(memberId, amount, description);

            // then
            verify(pointService, times(1)).chargePoint(memberId, amount, description);
        }

        @Test
        @DisplayName("포인트 정보가 없으면 예외가 전파된다.")
        void throwsException_whenPointNotFound() {
            // given
            Long memberId = 999L;
            int amount = 5000;
            String description = "관리자 충전";

            doThrow(new CoreException(ErrorType.NOT_FOUND, "포인트 정보를 찾을 수 없습니다."))
                    .when(pointService).chargePoint(memberId, amount, description);

            // when & then
            assertThatThrownBy(() -> adminPointFacade.chargePoint(memberId, amount, description))
                    .isInstanceOf(CoreException.class)
                    .satisfies(exception -> {
                        CoreException coreException = (CoreException) exception;
                        assertThat(coreException.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
                    });
        }

        @Test
        @DisplayName("충전 금액이 0 이하이면 예외가 전파된다.")
        void throwsException_whenAmountIsZeroOrNegative() {
            // given
            Long memberId = 1L;
            int amount = 0;
            String description = "관리자 충전";

            doThrow(new CoreException(ErrorType.BAD_REQUEST, "충전 금액은 0보다 커야 합니다."))
                    .when(pointService).chargePoint(memberId, amount, description);

            // when & then
            assertThatThrownBy(() -> adminPointFacade.chargePoint(memberId, amount, description))
                    .isInstanceOf(CoreException.class)
                    .satisfies(exception -> {
                        CoreException coreException = (CoreException) exception;
                        assertThat(coreException.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                    });
        }

        @Test
        @DisplayName("전달받은 인자를 그대로 PointService에 전달한다.")
        void passesArgumentsToService() {
            // given
            Long memberId = 5L;
            int amount = 10000;
            String description = "이벤트 충전";

            // when
            adminPointFacade.chargePoint(memberId, amount, description);

            // then
            verify(pointService).chargePoint(5L, 10000, "이벤트 충전");
        }
    }
}
