package com.loopers.interfaces.api.coupon.dto;

import com.loopers.application.coupon.CouponIssueStatusInfo;
import com.loopers.domain.coupon.MemberCouponStatus;

// 쿠폰 발급 API 요청/응답 DTO
public class CouponIssueV1Dto {

    // 쿠폰 발급 상태 응답
    public record IssueStatusResponse(
            MemberCouponStatus status,
            String failReason
    ) {
        public static IssueStatusResponse from(CouponIssueStatusInfo info) {
            return new IssueStatusResponse(info.status(), info.failReason());
        }
    }
}
