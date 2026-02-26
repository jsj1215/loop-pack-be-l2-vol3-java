package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [통합 테스트 - Service Integration]
 *
 * 테스트 대상: PointService (Domain Layer)
 * 테스트 유형: 통합 테스트 (Integration Test)
 * 테스트 범위: Service → Repository → Database
 */
@SpringBootTest
class PointServiceIntegrationTest {

    @Autowired
    private PointService pointService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("포인트를 생성할 때,")
    @Nested
    class CreatePoint {

        @Test
        @DisplayName("회원 ID로 포인트를 생성하면, 잔액 0으로 생성된다.")
        void createsPointWithZeroBalance() {
            // given
            Long memberId = 1L;

            // when
            Point point = pointService.createPoint(memberId);

            // then
            assertAll(
                    () -> assertThat(point.getId()).isNotNull(),
                    () -> assertThat(point.getMemberId()).isEqualTo(memberId),
                    () -> assertThat(point.getBalance()).isEqualTo(0)
            );
        }
    }

    @DisplayName("포인트를 충전할 때,")
    @Nested
    class ChargePoint {

        @Test
        @DisplayName("유효한 금액으로 충전하면, 잔액이 증가한다.")
        void chargesPoint_whenValidAmount() {
            // given
            Long memberId = 1L;
            pointService.createPoint(memberId);

            // when
            pointService.chargePoint(memberId, 10000, "이벤트 충전");

            // then
            int balance = pointService.getBalance(memberId);
            assertThat(balance).isEqualTo(10000);
        }

        @Test
        @DisplayName("존재하지 않는 memberId면, NOT_FOUND 예외가 발생한다.")
        void throwsException_whenMemberNotFound() {
            // given
            Long nonExistentMemberId = 999L;

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> pointService.chargePoint(nonExistentMemberId, 10000, "충전"));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("포인트를 사용할 때,")
    @Nested
    class UsePoint {

        @Test
        @DisplayName("잔액 이내로 사용하면, 잔액이 감소한다.")
        void usesPoint_whenEnoughBalance() {
            // given
            Long memberId = 1L;
            pointService.createPoint(memberId);
            pointService.chargePoint(memberId, 10000, "이벤트 충전");

            // when
            pointService.usePoint(memberId, 3000, "주문 사용", 1L);

            // then
            int balance = pointService.getBalance(memberId);
            assertThat(balance).isEqualTo(7000);
        }

        @Test
        @DisplayName("잔액이 부족하면, BAD_REQUEST 예외가 발생한다.")
        void throwsException_whenInsufficientBalance() {
            // given
            Long memberId = 1L;
            pointService.createPoint(memberId);
            pointService.chargePoint(memberId, 5000, "이벤트 충전");

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> pointService.usePoint(memberId, 10000, "주문 사용", 1L));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("포인트가 부족")
            );
        }
    }

    @DisplayName("포인트 잔액을 조회할 때,")
    @Nested
    class GetBalance {

        @Test
        @DisplayName("충전과 사용 후 잔액을 조회하면, 정확한 잔액을 반환한다.")
        void returnsCorrectBalance_afterChargeAndUse() {
            // given
            Long memberId = 1L;
            pointService.createPoint(memberId);
            pointService.chargePoint(memberId, 10000, "이벤트 충전");
            pointService.usePoint(memberId, 3000, "주문 사용", 1L);

            // when
            int balance = pointService.getBalance(memberId);

            // then
            assertThat(balance).isEqualTo(7000);
        }
    }
}
