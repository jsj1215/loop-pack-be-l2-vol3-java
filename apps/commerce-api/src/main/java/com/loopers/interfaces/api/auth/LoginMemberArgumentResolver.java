package com.loopers.interfaces.api.auth;

import com.loopers.domain.member.Member;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

// @LoginMember 어노테이션이 붙은 파라미터에 인증된 Member 객체를 주입하는 ArgumentResolver.
// MemberAuthInterceptor가 설정한 요청 속성에서 Member를 꺼내 바인딩한다.
@Component
public class LoginMemberArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(LoginMember.class)
                && Member.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        LoginMember annotation = parameter.getParameterAnnotation(LoginMember.class);
        boolean required = annotation == null || annotation.required();

        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            if (!required) {
                return null;
            }
            throw new CoreException(ErrorType.UNAUTHORIZED, "인증 정보를 확인할 수 없습니다.");
        }

        Object attribute = request.getAttribute(MemberAuthInterceptor.LOGIN_MEMBER_ATTRIBUTE);
        if (!(attribute instanceof Member)) {
            if (!required) {
                return null;
            }
            throw new CoreException(ErrorType.UNAUTHORIZED, "인증 정보가 유효하지 않습니다.");
        }

        return attribute;
    }
}
