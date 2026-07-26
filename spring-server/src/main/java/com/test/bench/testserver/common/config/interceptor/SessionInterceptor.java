package com.test.bench.testserver.common.config.interceptor;

import com.test.bench.testserver.common.exception.UnauthorizedException;
import com.test.bench.testserver.common.session.SessionConst;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
public class SessionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute(SessionConst.LOGIN_USER_ID) == null) {
            log.warn("미인증 접근 차단: {} {}", request.getMethod(), request.getRequestURI());
            throw new UnauthorizedException("로그인이 필요합니다.");
        }

        return true;
    }
}
