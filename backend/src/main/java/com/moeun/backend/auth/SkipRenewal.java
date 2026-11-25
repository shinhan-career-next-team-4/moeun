package com.moeun.backend.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 이 핸들러 응답에는 새 토큰 쿠키를 발급하지 않는다(폴링, 로그아웃). 사용자 조작이 아닌 요청이 세션을 늘리지 않게 한다. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SkipRenewal {
}
