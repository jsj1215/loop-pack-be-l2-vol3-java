package com.loopers.infrastructure.auth;

import com.loopers.domain.auth.Admin;
import com.loopers.domain.auth.LdapAuthService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.stereotype.Component;

@Component
public class FakeLdapAuthService implements LdapAuthService {

    private static final String VALID_LDAP_ID = "loopers.admin";

    @Override
    public Admin authenticate(String ldapHeader) {
        if (ldapHeader == null || !ldapHeader.equals(VALID_LDAP_ID)) {
            throw new CoreException(ErrorType.UNAUTHORIZED, "어드민 인증에 실패했습니다.");
        }
        return new Admin(ldapHeader);
    }
}
