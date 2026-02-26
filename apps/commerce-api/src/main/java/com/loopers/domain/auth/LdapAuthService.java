package com.loopers.domain.auth;

public interface LdapAuthService {

    Admin authenticate(String ldapHeader);
}
