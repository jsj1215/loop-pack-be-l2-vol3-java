package com.loopers.interfaces.api.auth;

import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@RequiredArgsConstructor
@Component
public class MemberAuthInterceptor implements HandlerInterceptor {

    private static final String HEADER_LOGIN_ID = "X-Loopers-LoginId";
    private static final String HEADER_LOGIN_PW = "X-Loopers-LoginPw";
    static final String LOGIN_MEMBER_ATTRIBUTE = "loginMember";

    private final MemberService memberService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String loginId = request.getHeader(HEADER_LOGIN_ID);
        String password = request.getHeader(HEADER_LOGIN_PW);

        if (loginId == null || loginId.isBlank() || password == null || password.isBlank()) {
            throw new CoreException(ErrorType.UNAUTHORIZED, "인증 정보가 필요합니다.");
        }

        Member member = memberService.authenticate(loginId, password);
        request.setAttribute(LOGIN_MEMBER_ATTRIBUTE, member);

        return true;
    }
}
