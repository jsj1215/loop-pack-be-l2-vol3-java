package com.loopers.infrastructure.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ManualCircuitBreaker 단위 테스트")
class ManualCircuitBreakerTest {

    private ManualCircuitBreaker createCircuitBreaker(Clock clock) {
        return new ManualCircuitBreaker(
                10,    // slidingWindowSize
                50.0,  // failureRateThreshold
                10000, // waitDurationInOpenStateMs (10초)
                2,     // permittedCallsInHalfOpen
                clock
        );
    }

    private ManualCircuitBreaker createCircuitBreaker() {
        return createCircuitBreaker(Clock.systemDefaultZone());
    }

    @Nested
    @DisplayName("CLOSED 상태에서")
    class ClosedState {

        @Test
        @DisplayName("초기 상태는 CLOSED이고 호출이 허용된다.")
        void initialStateIsClosed() {
            // given
            ManualCircuitBreaker cb = createCircuitBreaker();

            // then
            assertThat(cb.getState()).isEqualTo(ManualCircuitBreaker.State.CLOSED);
            assertThat(cb.isCallPermitted()).isTrue();
        }

        @Test
        @DisplayName("슬라이딩 윈도우가 가득 차기 전에는 OPEN으로 전이하지 않는다.")
        void doesNotOpenBeforeWindowIsFull() {
            // given
            ManualCircuitBreaker cb = createCircuitBreaker();

            // when - 9번 실패 (윈도우 10개 미만)
            for (int i = 0; i < 9; i++) {
                cb.isCallPermitted();
                cb.recordFailure();
            }

            // then
            assertThat(cb.getState()).isEqualTo(ManualCircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("실패율이 임계치(50%) 이상이면 OPEN으로 전이한다.")
        void transitionsToOpenWhenFailureRateExceedsThreshold() {
            // given
            ManualCircuitBreaker cb = createCircuitBreaker();

            // when - 5번 성공, 5번 실패 (실패율 50%)
            for (int i = 0; i < 5; i++) {
                cb.isCallPermitted();
                cb.recordSuccess();
            }
            for (int i = 0; i < 5; i++) {
                cb.isCallPermitted();
                cb.recordFailure();
            }

            // then
            assertThat(cb.getState()).isEqualTo(ManualCircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("실패율이 임계치 미만이면 CLOSED를 유지한다.")
        void staysClosedWhenFailureRateBelowThreshold() {
            // given
            ManualCircuitBreaker cb = createCircuitBreaker();

            // when - 6번 성공, 4번 실패 (실패율 40%)
            for (int i = 0; i < 6; i++) {
                cb.isCallPermitted();
                cb.recordSuccess();
            }
            for (int i = 0; i < 4; i++) {
                cb.isCallPermitted();
                cb.recordFailure();
            }

            // then
            assertThat(cb.getState()).isEqualTo(ManualCircuitBreaker.State.CLOSED);
        }
    }

    @Nested
    @DisplayName("OPEN 상태에서")
    class OpenState {

        private ManualCircuitBreaker openCircuitBreaker(Clock clock) {
            ManualCircuitBreaker cb = createCircuitBreaker(clock);
            // 10번 실패 → OPEN
            for (int i = 0; i < 10; i++) {
                cb.isCallPermitted();
                cb.recordFailure();
            }
            return cb;
        }

        @Test
        @DisplayName("호출이 차단된다.")
        void callsAreRejected() {
            // given
            ManualCircuitBreaker cb = openCircuitBreaker(Clock.systemDefaultZone());

            // then
            assertThat(cb.getState()).isEqualTo(ManualCircuitBreaker.State.OPEN);
            assertThat(cb.isCallPermitted()).isFalse();
        }

        @Test
        @DisplayName("대기 시간이 지나면 HALF_OPEN으로 전이한다.")
        void transitionsToHalfOpenAfterWaitDuration() {
            // given - waitDuration=0으로 즉시 HALF_OPEN 전이 가능한 서킷브레이커
            ManualCircuitBreaker cb = new ManualCircuitBreaker(10, 50.0, 0, 2);

            // 10번 실패 → OPEN
            for (int i = 0; i < 10; i++) {
                cb.isCallPermitted();
                cb.recordFailure();
            }
            assertThat(cb.getState()).isEqualTo(ManualCircuitBreaker.State.OPEN);

            // when - 대기 시간(0ms)이 지났으므로 isCallPermitted() 호출 시 HALF_OPEN 전이
            boolean permitted = cb.isCallPermitted();

            // then
            assertThat(permitted).isTrue();
            assertThat(cb.getState()).isEqualTo(ManualCircuitBreaker.State.HALF_OPEN);
        }
    }

    @Nested
    @DisplayName("HALF_OPEN 상태에서")
    class HalfOpenState {

        private ManualCircuitBreaker halfOpenCircuitBreaker() {
            // 고정 시간으로 OPEN 만든 후, 10초 뒤 시간의 Clock으로 교체하는 대신
            // 직접 상태 전이를 유도
            Instant baseTime = Instant.now();
            Clock clock = Clock.fixed(baseTime.plusMillis(20000), ZoneId.systemDefault());

            // 10초 전에 OPEN된 것처럼 구성
            ManualCircuitBreaker cb = new ManualCircuitBreaker(10, 50.0, 10000, 2,
                    Clock.fixed(baseTime, ZoneId.systemDefault()));

            for (int i = 0; i < 10; i++) {
                cb.isCallPermitted();
                cb.recordFailure();
            }
            assertThat(cb.getState()).isEqualTo(ManualCircuitBreaker.State.OPEN);

            // 시간이 지난 Clock으로 새 CB 생성하여 HALF_OPEN 전이 테스트
            // → 대신 단순하게: 직접 HALF_OPEN 유도 가능한 구조로 테스트
            return cb;
        }

        @Test
        @DisplayName("허용된 호출 수만큼만 통과시킨다.")
        void permitsLimitedCalls() {
            // given
            Instant base = Instant.now();
            Clock openClock = Clock.fixed(base, ZoneId.systemDefault());
            ManualCircuitBreaker cb = new ManualCircuitBreaker(10, 50.0, 5000, 2, openClock);

            // OPEN으로 전이
            for (int i = 0; i < 10; i++) {
                cb.isCallPermitted();
                cb.recordFailure();
            }
            assertThat(cb.getState()).isEqualTo(ManualCircuitBreaker.State.OPEN);

            // when - 5초 뒤 시간의 Clock으로 교체된 CB
            Clock halfOpenClock = Clock.fixed(base.plusMillis(6000), ZoneId.systemDefault());
            ManualCircuitBreaker cb2 = new ManualCircuitBreaker(10, 50.0, 5000, 2, halfOpenClock);
            for (int i = 0; i < 10; i++) {
                cb2.isCallPermitted();
                cb2.recordFailure();
            }
            // cb2도 OPEN
            // 5초 후 isCallPermitted → HALF_OPEN 전이
            // 이 테스트는 Clock 교체가 어려우므로 짧은 대기시간으로 검증

            // 간단한 방식: waitDuration을 0으로 설정
            ManualCircuitBreaker cbFast = new ManualCircuitBreaker(10, 50.0, 0, 2);
            for (int i = 0; i < 10; i++) {
                cbFast.isCallPermitted();
                cbFast.recordFailure();
            }
            assertThat(cbFast.getState()).isEqualTo(ManualCircuitBreaker.State.OPEN);

            // then - 대기시간 0이므로 즉시 HALF_OPEN 전이
            assertThat(cbFast.isCallPermitted()).isTrue(); // 1번째 허용
            assertThat(cbFast.getState()).isEqualTo(ManualCircuitBreaker.State.HALF_OPEN);
            assertThat(cbFast.isCallPermitted()).isTrue(); // 2번째 허용
            assertThat(cbFast.isCallPermitted()).isFalse(); // 3번째 차단
        }

        @Test
        @DisplayName("허용된 호출이 모두 성공하면 CLOSED로 복구한다.")
        void transitionsToClosedOnSuccess() {
            // given
            ManualCircuitBreaker cb = new ManualCircuitBreaker(10, 50.0, 0, 2);
            for (int i = 0; i < 10; i++) {
                cb.isCallPermitted();
                cb.recordFailure();
            }

            // HALF_OPEN 전이
            cb.isCallPermitted();
            cb.recordSuccess();
            cb.isCallPermitted();
            cb.recordSuccess();

            // then
            assertThat(cb.getState()).isEqualTo(ManualCircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("허용된 호출 중 하나라도 실패하면 다시 OPEN으로 전이한다.")
        void transitionsBackToOpenOnFailure() {
            // given
            ManualCircuitBreaker cb = new ManualCircuitBreaker(10, 50.0, 0, 2);
            for (int i = 0; i < 10; i++) {
                cb.isCallPermitted();
                cb.recordFailure();
            }

            // HALF_OPEN 전이
            cb.isCallPermitted();
            cb.recordFailure();

            // then
            assertThat(cb.getState()).isEqualTo(ManualCircuitBreaker.State.OPEN);
        }
    }
}
