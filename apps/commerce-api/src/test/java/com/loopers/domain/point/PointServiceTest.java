package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [단위 테스트 - Service with Mock]
 *
 * 테스트 대상: PointService
 * 테스트 유형: 단위 테스트 (Mock 사용)
 * 테스트 더블: Mock (PointRepository, PointHistoryRepository)
 *
 * 사용 라이브러리:
 * - JUnit 5 (org.junit.jupiter)
 * - Mockito (org.mockito)
 * - AssertJ (org.assertj.core.api)
 *
 * 특징:
 * - Spring Context 불필요 -> 빠른 실행
 * - Docker/DB 불필요
 * - 의존성을 Mock으로 대체하여 테스트 대상만 격리 테스트
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PointService 단위 테스트")
class PointServiceTest {

    @Mock
    private PointRepository pointRepository;

    @Mock
    private PointHistoryRepository pointHistoryRepository;

    @InjectMocks
    private PointService pointService;

    private Point createPointWithId(Long id, Long memberId, int balance) {
        Point point = Point.create(memberId, balance);
        ReflectionTestUtils.setField(point, "id", id);
        return point;
    }

    @Nested
    @DisplayName("포인트를 생성할 때,")
    class CreatePoint {

        @Test
        @DisplayName("회원 ID로 잔액 0인 포인트가 생성된다.")
        void createsPointWithZeroBalance() {
            // given
            Long memberId = 1L;
            Point savedPoint = createPointWithId(1L, memberId, 0);

            when(pointRepository.save(any(Point.class))).thenReturn(savedPoint);

            // when
            Point result = pointService.createPoint(memberId);

            // then
            assertAll(
                    () -> assertThat(result.getMemberId()).isEqualTo(1L),
                    () -> assertThat(result.getBalance()).isEqualTo(0),
                    () -> verify(pointRepository, times(1)).save(any(Point.class)));
        }
    }

    @Nested
    @DisplayName("포인트를 충전할 때,")
    class ChargePoint {

        @Test
        @DisplayName("포인트 정보가 없으면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenPointNotExists() {
            // given
            Long memberId = 1L;

            when(pointRepository.findByMemberId(memberId)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> pointService.chargePoint(memberId, 1000, "관리자 충전"));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND),
                    () -> verify(pointRepository, never()).save(any(Point.class)),
                    () -> verify(pointHistoryRepository, never()).save(any(PointHistory.class)));
        }

        @Test
        @DisplayName("충전 금액이 0 이하이면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenAmountIsZeroOrNegative() {
            // given
            Long memberId = 1L;
            Point point = createPointWithId(1L, memberId, 1000);

            when(pointRepository.findByMemberId(memberId)).thenReturn(Optional.of(point));

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> pointService.chargePoint(memberId, 0, "관리자 충전"));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("충전 금액은 0보다 커야 합니다."),
                    () -> verify(pointRepository, never()).save(any(Point.class)),
                    () -> verify(pointHistoryRepository, never()).save(any(PointHistory.class)));
        }

        @Test
        @DisplayName("정상 충전 시 잔액이 증가하고 이력이 저장된다.")
        void chargesPointAndSavesHistory_whenValid() {
            // given
            Long memberId = 1L;
            Point point = createPointWithId(1L, memberId, 1000);

            when(pointRepository.findByMemberId(memberId)).thenReturn(Optional.of(point));
            when(pointRepository.save(any(Point.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointHistoryRepository.save(any(PointHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            pointService.chargePoint(memberId, 500, "관리자 충전");

            // then
            assertAll(
                    () -> assertThat(point.getBalance()).isEqualTo(1500),
                    () -> verify(pointRepository, times(1)).save(any(Point.class)),
                    () -> verify(pointHistoryRepository, times(1)).save(any(PointHistory.class)));
        }
    }

    @Nested
    @DisplayName("포인트를 사용할 때,")
    class UsePoint {

        @Test
        @DisplayName("포인트 정보가 없으면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenPointNotExists() {
            // given
            Long memberId = 1L;

            when(pointRepository.findByMemberId(memberId)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> pointService.usePoint(memberId, 500, "주문 사용", 100L));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND),
                    () -> verify(pointRepository, never()).save(any(Point.class)),
                    () -> verify(pointHistoryRepository, never()).save(any(PointHistory.class)));
        }

        @Test
        @DisplayName("잔액이 부족하면 BAD_REQUEST 예외가 발생한다.")
        void throwsBadRequest_whenInsufficientBalance() {
            // given
            Long memberId = 1L;
            Point point = createPointWithId(1L, memberId, 100);

            when(pointRepository.findByMemberId(memberId)).thenReturn(Optional.of(point));

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> pointService.usePoint(memberId, 500, "주문 사용", 100L));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                    () -> assertThat(exception.getMessage()).contains("포인트가 부족합니다."),
                    () -> verify(pointRepository, never()).save(any(Point.class)),
                    () -> verify(pointHistoryRepository, never()).save(any(PointHistory.class)));
        }

        @Test
        @DisplayName("정상 사용 시 잔액이 감소하고 이력이 저장된다.")
        void usesPointAndSavesHistory_whenValid() {
            // given
            Long memberId = 1L;
            Point point = createPointWithId(1L, memberId, 1000);

            when(pointRepository.findByMemberId(memberId)).thenReturn(Optional.of(point));
            when(pointRepository.save(any(Point.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pointHistoryRepository.save(any(PointHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            pointService.usePoint(memberId, 300, "주문 사용", 100L);

            // then
            assertAll(
                    () -> assertThat(point.getBalance()).isEqualTo(700),
                    () -> verify(pointRepository, times(1)).save(any(Point.class)),
                    () -> verify(pointHistoryRepository, times(1)).save(any(PointHistory.class)));
        }
    }

    @Nested
    @DisplayName("포인트 잔액을 조회할 때,")
    class GetBalance {

        @Test
        @DisplayName("포인트 정보가 없으면 NOT_FOUND 예외가 발생한다.")
        void throwsNotFound_whenPointNotExists() {
            // given
            Long memberId = 1L;

            when(pointRepository.findByMemberId(memberId)).thenReturn(Optional.empty());

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> pointService.getBalance(memberId));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @Test
        @DisplayName("포인트 정보가 있으면 잔액을 반환한다.")
        void returnsBalance_whenPointExists() {
            // given
            Long memberId = 1L;
            Point point = createPointWithId(1L, memberId, 5000);

            when(pointRepository.findByMemberId(memberId)).thenReturn(Optional.of(point));

            // when
            int balance = pointService.getBalance(memberId);

            // then
            assertThat(balance).isEqualTo(5000);
        }
    }
}
