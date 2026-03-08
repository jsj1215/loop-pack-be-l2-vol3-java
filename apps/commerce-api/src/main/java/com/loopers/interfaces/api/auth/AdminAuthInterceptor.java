package com.loopers.interfaces.api.auth;

import com.loopers.domain.auth.Admin;
import com.loopers.domain.auth.LdapAuthService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@RequiredArgsConstructor
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final String HEADER_LDAP = "X-Loopers-Ldap";
    static final String LOGIN_ADMIN_ATTRIBUTE = "loginAdmin";

    private final LdapAuthService ldapAuthService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String ldapHeader = request.getHeader(HEADER_LDAP);

        if (ldapHeader == null || ldapHeader.isBlank()) {
            throw new CoreException(ErrorType.UNAUTHORIZED, "어드민 인증 정보가 필요합니다.");
        }

        Admin admin = ldapAuthService.authenticate(ldapHeader);
        request.setAttribute(LOGIN_ADMIN_ATTRIBUTE, admin);

        return true;
    }
}
