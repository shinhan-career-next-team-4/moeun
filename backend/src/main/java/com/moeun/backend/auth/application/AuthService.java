package com.moeun.backend.auth.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.auth.MemberLoggedIn;
import com.moeun.backend.auth.MemberLoggedOut;
import com.moeun.backend.auth.domain.Member;
import com.moeun.backend.auth.domain.MemberRepository;

@Service
@Transactional(readOnly = true)
public class AuthService {

    /** 회원가입 아이디: 영문·숫자 4~20자(D-107). */
    private static final Pattern LOGIN_ID = Pattern.compile("[A-Za-z0-9]{4,20}");
    /** 회원가입 비밀번호 길이(D-107, 코드 포인트). */
    private static final int MIN_PASSWORD = 8;
    private static final int MAX_PASSWORD = 64;
    /** BCrypt는 72바이트를 넘는 비밀번호를 해시하지 않는다. 한글 같은 다바이트 문자는 64자 안이어도 넘을 수 있다. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final String dummyHash;

    AuthService(MemberRepository memberRepository, PasswordEncoder passwordEncoder, ApplicationEventPublisher events) {
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

    /** 회원가입 형식(D-107)에 맞지 않는 요청 필드 이름을 loginId, password 순서로. 비어 있으면 가입할 수 있다. */
    public List<String> malformedSignupFields(String loginId, String password) {
        List<String> fields = new ArrayList<>();
        if (!LOGIN_ID.matcher(loginId).matches()) {
            fields.add("loginId");
        }
        int length = password.codePointCount(0, password.length());
        if (length < MIN_PASSWORD || length > MAX_PASSWORD
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            fields.add("password");
        }
        return fields;
    }

    /**
     * 계정을 만들고 로그인 성공을 알린다(가입 즉시 로그인, D-107). 이미 있는 아이디면 비어 있다.
     * 같은 아이디로 동시에 가입하면 UNIQUE 위반(DataIntegrityViolationException)이 호출자에게 올라간다.
     */
    @Transactional
    public Optional<Member> signUp(String loginId, String password) {
        if (memberRepository.findByLoginId(loginId).isPresent()) {
            return Optional.empty();
        }
        Member member = memberRepository.save(new Member(loginId, passwordEncoder.encode(password)));
        events.publishEvent(new MemberLoggedIn(member.getId()));
        return Optional.of(member);
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
}
