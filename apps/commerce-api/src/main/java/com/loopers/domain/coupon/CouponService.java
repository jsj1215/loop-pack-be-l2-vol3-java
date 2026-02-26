package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import java.util.List;

@RequiredArgsConstructor
@Component
public class CouponService {

    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;

    public Coupon createCoupon(Coupon coupon) {
        return couponRepository.save(coupon);
    }

    public Coupon findById(Long couponId) {
        return couponRepository.findById(couponId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));
    }

    public List<Coupon> findAvailableCoupons() {
        return couponRepository.findAllValid();
    }

    public Page<Coupon> findAllCoupons(Pageable pageable) {
        return couponRepository.findAll(pageable);
    }

    public MemberCoupon downloadCoupon(Long memberId, Long couponId) {
        // 1. 쿠폰 조회
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));

        // 2. 중복 다운로드 검사
        memberCouponRepository.findByMemberIdAndCouponId(memberId, couponId)
                .ifPresent(mc -> {
                    throw new CoreException(ErrorType.CONFLICT, "이미 다운로드한 쿠폰입니다.");
                });

        // 3. 발급 가능 여부 확인 및 발급 처리
        coupon.issue();
        couponRepository.save(coupon);

        // 4. 회원 쿠폰 생성 및 저장
        MemberCoupon memberCoupon = new MemberCoupon(memberId, couponId);
        return memberCouponRepository.save(memberCoupon);
    }

    public List<MemberCoupon> findMyCoupons(Long memberId) {
        return memberCouponRepository.findByMemberIdAndStatus(memberId, MemberCouponStatus.AVAILABLE);
    }

    public MemberCoupon getMemberCoupon(Long memberCouponId) {
        return memberCouponRepository.findById(memberCouponId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));
    }

    public MemberCoupon useCoupon(Long memberCouponId, Long orderId) {
        MemberCoupon memberCoupon = memberCouponRepository.findById(memberCouponId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));

        memberCoupon.use(orderId);
        return memberCouponRepository.save(memberCoupon);
    }

    /**
     * 쿠폰 할인 금액 계산
     * - 회원 쿠폰 소유 검증, 사용 가능 여부, 유효기간, 최소 주문 금액 검증 후 할인 금액을 반환한다.
     *
     * @param memberId         회원 ID
     * @param memberCouponId   회원 쿠폰 ID
     * @param applicableAmount 쿠폰 적용 대상 금액 (scope별로 호출 측에서 계산)
     * @return 할인 금액
     */
    public int calculateCouponDiscount(Long memberId, Long memberCouponId, int applicableAmount) {
        MemberCoupon memberCoupon = getMemberCoupon(memberCouponId);

        if (!memberCoupon.getMemberId().equals(memberId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다.");
        }

        if (!memberCoupon.isAvailable()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "이미 사용된 쿠폰입니다.");
        }

        Coupon coupon = findById(memberCoupon.getCouponId());

        if (!coupon.isValid()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "유효기간이 만료된 쿠폰입니다.");
        }

        if (applicableAmount < coupon.getMinOrderAmount()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "최소 주문 금액 조건을 충족하지 않습니다.");
        }

        return coupon.calculateDiscount(applicableAmount);
    }
}
