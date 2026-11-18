package com.moeun.backend.auth.application;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.auth.domain.Member;
import com.moeun.backend.auth.domain.MemberRepository;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcClient jdbcClient;
    private final String dummyHash;

    AuthService(MemberRepository memberRepository, PasswordEncoder passwordEncoder, JdbcClient jdbcClient) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcClient = jdbcClient;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    /**
     * 아이디와 비밀번호가 맞으면 계정을 돌려준다. 틀린 이유(아이디/비밀번호)는 구분하지 않는다.
     * 없는 아이디도 가짜 해시와 비교해 응답 시간으로 계정 존재 여부를 알 수 없게 한다.
     */
    public Optional<Member> authenticate(String loginId, String password) {
        Optional<Member> member = memberRepository.findByLoginId(loginId);
        String hash = member.map(Member::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(password, hash);
        return member.filter(found -> matches);
    }

    public Member findMember(long memberId) {
        return memberRepository.findById(memberId).orElseThrow();
    }

    /** plan 모듈에 의존하지 않으려고 plan 테이블을 직접 조회한다. */
    public boolean hasPlan(long memberId) {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM plan WHERE member_id = ?)")
                .param(memberId)
                .query(Boolean.class)
                .single();
    }
}
