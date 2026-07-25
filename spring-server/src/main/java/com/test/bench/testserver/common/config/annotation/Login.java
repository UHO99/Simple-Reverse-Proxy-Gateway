package com.test.bench.testserver.common.config.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 컨트롤러 파라미터에 붙이면 LoginMemberArgumentResolver가 세션의 로그인 유저 id를 주입해줌
// 예) public ResponseEntity<?> method(@Login Long userId) { ... }
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Login {
}
