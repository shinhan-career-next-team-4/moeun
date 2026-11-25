package com.moeun.backend.auth;

/** 회원이 로그아웃했다. 쿠키를 내리기 전에 동기로 발행하므로 리스너가 실패하면 요청도 실패한다. */
public record MemberLoggedOut(long memberId) {
}
