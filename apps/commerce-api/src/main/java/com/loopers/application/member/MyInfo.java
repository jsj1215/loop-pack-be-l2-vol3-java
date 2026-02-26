package com.loopers.application.member;

import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberName;

/*
    Info 
    : 비즈니스 로직 결과를 전달하는 도메인 객체.
*/
public record MyInfo(
        String loginId,
        String name,
        String email,
        String birthDate,
        int pointBalance) {
    public static MyInfo from(Member member, int pointBalance) {
        MemberName memberName = new MemberName(member.getName());
        return new MyInfo(
                member.getLoginId(),
                memberName.masked(),
                member.getEmail(),
                member.getBirthDate(),
                pointBalance);
    }
}
