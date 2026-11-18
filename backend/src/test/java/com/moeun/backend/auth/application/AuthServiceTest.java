package com.moeun.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.moeun.backend.auth.domain.Member;
import com.moeun.backend.auth.domain.MemberRepository;

class AuthServiceTest {

    /** matches 호출을 기록하는 인코더. 없는 아이디도 해시 비교를 하는지 본다 */
    static class RecordingEncoder implements PasswordEncoder {
        final PasswordEncoder delegate = new BCryptPasswordEncoder(4);
        final List<String> matchedHashes = new ArrayList<>();

        @Override
        public String encode(CharSequence raw) {
            return delegate.encode(raw);
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            matchedHashes.add(encoded);
            return delegate.matches(raw, encoded);
        }
    }

    private final MemberRepository repository = mock(MemberRepository.class);
    private final RecordingEncoder encoder = new RecordingEncoder();
    private final AuthService service = new AuthService(repository, encoder, mock(JdbcClient.class));

    @Test
    void unknownIdStillComparesAgainstDummyHash() {
        when(repository.findByLoginId("nobody")).thenReturn(Optional.empty());

        assertThat(service.authenticate("nobody", "pw")).isEmpty();
        assertThat(encoder.matchedHashes).hasSize(1).allSatisfy(hash -> assertThat(hash).startsWith("$2"));
    }

    @Test
    void knownIdComparesAgainstStoredHash() {
        String hash = encoder.encode("pw");
        when(repository.findByLoginId("demo01")).thenReturn(Optional.of(new Member("demo01", hash)));

        assertThat(service.authenticate("demo01", "pw")).isPresent();
        assertThat(service.authenticate("demo01", "wrong")).isEmpty();
        assertThat(encoder.matchedHashes).containsExactly(hash, hash);
    }
}
