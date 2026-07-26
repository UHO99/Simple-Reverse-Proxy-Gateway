package com.test.bench.testserver.common.config;

import com.test.bench.testserver.common.config.interceptor.SessionInterceptor;
import com.test.bench.testserver.common.config.resolver.LoginMemberArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SessionInterceptor())
                .order(1)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",      // 로그인
                        "/api/auth/logout",     // 로그아웃
                        "/api/users",           // 회원가입 (POST)
                        "/api/migration/**"     // Flyway 마이그레이션 제어
                );
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new LoginMemberArgumentResolver());
    }
}
