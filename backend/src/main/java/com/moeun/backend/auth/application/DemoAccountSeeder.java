package com.moeun.backend.auth.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.auth.domain.Member;
import com.moeun.backend.auth.domain.MemberRepository;

/**
 * 기동 시 moeun.demo.accounts("id:pw,id:pw")의 데모 계정 중 없는 것만 만든다.
 * 비밀번호는 저장소에 두지 않고 로컬 .env(DEMO_ACCOUNTS)로만 넣는다. 이미 있는 계정의 비밀번호는 바꾸지 않는다.
 */
@Component
public class DemoAccountSeeder implements ApplicationRunner {

    private final String accounts;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    DemoAccountSeeder(@Value("${moeun.demo.accounts:}") String accounts, MemberRepository memberRepository,
            PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (accounts.isBlank()) {
            return;
        }
        for (String entry : accounts.split(",")) {
            String[] parts = entry.trim().split(":", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalStateException("moeun.demo.accounts 형식은 id:password 입니다. 잘못된 항목이 있습니다.");
            }
            String loginId = parts[0].trim();
            if (memberRepository.findByLoginId(loginId).isEmpty()) {
                memberRepository.save(new Member(loginId, passwordEncoder.encode(parts[1])));
            }
        }
    }
}
