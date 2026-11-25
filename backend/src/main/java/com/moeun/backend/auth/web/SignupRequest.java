package com.moeun.backend.auth.web;

import jakarta.validation.constraints.NotEmpty;

/** P-7 회원가입 요청. 빈 값은 REQUIRED, 형식은 컨트롤러가 INVALID_FORMAT으로 검사한다(D-107). */
public record SignupRequest(@NotEmpty String loginId, @NotEmpty String password) {
}
