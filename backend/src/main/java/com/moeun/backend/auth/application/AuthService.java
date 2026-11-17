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

    AuthService(MemberRepository memberRepository, PasswordEncoder passwordEncoder, JdbcClient jdbcClient) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcClient = jdbcClient;
    }

    /** 아이디와 비밀번호가 맞으면 계정을 돌려준다. 틀린 이유(아이디/비밀번호)는 구분하지 않는다. */
    public Optional<Member> authenticate(String loginId, String password) {
        return memberRepository.findByLoginId(loginId)
                .filter(member -> passwordEncoder.matches(password, member.getPasswordHash()));
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
