package com.loopers.interfaces.api.auth;

import com.loopers.domain.auth.Admin;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class LoginAdminArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(LoginAdmin.class)
                && Admin.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw new CoreException(ErrorType.UNAUTHORIZED, "인증 정보를 확인할 수 없습니다.");
        }

        Object attribute = request.getAttribute(AdminAuthInterceptor.LOGIN_ADMIN_ATTRIBUTE);
        if (!(attribute instanceof Admin)) {
            throw new CoreException(ErrorType.UNAUTHORIZED, "인증 정보가 유효하지 않습니다.");
        }

        return attribute;
    }
}
