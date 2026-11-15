/**
 * 인증. 데모 계정(member)과 무상태 JWT 발급·검증.
 * <p>테이블: member. 주요 클래스: JwtTokenService, TokenRenewalFilter, @SkipRenewal (moved-to-code.md).
 * <p>다른 모듈은 memberId를 JWT subject로만 얻는다. 이 모듈을 직접 의존하지 않는다.
 */
@ApplicationModule(displayName = "인증", allowedDependencies = {})
package com.moeun.backend.auth;

import org.springframework.modulith.ApplicationModule;
