package com.test.bench.testserver.common.config.resolver;

import com.test.bench.benchtest.common.config.annotation.Login;
import com.test.bench.benchtest.common.exception.UnauthorizedException;
import com.test.bench.benchtest.common.session.SessionConst;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class LoginMemberArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        boolean hasLoginAnnotation = parameter.hasParameterAnnotation(Login.class);
        boolean isLongType = Long.class.isAssignableFrom(parameter.getParameterType());
        return hasLoginAnnotation && isLongType;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                   ModelAndViewContainer mavContainer,
                                   NativeWebRequest webRequest,
                                   WebDataBinderFactory binderFactory) {
        HttpServletRequest request = (HttpServletRequest) webRequest.getNativeRequest();
        HttpSession session = request.getSession(false);

        // SessionInterceptor가 먼저 동작해서 인증을 보장하지만,
        // 인터셉터 적용 대상에서 빠진 API에서 호출될 가능성을 대비해 방어적으로 한 번 더 검증
        if (session == null || session.getAttribute(SessionConst.LOGIN_USER_ID) == null) {
            throw new UnauthorizedException("로그인이 필요합니다.");
        }

        return session.getAttribute(SessionConst.LOGIN_USER_ID);
    }
}
