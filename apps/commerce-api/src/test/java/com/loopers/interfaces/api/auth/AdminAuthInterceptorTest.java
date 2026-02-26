package com.loopers.interfaces.api.auth;

import com.loopers.domain.auth.Admin;
import com.loopers.domain.auth.LdapAuthService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAuthInterceptor 단위 테스트")
class AdminAuthInterceptorTest {

    @Mock
    private LdapAuthService ldapAuthService;

    @InjectMocks
    private AdminAuthInterceptor adminAuthInterceptor;

    @Nested
    @DisplayName("어드민 인증 인터셉터를 통과할 때,")
    class PreHandle {

        @Test
        @DisplayName("유효한 LDAP 헤더가 있으면 true를 반환한다.")
        void returnsTrue_whenValidLdapHeader() {
            // given
            HttpServletRequest request = mock(HttpServletRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            Admin admin = new Admin("loopers.admin");

            when(request.getHeader("X-Loopers-Ldap")).thenReturn("loopers.admin");
            when(ldapAuthService.authenticate("loopers.admin")).thenReturn(admin);

            // when
            boolean result = adminAuthInterceptor.preHandle(request, response, new Object());

            // then
            assertAll(
                    () -> assertThat(result).isTrue(),
                    () -> verify(request).setAttribute("loginAdmin", admin));
        }

        @Test
        @DisplayName("LDAP 헤더가 없으면 UNAUTHORIZED 예외가 발생한다.")
        void throwsUnauthorized_whenNoLdapHeader() {
            // given
            HttpServletRequest request = mock(HttpServletRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);

            when(request.getHeader("X-Loopers-Ldap")).thenReturn(null);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> adminAuthInterceptor.preHandle(request, response, new Object()));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }

        @Test
        @DisplayName("빈 LDAP 헤더이면 UNAUTHORIZED 예외가 발생한다.")
        void throwsUnauthorized_whenBlankLdapHeader() {
            // given
            HttpServletRequest request = mock(HttpServletRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);

            when(request.getHeader("X-Loopers-Ldap")).thenReturn("  ");

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> adminAuthInterceptor.preHandle(request, response, new Object()));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }
    }
}
