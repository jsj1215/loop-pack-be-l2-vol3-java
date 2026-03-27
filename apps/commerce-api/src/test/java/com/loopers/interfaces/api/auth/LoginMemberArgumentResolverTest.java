package com.loopers.interfaces.api.auth;

import com.loopers.domain.member.BirthDate;
import com.loopers.domain.member.Email;
import com.loopers.domain.member.LoginId;
import com.loopers.domain.member.Member;
import com.loopers.domain.member.MemberName;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LoginMemberArgumentResolver 단위 테스트")
class LoginMemberArgumentResolverTest {

    private final LoginMemberArgumentResolver resolver = new LoginMemberArgumentResolver();

    private MethodParameter mockRequiredParameter() {
        MethodParameter parameter = mock(MethodParameter.class);
        LoginMember annotation = mock(LoginMember.class);
        when(annotation.required()).thenReturn(true);
        when(parameter.getParameterAnnotation(LoginMember.class)).thenReturn(annotation);
        return parameter;
    }

    private MethodParameter mockOptionalParameter() {
        MethodParameter parameter = mock(MethodParameter.class);
        LoginMember annotation = mock(LoginMember.class);
        when(annotation.required()).thenReturn(false);
        when(parameter.getParameterAnnotation(LoginMember.class)).thenReturn(annotation);
        return parameter;
    }

    @Nested
    @DisplayName("인증된 Member를 resolve할 때,")
    class ResolveArgument {

        @Test
        @DisplayName("로그인 속성이 정상적인 Member이면 반환한다.")
        void returnsMember_whenValidAttribute() {
            // given
            MethodParameter parameter = mockRequiredParameter();
            NativeWebRequest webRequest = mock(NativeWebRequest.class);
            HttpServletRequest httpRequest = mock(HttpServletRequest.class);
            Member member = new Member(new LoginId("testuser"), "encodedPw",
                    new MemberName("홍길동"), new Email("test@test.com"), new BirthDate("19900101"));

            when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
            when(httpRequest.getAttribute(MemberAuthInterceptor.LOGIN_MEMBER_ATTRIBUTE)).thenReturn(member);

            // when
            Object result = resolver.resolveArgument(parameter, null, webRequest, null);

            // then
            assertThat(result).isEqualTo(member);
        }

        @Test
        @DisplayName("로그인 속성이 없으면 UNAUTHORIZED 예외가 발생한다.")
        void throwsUnauthorized_whenAttributeIsNull() {
            // given
            MethodParameter parameter = mockRequiredParameter();
            NativeWebRequest webRequest = mock(NativeWebRequest.class);
            HttpServletRequest httpRequest = mock(HttpServletRequest.class);

            when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
            when(httpRequest.getAttribute(MemberAuthInterceptor.LOGIN_MEMBER_ATTRIBUTE)).thenReturn(null);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> resolver.resolveArgument(parameter, null, webRequest, null));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }

        @Test
        @DisplayName("로그인 속성이 Member 타입이 아니면 UNAUTHORIZED 예외가 발생한다.")
        void throwsUnauthorized_whenAttributeIsWrongType() {
            // given
            MethodParameter parameter = mockRequiredParameter();
            NativeWebRequest webRequest = mock(NativeWebRequest.class);
            HttpServletRequest httpRequest = mock(HttpServletRequest.class);

            when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
            when(httpRequest.getAttribute(MemberAuthInterceptor.LOGIN_MEMBER_ATTRIBUTE)).thenReturn("not a member");

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> resolver.resolveArgument(parameter, null, webRequest, null));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }

        @Test
        @DisplayName("HttpServletRequest를 얻을 수 없으면 UNAUTHORIZED 예외가 발생한다.")
        void throwsUnauthorized_whenRequestIsNull() {
            // given
            MethodParameter parameter = mockRequiredParameter();
            NativeWebRequest webRequest = mock(NativeWebRequest.class);

            when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(null);

            // when
            CoreException exception = assertThrows(CoreException.class,
                    () -> resolver.resolveArgument(parameter, null, webRequest, null));

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("required=false일 때,")
    class OptionalResolveArgument {

        @Test
        @DisplayName("로그인 속성이 없으면 null을 반환한다.")
        void returnsNull_whenAttributeIsNull() {
            // given
            MethodParameter parameter = mockOptionalParameter();
            NativeWebRequest webRequest = mock(NativeWebRequest.class);
            HttpServletRequest httpRequest = mock(HttpServletRequest.class);

            when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
            when(httpRequest.getAttribute(MemberAuthInterceptor.LOGIN_MEMBER_ATTRIBUTE)).thenReturn(null);

            // when
            Object result = resolver.resolveArgument(parameter, null, webRequest, null);

            // then
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("HttpServletRequest를 얻을 수 없으면 null을 반환한다.")
        void returnsNull_whenRequestIsNull() {
            // given
            MethodParameter parameter = mockOptionalParameter();
            NativeWebRequest webRequest = mock(NativeWebRequest.class);

            when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(null);

            // when
            Object result = resolver.resolveArgument(parameter, null, webRequest, null);

            // then
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("로그인 속성이 정상적인 Member이면 반환한다.")
        void returnsMember_whenValidAttribute() {
            // given
            MethodParameter parameter = mockOptionalParameter();
            NativeWebRequest webRequest = mock(NativeWebRequest.class);
            HttpServletRequest httpRequest = mock(HttpServletRequest.class);
            Member member = new Member(new LoginId("testuser"), "encodedPw",
                    new MemberName("홍길동"), new Email("test@test.com"), new BirthDate("19900101"));

            when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
            when(httpRequest.getAttribute(MemberAuthInterceptor.LOGIN_MEMBER_ATTRIBUTE)).thenReturn(member);

            // when
            Object result = resolver.resolveArgument(parameter, null, webRequest, null);

            // then
            assertThat(result).isEqualTo(member);
        }
    }
}
