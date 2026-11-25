package com.moeun.backend.auth.application;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.auth.DemoReset;
import com.moeun.backend.auth.MemberLoggedIn;
import com.moeun.backend.auth.MemberLoggedOut;
import com.moeun.backend.auth.domain.Member;
import com.moeun.backend.auth.domain.MemberRepository;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final String dummyHash;
    private final Set<String> demoLoginIds;

    AuthService(MemberRepository memberRepository, PasswordEncoder passwordEncoder, ApplicationEventPublisher events,
            @Value("${moeun.demo.accounts:}") String demoAccounts) {
        this.demoLoginIds = parseDemoLoginIds(demoAccounts);
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
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

    /** 로그인 성공을 알린다. 동기 리스너가 실패하면 예외가 그대로 올라가 로그인도 실패한다. 리스너와 같은 트랜잭션이다. */
    @Transactional
    public void loggedIn(long memberId) {
        events.publishEvent(new MemberLoggedIn(memberId));
    }

    @Transactional
    public void loggedOut(long memberId) {
        events.publishEvent(new MemberLoggedOut(memberId));
    }

    /**
     * 데모 계정이면 그 회원의 계획과 대화를 지우라고 알린다. 데모 계정(moeun.demo.accounts)이 아니면 아무것도 지우지 않고
     * false를 돌려준다. 동기 리스너가 실패하면 예외가 그대로 올라가 요청도 실패한다.
     */
    @Transactional
    public boolean demoReset(long memberId) {
        boolean demo = memberRepository.findById(memberId).map(Member::getLoginId).filter(demoLoginIds::contains)
                .isPresent();
        if (demo) {
            events.publishEvent(new DemoReset(memberId));
        }
        return demo;
    }

    private static Set<String> parseDemoLoginIds(String accounts) {
        return Arrays.stream(accounts.split(","))
                .map(entry -> entry.trim().split(":", 2)[0].trim())
                .filter(id -> !id.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
