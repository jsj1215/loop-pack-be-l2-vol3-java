package com.loopers.config;

import com.loopers.interfaces.api.auth.AdminAuthInterceptor;
import com.loopers.interfaces.api.auth.LoginAdminArgumentResolver;
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
    private final AdminAuthInterceptor adminAuthInterceptor;
    private final LoginAdminArgumentResolver loginAdminArgumentResolver;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(memberAuthInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/members/signup",
                        "/api/v1/brands/**",
                        "/api/v1/products",
                        "/api/v1/products/{id}",
                        "/api/v1/examples/**"
                );

        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api-admin/v1/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(loginMemberArgumentResolver);
        resolvers.add(loginAdminArgumentResolver);
    }
}
