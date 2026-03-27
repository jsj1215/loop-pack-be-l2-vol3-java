package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponIssueFacade;
import com.loopers.application.coupon.CouponIssueStatusInfo;
import com.loopers.domain.member.Member;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginMember;
import com.loopers.interfaces.api.coupon.dto.CouponIssueV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/coupons")
public class CouponIssueV1Controller {

    private final CouponIssueFacade couponIssueFacade;

    /**
     * 선착순 쿠폰 발급 요청 (비동기)
     */
    @PostMapping("/{couponId}/issue-request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<CouponIssueV1Dto.IssueStatusResponse> requestIssue(
            @LoginMember Member member, @PathVariable Long couponId) {
        CouponIssueStatusInfo info = couponIssueFacade.requestIssue(member, couponId);
        return ApiResponse.success(CouponIssueV1Dto.IssueStatusResponse.from(info));
    }

    /**
     * 선착순 쿠폰 발급 상태 조회 (Polling)
     */
    @GetMapping("/{couponId}/issue-status")
    public ApiResponse<CouponIssueV1Dto.IssueStatusResponse> getIssueStatus(
            @LoginMember Member member, @PathVariable Long couponId) {
        CouponIssueStatusInfo info = couponIssueFacade.getIssueStatus(member, couponId);
        return ApiResponse.success(CouponIssueV1Dto.IssueStatusResponse.from(info));
    }
}
