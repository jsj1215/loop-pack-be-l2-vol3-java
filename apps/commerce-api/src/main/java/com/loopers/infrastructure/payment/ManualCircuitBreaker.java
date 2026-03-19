package com.loopers.infrastructure.payment;

import lombok.extern.slf4j.Slf4j;

import java.time.Clock;

@Slf4j
public class ManualCircuitBreaker {

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final int slidingWindowSize;
    private final double failureRateThreshold;
    private final long waitDurationInOpenStateMs;
    private final int permittedCallsInHalfOpen;
    private final Clock clock;

    private State state = State.CLOSED;
    private long openedAt = 0;

    // 슬라이딩 윈도우: true=성공, false=실패
    private final boolean[] slidingWindow;
    private int windowIndex = 0;
    private int recordedCount = 0;
    private int failureCount = 0;

    // Half-Open 상태에서의 호출 추적
    private int halfOpenCallCount = 0;
    private int halfOpenSuccessCount = 0;

    public ManualCircuitBreaker(int slidingWindowSize, double failureRateThreshold,
                                long waitDurationInOpenStateMs, int permittedCallsInHalfOpen) {
        this(slidingWindowSize, failureRateThreshold, waitDurationInOpenStateMs,
                permittedCallsInHalfOpen, Clock.systemDefaultZone());
    }

    ManualCircuitBreaker(int slidingWindowSize, double failureRateThreshold,
                         long waitDurationInOpenStateMs, int permittedCallsInHalfOpen, Clock clock) {
        this.slidingWindowSize = slidingWindowSize;
        this.failureRateThreshold = failureRateThreshold;
        this.waitDurationInOpenStateMs = waitDurationInOpenStateMs;
        this.permittedCallsInHalfOpen = permittedCallsInHalfOpen;
        this.clock = clock;
        this.slidingWindow = new boolean[slidingWindowSize];
    }

    public synchronized boolean isCallPermitted() {
        if (state == State.CLOSED) {
            return true;
        }

        if (state == State.OPEN) {
            long elapsed = clock.millis() - openedAt;
            if (elapsed >= waitDurationInOpenStateMs) {
                state = State.HALF_OPEN;
                halfOpenCallCount = 0;
                halfOpenSuccessCount = 0;
                log.info("서킷 브레이커 상태 전이: OPEN → HALF_OPEN");
                halfOpenCallCount++;
                return true;
            }
            return false;
        }

        // HALF_OPEN
        halfOpenCallCount++;
        return halfOpenCallCount <= permittedCallsInHalfOpen;
    }

    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            halfOpenSuccessCount++;
            if (halfOpenSuccessCount >= permittedCallsInHalfOpen) {
                state = State.CLOSED;
                resetSlidingWindow();
                log.info("서킷 브레이커 상태 전이: HALF_OPEN → CLOSED (복구)");
            }
            return;
        }

        if (state == State.CLOSED) {
            recordInWindow(true);
        }
    }

    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN) {
            state = State.OPEN;
            openedAt = clock.millis();
            log.info("서킷 브레이커 상태 전이: HALF_OPEN → OPEN (실패 감지)");
            return;
        }

        if (state == State.CLOSED) {
            recordInWindow(false);
            evaluateThreshold();
        }
    }

    public synchronized State getState() {
        return state;
    }

    private void recordInWindow(boolean success) {
        int index = windowIndex;
        windowIndex = (windowIndex + 1) % slidingWindowSize;

        // 기존 값이 실패였다면 실패 카운트 감소
        if (recordedCount >= slidingWindowSize && !slidingWindow[index]) {
            failureCount--;
        }

        slidingWindow[index] = success;
        if (!success) {
            failureCount++;
        }

        if (recordedCount < slidingWindowSize) {
            recordedCount++;
        }
    }

    private void evaluateThreshold() {
        if (recordedCount < slidingWindowSize) {
            return;
        }

        double failureRate = (double) failureCount / slidingWindowSize * 100;
        if (failureRate >= failureRateThreshold) {
            state = State.OPEN;
            openedAt = clock.millis();
            log.info("서킷 브레이커 상태 전이: CLOSED → OPEN (실패율 {}%)", String.format("%.1f", failureRate));
        }
    }

    private void resetSlidingWindow() {
        windowIndex = 0;
        recordedCount = 0;
        failureCount = 0;
    }
}
