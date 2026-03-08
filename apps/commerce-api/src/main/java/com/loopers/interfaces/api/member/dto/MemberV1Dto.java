package com.loopers.interfaces.api.member.dto;

import com.loopers.application.member.MemberInfo;
import com.loopers.application.member.MyInfo;
import com.loopers.domain.member.SignupCommand;

/**
 * 회원 관련 API 요청/응답 DTO
 */
public class MemberV1Dto {

    /**
     * 회원가입 요청
     */
    public record SignupRequest(
            String loginId,
            String password,
            String name,
            String email,
            String birthDate) {
        public SignupCommand toCommand() {
            return new SignupCommand(loginId, password, name, email, birthDate);
        }
    }

    /**
     * 회원가입 응답
     */
    public record SignupResponse(Long memberId) {
        public static SignupResponse from(MemberInfo info) {
            return new SignupResponse(info.id());
        }
    }

    /**
     * 내 정보 조회 응답
     */
    public record MyInfoResponse(
            String loginId,
            String name,
            String email,
            String birthDate,
            int pointBalance) {
        public static MyInfoResponse from(MyInfo info) {
            return new MyInfoResponse(
                    info.loginId(),
                    info.name(),
                    info.email(),
                    info.birthDate(),
                    info.pointBalance());
        }
    }

    /**
     * 비밀번호 변경 요청
     */
    public record ChangePasswordRequest(
            String currentPassword,
            String newPassword) {
    }
}
