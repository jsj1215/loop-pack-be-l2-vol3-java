package com.loopers.domain.point;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/*
  [단위 테스트]
  대상 : PointHistory
  사용 라이브러리 : JUnit 5, AssertJ

  특징:
  - Spring Context 불필요 -> 빠른 실행
  - Docker/DB 불필요
 */
@DisplayName("PointHistory 도메인 모델")
class PointHistoryTest {

    @Nested
    @DisplayName("충전 이력을 생성할 때,")
    class CreateCharge {

        @Test
        @DisplayName("타입이 CHARGE로 설정되고 orderId는 null이다.")
        void createsChargeHistory_withCorrectType() {
            // given
            Long memberId = 1L;
            int amount = 1000;
            int balanceAfter = 1000;
            String description = "관리자 충전";

            // when
            PointHistory history = PointHistory.createCharge(memberId, amount, balanceAfter, description);

            // then
            assertAll(
                    () -> assertThat(history.getId()).isEqualTo(0L),
                    () -> assertThat(history.getMemberId()).isEqualTo(1L),
                    () -> assertThat(history.getType()).isEqualTo(PointType.CHARGE),
                    () -> assertThat(history.getAmount()).isEqualTo(1000),
                    () -> assertThat(history.getBalanceAfter()).isEqualTo(1000),
                    () -> assertThat(history.getDescription()).isEqualTo("관리자 충전"),
                    () -> assertThat(history.getOrderId()).isNull(),
                    () -> assertThat(history.getCreatedAt()).isNull());
        }
    }

    @Nested
    @DisplayName("사용 이력을 생성할 때,")
    class CreateUse {

        @Test
        @DisplayName("타입이 USE로 설정되고 orderId가 포함된다.")
        void createsUseHistory_withCorrectTypeAndOrderId() {
            // given
            Long memberId = 1L;
            int amount = 500;
            int balanceAfter = 500;
            String description = "주문 사용";
            Long orderId = 100L;

            // when
            PointHistory history = PointHistory.createUse(memberId, amount, balanceAfter, description, orderId);

            // then
            assertAll(
                    () -> assertThat(history.getId()).isEqualTo(0L),
                    () -> assertThat(history.getMemberId()).isEqualTo(1L),
                    () -> assertThat(history.getType()).isEqualTo(PointType.USE),
                    () -> assertThat(history.getAmount()).isEqualTo(500),
                    () -> assertThat(history.getBalanceAfter()).isEqualTo(500),
                    () -> assertThat(history.getDescription()).isEqualTo("주문 사용"),
                    () -> assertThat(history.getOrderId()).isEqualTo(100L),
                    () -> assertThat(history.getCreatedAt()).isNull());
        }
    }
}
