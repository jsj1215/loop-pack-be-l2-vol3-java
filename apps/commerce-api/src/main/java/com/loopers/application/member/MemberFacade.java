package com.loopers.application.member;

import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberService;
import com.loopers.domain.member.SignupCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/*
    Facade
    :  회원 도메인 비즈니스 로직의 게이트웨이 (컨트롤러 -> 서비스)
*/
@RequiredArgsConstructor
@Component
public class MemberFacade {

    private final MemberService memberService;

    /**
     * 회원가입
     */
    public MemberInfo signup(SignupCommand command) {
        Member member = memberService.signup(command); // 도메인 결과를 담는 객체
        return MemberInfo.from(member); // record로 반환
    }

    /**
     * 내 정보 가져오기
     */
    public MyInfo getMyInfo(Member member) {
        return MyInfo.from(member);
    }

    /**
     * 비밀번호 변경
     */
    public void changePassword(Member member, String currentPassword, String newPassword) {
        memberService.changePassword(member, currentPassword, newPassword);
    }
}
