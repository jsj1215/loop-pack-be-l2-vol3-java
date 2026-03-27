package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 선착순 쿠폰 발급 요청 도메인 서비스.
 *
 * 발급 요청(REQUESTED 상태 INSERT)과 Kafka 발행 전 검증을 담당한다.
 * 실제 발급 승인/거부는 Consumer(commerce-streamer)에서 처리한다.
 */
@RequiredArgsConstructor
@Component
public class CouponIssueService {

    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;

    /**
     * 선착순 쿠폰 발급 요청을 생성한다.
     *
     * 검증 순서:
     * - 1. 쿠폰 존재 여부
     * - 2. 쿠폰 유효기간
     * - 3. 선착순 수량 제한 쿠폰인지 확인
     * - 4. 중복 요청 여부 (REQUESTED/AVAILABLE 상태가 이미 있으면 거부)
     *
     * @return 저장된 MemberCoupon (status=REQUESTED)
     */
    public MemberCoupon createIssueRequest(Long memberId, Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));

        if (!coupon.isValid()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰 발급 기간이 아닙니다.");
        }

        if (!coupon.isLimitedIssue()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "선착순 쿠폰이 아닙니다.");
        }

        Optional<MemberCoupon> existing = memberCouponRepository
                .findByMemberIdAndCouponIdIncludingDeleted(memberId, couponId);
        if (existing.isPresent()) {
            MemberCoupon existingCoupon = existing.get();
            MemberCouponStatus status = existingCoupon.getStatus();
            if (status == MemberCouponStatus.REQUESTED || status == MemberCouponStatus.AVAILABLE) {
                throw new CoreException(ErrorType.CONFLICT, "이미 발급 요청한 쿠폰입니다.");
            }
            if (status == MemberCouponStatus.FAILED) {
                existingCoupon.retryRequest();
                return memberCouponRepository.save(existingCoupon);
            }
        }

        MemberCoupon memberCoupon = MemberCoupon.createRequested(memberId, couponId);
        return memberCouponRepository.save(memberCoupon);
    }

    /**
     * 발급 상태 조회 (Polling 용)
     */
    public Optional<MemberCoupon> findIssueStatus(Long memberId, Long couponId) {
        return memberCouponRepository.findByMemberIdAndCouponIdIncludingDeleted(memberId, couponId);
    }
}
