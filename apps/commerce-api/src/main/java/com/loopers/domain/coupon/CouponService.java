package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    public List<Coupon> findByIds(List<Long> couponIds) {
        return couponRepository.findByIds(couponIds);
    }

    public Page<Coupon> findAllCoupons(Pageable pageable) {
        return couponRepository.findAll(pageable);
    }

    public Coupon updateCoupon(Long couponId, String name, CouponScope couponScope, Long targetId,
                               DiscountType discountType, int discountValue, int minOrderAmount,
                               int maxDiscountAmount,
                               ZonedDateTime validFrom, ZonedDateTime validTo) {
        Coupon coupon = couponRepository.findByIdWithLock(couponId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));
        coupon.updateInfo(name, couponScope, targetId, discountType, discountValue,
                minOrderAmount, maxDiscountAmount, validFrom, validTo);
        return couponRepository.save(coupon);
    }

    public void softDelete(Long couponId) {
        Coupon coupon = couponRepository.findByIdWithLock(couponId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));
        coupon.delete();
        couponRepository.save(coupon);
    }

    public MemberCouponDetail downloadCoupon(Long memberId, Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));

        // 유효기간 검증
        if (!coupon.isValid()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰 발급이 불가합니다.");
        }

        // 삭제된 행 포함 조회하여 중복/재발급 판단
        // 동시 요청에 의한 UniqueConstraint 위반은 DataIntegrityViolationException으로 발생하며,
        // ApiControllerAdvice에서 409 CONFLICT로 처리된다.
        Optional<MemberCoupon> existing = memberCouponRepository
                .findByMemberIdAndCouponIdIncludingDeleted(memberId, couponId);

        MemberCoupon memberCoupon;
        if (existing.isPresent()) {
            memberCoupon = existing.get();
            memberCoupon.reissue();
        } else {
            memberCoupon = new MemberCoupon(memberId, couponId);
        }

        MemberCoupon savedMemberCoupon = memberCouponRepository.save(memberCoupon);
        return new MemberCouponDetail(savedMemberCoupon, coupon);
    }

    public List<MemberCoupon> findMyCoupons(Long memberId) {
        return memberCouponRepository.findByMemberIdAndStatus(memberId, MemberCouponStatus.AVAILABLE);
    }

    public List<MemberCoupon> findAllMyCoupons(Long memberId) {
        return memberCouponRepository.findByMemberId(memberId);
    }

    public List<MemberCouponDetail> getMyCouponDetails(Long memberId) {
        List<MemberCoupon> memberCoupons = memberCouponRepository.findByMemberId(memberId);

        List<Long> couponIds = memberCoupons.stream()
                .map(MemberCoupon::getCouponId)
                .toList();

        Map<Long, Coupon> couponMap = couponRepository.findByIds(couponIds).stream()
                .collect(Collectors.toMap(Coupon::getId, Function.identity()));

        return memberCoupons.stream()
                .filter(mc -> couponMap.containsKey(mc.getCouponId()))
                .map(mc -> new MemberCouponDetail(mc, couponMap.get(mc.getCouponId())))
                .toList();
    }

    public Page<MemberCoupon> findCouponIssues(Long couponId, Pageable pageable) {
        return memberCouponRepository.findByCouponId(couponId, pageable);
    }

    public MemberCoupon getMemberCoupon(Long memberCouponId) {
        return memberCouponRepository.findById(memberCouponId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));
    }

    /**
     * 쿠폰 사용 처리 (원자적 업데이트)
     * - 소유권/유효기간 검증 후, UPDATE ... WHERE status='AVAILABLE' 로 상태 전환
     * - affected rows = 0 이면 이미 사용된 쿠폰으로 판단하여 CONFLICT 예외 발생
     * - 반드시 상위 레이어(@Transactional)의 트랜잭션 내에서 호출되어야 한다.
     */
    public void useCoupon(Long memberId, Long memberCouponId, Long orderId) {
        MemberCoupon memberCoupon = memberCouponRepository.findById(memberCouponId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));

        if (!memberCoupon.getMemberId().equals(memberId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다.");
        }

        Coupon coupon = couponRepository.findById(memberCoupon.getCouponId())
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰을 찾을 수 없습니다."));

        if (!coupon.isValid()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "유효기간이 만료된 쿠폰입니다.");
        }

        int updatedCount = memberCouponRepository.updateStatusToUsed(memberCouponId, orderId, ZonedDateTime.now());
        if (updatedCount == 0) {
            throw new CoreException(ErrorType.CONFLICT, "이미 사용된 쿠폰입니다.");
        }
    }

    /**
     * 사용된 쿠폰 취소 (원자적 업데이트)
     * - UPDATE ... WHERE status='USED' 로 AVAILABLE 상태로 복원
     * - affected rows = 0 이면 이미 취소되었거나 USED 상태가 아닌 것으로 판단하여 CONFLICT 예외 발생
     */
    public void cancelUsedCoupon(Long memberCouponId) {
        int updatedCount = memberCouponRepository.updateStatusToAvailable(memberCouponId);
        if (updatedCount == 0) {
            throw new CoreException(ErrorType.CONFLICT, "사용 상태가 아닌 쿠폰은 취소할 수 없습니다.");
        }
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
