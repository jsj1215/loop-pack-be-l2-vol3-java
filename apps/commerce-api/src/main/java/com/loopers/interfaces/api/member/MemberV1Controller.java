package com.loopers.interfaces.api.member;

import com.loopers.application.member.MemberFacade;
import com.loopers.application.member.MemberInfo;
import com.loopers.application.member.MyInfo;
import com.loopers.domain.member.Member;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginMember;
import com.loopers.interfaces.api.member.dto.MemberV1Dto;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/members")
public class MemberV1Controller {

    private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";

    private final MemberFacade memberFacade;

    /**
     * 회원가입
     */
    @PostMapping("/signup")
    public ApiResponse<MemberV1Dto.SignupResponse> signup(
            @RequestBody MemberV1Dto.SignupRequest request,
            HttpServletResponse response) {
        // 회원가입 기능 동작
        MemberInfo info = memberFacade.signup(request.toCommand());

        // 응답 헤더 설정 (비밀번호는 보안상 헤더에 포함하지 않음)
        response.setHeader(HEADER_LOGIN_ID, info.loginId());

        // 클라이언트에게 필요한 정보만 노출 하기 위해 계층별로 DTO를 분리
        MemberV1Dto.SignupResponse signupResponse = MemberV1Dto.SignupResponse.from(info);
        return ApiResponse.success(signupResponse);
    }

    /**
     * 내 정보조회
     */
    @GetMapping("/me")
    public ApiResponse<MemberV1Dto.MyInfoResponse> getMyInfo(@LoginMember Member member) {
        // 내 정보 조회 기능 동작
        MyInfo info = memberFacade.getMyInfo(member);
        return ApiResponse.success(MemberV1Dto.MyInfoResponse.from(info));
    }

    /**
     * 비밀번호 변경
     */
    @PatchMapping("/me/password")
    public ApiResponse<Void> changePassword(
            @LoginMember Member member,
            @RequestBody MemberV1Dto.ChangePasswordRequest request) {
        // 비밀번호 변경 기능 동작
        memberFacade.changePassword(member, request.currentPassword(), request.newPassword());

        return ApiResponse.success(null);
    }
}
