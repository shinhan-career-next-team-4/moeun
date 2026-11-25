package com.moeun.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.moeun.backend.auth.domain.MemberRepository;
import com.moeun.backend.config.TestcontainersConfig;

/** 같은 아이디로 동시에 가입하면 늦은 쪽은 미리 조회를 지나 UNIQUE에 걸린다. 그때도 409다(P-7). */
@SpringBootTest(properties = "moeun.demo.accounts=demo01:pw-123456")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SignupRaceTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoSpyBean
    MemberRepository memberRepository;

    @Test
    void uniqueViolationAfterLookupIs409() throws Exception {
        // 다른 요청이 조회와 저장 사이에 같은 아이디를 먼저 만든 상황: 조회는 없다고 답하지만 행은 이미 있다
        doReturn(Optional.empty()).when(memberRepository).findByLoginId(eq("demo01"));
        long before = memberRepository.count();

        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pass-1234\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_LOGIN_ID"));

        assertThat(memberRepository.count()).isEqualTo(before);
    }
}
