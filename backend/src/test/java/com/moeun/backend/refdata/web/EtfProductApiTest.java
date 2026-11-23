package com.moeun.backend.refdata.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.config.TestcontainersConfig;

import jakarta.servlet.http.Cookie;

/**
 * ETF 종목 목록 API(#220)를 실제 서버 설정으로 확인한다. 가격 파일은 S&P500과 069500만 주고, 133690은 없는 경로를
 * 직접 줘서 적재되지 않게 한다(계산할 수 없는 종목은 고를 수 없다). 개발자 로컬 파일과 관계없이 같은 결과다.
 */
@SpringBootTest(properties = {"moeun.demo.accounts=demo01:pw-123456",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv",
    "MOEUN_ETF_069500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv",
    "MOEUN_ETF_133690_FILE=file:build/no-etf-133690-for-api-test.csv"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class EtfProductApiTest {

    @Autowired
    MockMvc mockMvc;

    private Cookie login() throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}"))
                .andReturn().getResponse().getCookie(JwtTokenService.COOKIE_NAME);
    }

    @Test
    @DisplayName("로그인한 사용자는 적재된 종목만, 설정 순서(대표 상품 먼저)로 설정의 화면 이름·세율을 받는다")
    void listsLoadedProducts() throws Exception {
        mockMvc.perform(get("/api/etf-products").cookie(login()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"products\":["
                        + "{\"code\":\"SP500\",\"displayName\":\"S&P500 추종 ETF\",\"gainTaxRatePct\":15.4},"
                        + "{\"code\":\"069500\",\"displayName\":\"코스피200 추종 ETF\",\"gainTaxRatePct\":0}]}",
                        JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("(예외) 로그인하지 않으면 401 UNAUTHENTICATED(계약 C3-1 형식)")
    void rejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/etf-products"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
