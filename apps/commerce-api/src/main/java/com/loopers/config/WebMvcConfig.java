package com.loopers.config;

import com.loopers.interfaces.api.auth.LoginMemberArgumentResolver;
import com.loopers.interfaces.api.auth.MemberAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@RequiredArgsConstructor
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final MemberAuthInterceptor memberAuthInterceptor;
    private final LoginMemberArgumentResolver loginMemberArgumentResolver;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(memberAuthInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/members/signup");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(loginMemberArgumentResolver);
    }
}
