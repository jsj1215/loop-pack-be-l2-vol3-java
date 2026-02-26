package com.loopers.infrastructure.auth;

import com.loopers.domain.auth.Admin;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("FakeLdapAuthService 단위 테스트")
class FakeLdapAuthServiceTest {

    private final FakeLdapAuthService fakeLdapAuthService = new FakeLdapAuthService();

    @Nested
    @DisplayName("어드민 인증을 할 때,")
    class Authenticate {

        @Test
        @DisplayName("유효한 LDAP 헤더로 인증하면 Admin을 반환한다.")
        void returnsAdmin_whenValidLdapHeader() {
            // given
            String ldapHeader = "loopers.admin";

            // when
            Admin admin = fakeLdapAuthService.authenticate(ldapHeader);

            // then
            assertThat(admin.ldapId()).isEqualTo("loopers.admin");
        }

        @Test
        @DisplayName("잘못된 LDAP 헤더로 인증하면 UNAUTHORIZED 예외가 발생한다.")
        void throwsUnauthorized_whenInvalidLdapHeader() {
            // given
            String ldapHeader = "invalid.admin";

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> fakeLdapAuthService.authenticate(ldapHeader));

            // then
            assertAll(
                    () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED),
                    () -> assertThat(exception.getMessage()).contains("어드민 인증에 실패"));
        }

        @Test
        @DisplayName("null LDAP 헤더로 인증하면 UNAUTHORIZED 예외가 발생한다.")
        void throwsUnauthorized_whenNullLdapHeader() {
            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> fakeLdapAuthService.authenticate(null));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }
    }
}
