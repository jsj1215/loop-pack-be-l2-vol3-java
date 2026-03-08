package com.loopers.application.member;

import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberService;
import com.loopers.domain.member.SignupCommand;
import com.loopers.domain.point.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/*
    Facade
    :  회원 도메인 비즈니스 로직의 게이트웨이 (컨트롤러 -> 서비스)
*/
@RequiredArgsConstructor
@Component
@Transactional(readOnly = true)
public class MemberFacade {

    private final MemberService memberService;
    private final PointService pointService;

    /**
     * 회원가입
     */
    @Transactional
    public MemberInfo signup(SignupCommand command) {
        Member member = memberService.signup(command);
        pointService.createPoint(member.getId());
        return MemberInfo.from(member);
    }

    /**
     * 내 정보 가져오기
     */
    public MyInfo getMyInfo(Member member) {
        int pointBalance = pointService.getBalance(member.getId());
        return MyInfo.from(member, pointBalance);
    }

    /**
     * 비밀번호 변경
     */
    @Transactional
    public void changePassword(Member member, String currentPassword, String newPassword) {
        memberService.changePassword(member, currentPassword, newPassword);
    }
}
