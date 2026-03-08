package com.loopers.interfaces.api.auth;

import com.loopers.domain.auth.Admin;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.NativeWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LoginAdminArgumentResolver 단위 테스트")
class LoginAdminArgumentResolverTest {

    private final LoginAdminArgumentResolver resolver = new LoginAdminArgumentResolver();

    @Nested
    @DisplayName("인증된 Admin을 resolve할 때,")
    class ResolveArgument {

        @Test
        @DisplayName("로그인 속성이 정상적인 Admin이면 반환한다.")
        void returnsAdmin_whenValidAttribute() {
            // given
            NativeWebRequest webRequest = mock(NativeWebRequest.class);
            HttpServletRequest httpRequest = mock(HttpServletRequest.class);
            Admin admin = new Admin("loopers.admin");

            when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
            when(httpRequest.getAttribute(AdminAuthInterceptor.LOGIN_ADMIN_ATTRIBUTE)).thenReturn(admin);

            // when
            Object result = resolver.resolveArgument(null, null, webRequest, null);

            // then
            assertThat(result).isEqualTo(admin);
        }

        @Test
        @DisplayName("로그인 속성이 없으면 UNAUTHORIZED 예외가 발생한다.")
        void throwsUnauthorized_whenAttributeIsNull() {
            // given
            NativeWebRequest webRequest = mock(NativeWebRequest.class);
            HttpServletRequest httpRequest = mock(HttpServletRequest.class);

            when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
            when(httpRequest.getAttribute(AdminAuthInterceptor.LOGIN_ADMIN_ATTRIBUTE)).thenReturn(null);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> resolver.resolveArgument(null, null, webRequest, null));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }

        @Test
        @DisplayName("로그인 속성이 Admin 타입이 아니면 UNAUTHORIZED 예외가 발생한다.")
        void throwsUnauthorized_whenAttributeIsWrongType() {
            // given
            NativeWebRequest webRequest = mock(NativeWebRequest.class);
            HttpServletRequest httpRequest = mock(HttpServletRequest.class);

            when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
            when(httpRequest.getAttribute(AdminAuthInterceptor.LOGIN_ADMIN_ATTRIBUTE)).thenReturn("not an admin");

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> resolver.resolveArgument(null, null, webRequest, null));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }

        @Test
        @DisplayName("HttpServletRequest를 얻을 수 없으면 UNAUTHORIZED 예외가 발생한다.")
        void throwsUnauthorized_whenRequestIsNull() {
            // given
            NativeWebRequest webRequest = mock(NativeWebRequest.class);

            when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(null);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> resolver.resolveArgument(null, null, webRequest, null));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }
    }
}
