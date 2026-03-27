package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.MemberCoupon;
import com.loopers.domain.coupon.MemberCouponRepository;
import com.loopers.domain.coupon.MemberCouponStatus;
import com.loopers.domain.event.EventHandled;
import com.loopers.domain.event.EventHandledRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CouponIssueEventService 테스트")
@ExtendWith(MockitoExtension.class)
class CouponIssueEventServiceTest {

    @Mock
    private EventHandledRepository eventHandledRepository;

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private MemberCouponRepository memberCouponRepository;

    private CouponIssueEventService service;

    @BeforeEach
    void setUp() {
        service = new CouponIssueEventService(
                eventHandledRepository, couponRepository, memberCouponRepository);
    }

    private MemberCoupon createRequestedMemberCoupon(Long id, Long memberId, Long couponId) {
        try {
            var constructor = MemberCoupon.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            MemberCoupon mc = constructor.newInstance();
            ReflectionTestUtils.setField(mc, "id", id);
            ReflectionTestUtils.setField(mc, "memberId", memberId);
            ReflectionTestUtils.setField(mc, "couponId", couponId);
            ReflectionTestUtils.setField(mc, "status", MemberCouponStatus.REQUESTED);
            return mc;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private MemberCoupon createMemberCouponWithStatus(Long id, Long memberId, Long couponId, MemberCouponStatus status) {
        try {
            var constructor = MemberCoupon.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            MemberCoupon mc = constructor.newInstance();
            ReflectionTestUtils.setField(mc, "id", id);
            ReflectionTestUtils.setField(mc, "memberId", memberId);
            ReflectionTestUtils.setField(mc, "couponId", couponId);
            ReflectionTestUtils.setField(mc, "status", status);
            return mc;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Coupon createCoupon(Long id, int maxIssueCount) {
        try {
            var constructor = Coupon.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Coupon coupon = constructor.newInstance();
            ReflectionTestUtils.setField(coupon, "id", id);
            ReflectionTestUtils.setField(coupon, "maxIssueCount", maxIssueCount);
            return coupon;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("멱등 처리")
    class Idempotency {

        @Test
        @DisplayName("이미 처리된 이벤트면 skip하고 false를 반환한다")
        void skipAlreadyHandled() {
            // given
            when(eventHandledRepository.existsByEventId("uuid-dup")).thenReturn(true);

            // when
            boolean result = service.processIssueRequest("uuid-dup", 100L, 1L, 10L);

            // then
            assertThat(result).isFalse();
            verify(memberCouponRepository, never()).findById(any());
        }

        @Test
        @DisplayName("UNIQUE 제약 위반 시 DataIntegrityViolationException을 던져 TX 롤백한다")
        void throwsOnUniqueViolation() {
            // given
            when(eventHandledRepository.existsByEventId("uuid-race")).thenReturn(false);
            MemberCoupon mc = createRequestedMemberCoupon(10L, 1L, 100L);
            when(memberCouponRepository.findById(10L)).thenReturn(Optional.of(mc));
            Coupon coupon = createCoupon(100L, 100);
            when(couponRepository.findById(100L)).thenReturn(Optional.of(coupon));
            when(memberCouponRepository.countByCouponIdAndStatusIn(eq(100L), any())).thenReturn(0L);
            when(eventHandledRepository.save(any(EventHandled.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate"));

            // when & then
            assertThatThrownBy(() -> service.processIssueRequest("uuid-race", 100L, 1L, 10L))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("발급 승인")
    class ApproveIssue {

        @Test
        @DisplayName("수량 이내면 AVAILABLE로 전환한다")
        void approvesWhenWithinLimit() {
            // given
            when(eventHandledRepository.existsByEventId("uuid-1")).thenReturn(false);
            MemberCoupon mc = createRequestedMemberCoupon(10L, 1L, 100L);
            when(memberCouponRepository.findById(10L)).thenReturn(Optional.of(mc));
            Coupon coupon = createCoupon(100L, 100);
            when(couponRepository.findById(100L)).thenReturn(Optional.of(coupon));
            when(memberCouponRepository.countByCouponIdAndStatusIn(eq(100L), any())).thenReturn(50L);

            // when
            boolean result = service.processIssueRequest("uuid-1", 100L, 1L, 10L);

            // then
            assertThat(result).isTrue();
            assertThat(mc.getStatus()).isEqualTo(MemberCouponStatus.AVAILABLE);
            verify(memberCouponRepository).save(mc);
            verify(eventHandledRepository).save(any(EventHandled.class));
        }

        @Test
        @DisplayName("마지막 1장(99/100)일 때도 정상 승인된다")
        void approvesLastOne() {
            // given
            when(eventHandledRepository.existsByEventId("uuid-last")).thenReturn(false);
            MemberCoupon mc = createRequestedMemberCoupon(10L, 1L, 100L);
            when(memberCouponRepository.findById(10L)).thenReturn(Optional.of(mc));
            Coupon coupon = createCoupon(100L, 100);
            when(couponRepository.findById(100L)).thenReturn(Optional.of(coupon));
            when(memberCouponRepository.countByCouponIdAndStatusIn(eq(100L), any())).thenReturn(99L);

            // when
            boolean result = service.processIssueRequest("uuid-last", 100L, 1L, 10L);

            // then
            assertThat(result).isTrue();
            assertThat(mc.getStatus()).isEqualTo(MemberCouponStatus.AVAILABLE);
        }
    }

    @Nested
    @DisplayName("발급 거부")
    class RejectIssue {

        @Test
        @DisplayName("수량 초과 시 FAILED로 전환하고 사유를 기록한다")
        void rejectsWhenExceedsLimit() {
            // given
            when(eventHandledRepository.existsByEventId("uuid-over")).thenReturn(false);
            MemberCoupon mc = createRequestedMemberCoupon(10L, 1L, 100L);
            when(memberCouponRepository.findById(10L)).thenReturn(Optional.of(mc));
            Coupon coupon = createCoupon(100L, 100);
            when(couponRepository.findById(100L)).thenReturn(Optional.of(coupon));
            when(memberCouponRepository.countByCouponIdAndStatusIn(eq(100L), any())).thenReturn(100L);

            // when
            boolean result = service.processIssueRequest("uuid-over", 100L, 1L, 10L);

            // then
            assertThat(result).isTrue();
            assertThat(mc.getStatus()).isEqualTo(MemberCouponStatus.FAILED);
            assertThat(mc.getFailReason()).isEqualTo("수량 초과");
            verify(memberCouponRepository).save(mc);
        }

        @Test
        @DisplayName("쿠폰이 삭제되었으면 FAILED로 전환한다")
        void rejectsWhenCouponDeleted() {
            // given
            when(eventHandledRepository.existsByEventId("uuid-del")).thenReturn(false);
            MemberCoupon mc = createRequestedMemberCoupon(10L, 1L, 100L);
            when(memberCouponRepository.findById(10L)).thenReturn(Optional.of(mc));
            when(couponRepository.findById(100L)).thenReturn(Optional.empty());

            // when
            boolean result = service.processIssueRequest("uuid-del", 100L, 1L, 10L);

            // then
            assertThat(result).isTrue();
            assertThat(mc.getStatus()).isEqualTo(MemberCouponStatus.FAILED);
            assertThat(mc.getFailReason()).isEqualTo("쿠폰을 찾을 수 없습니다.");
        }
    }

    @Nested
    @DisplayName("비정상 케이스 처리 — Direct Kafka 트레이드오프")
    class EdgeCases {

        /**
         * Direct Kafka 발행의 트레이드오프를 검증하는 핵심 테스트.
         *
         * CouponIssueFacade는 Outbox를 사용하지 않고 @Transactional 안에서 Kafka를 직접 발행한다.
         * 이때 Kafka 발행 성공 후 DB 커밋이 실패하면, Consumer가 메시지를 수신하지만
         * member_coupon 레코드가 DB에 없는 상태가 발생할 수 있다.
         *
         * Outbox Auto 모드였다면: DB 커밋 성공이 보장된 후 Kafka 발행 → 이 케이스 없음.
         * Direct Kafka에서는: 이 케이스를 Consumer에서 skip으로 방어한다.
         */
        @Test
        @DisplayName("member_coupon 레코드가 없으면 skip — Kafka 발행 성공 + DB 커밋 실패 케이스 방어")
        void skipsWhenMemberCouponNotFound() {
            // given
            when(eventHandledRepository.existsByEventId("uuid-nomc")).thenReturn(false);
            when(memberCouponRepository.findById(10L)).thenReturn(Optional.empty());

            // when
            boolean result = service.processIssueRequest("uuid-nomc", 100L, 1L, 10L);

            // then
            assertThat(result).isFalse();
            verify(memberCouponRepository, never()).save(any());
            verify(eventHandledRepository).save(any(EventHandled.class));
        }

        @Test
        @DisplayName("REQUESTED 상태가 아닌 member_coupon이면 skip한다")
        void skipsWhenNotRequestedStatus() {
            // given
            when(eventHandledRepository.existsByEventId("uuid-avail")).thenReturn(false);
            MemberCoupon mc = createMemberCouponWithStatus(10L, 1L, 100L, MemberCouponStatus.AVAILABLE);
            when(memberCouponRepository.findById(10L)).thenReturn(Optional.of(mc));

            // when
            boolean result = service.processIssueRequest("uuid-avail", 100L, 1L, 10L);

            // then
            assertThat(result).isFalse();
            verify(couponRepository, never()).findById(any());
            verify(eventHandledRepository).save(any(EventHandled.class));
        }
    }
}
