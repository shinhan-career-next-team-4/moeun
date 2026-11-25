package com.moeun.backend.docs;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.hamcrest.Matchers.contains;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.moeun.backend.auth.application.JwtTokenService;
import com.moeun.backend.config.TestcontainersConfig;

import jakarta.servlet.http.Cookie;

/** ETF 종목 목록 문서(#220). 이 테스트가 openapi3.yaml의 GET /api/etf-products 원천이다. */
@SpringBootTest(properties = {"moeun.demo.accounts=demo01:pw-123456",
    "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv",
    "MOEUN_ETF_069500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv",
    "MOEUN_ETF_133690_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv"})
@AutoConfigureMockMvc
@AutoConfigureRestDocs
@Import(TestcontainersConfig.class)
class EtfProductDocsTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void etfProductsDoc() throws Exception {
        Cookie cookie = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"demo01\",\"password\":\"pw-123456\"}"))
                .andReturn().getResponse().getCookie(JwtTokenService.COOKIE_NAME);

        mockMvc.perform(get("/api/etf-products").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[*].code").value(contains("SP500", "069500", "133690")))
                .andDo(document("etf-products", resource(ResourceSnippetParameters.builder()
                        .tag("refdata").summary("고를 수 있는 ETF 종목 목록")
                        .description("가격 데이터가 적재된 종목만 나온다(없는 종목은 계산할 수 없어 빠짐). "
                                + "순서는 설정(moeun.refdata.seeds) 순서이고 대표 상품(SP500)이 첫째. "
                                + "적재된 종목이 없으면 빈 목록. 로그인하지 않으면 401 UNAUTHENTICATED.")
                        .responseFields(
                                fieldWithPath("products").description("고를 수 있는 종목. 대표 상품이 첫째"),
                                fieldWithPath("products[].code").description("종목 코드(SP500, 069500, 133690)"),
                                fieldWithPath("products[].displayName")
                                        .description("화면 이름. 상품명 대신 추종 지수(예: 코스피200 추종 ETF)"),
                                fieldWithPath("products[].gainTaxRatePct")
                                        .description("매매차익 세율(%). 국내 주식형은 0"))
                        .build())));
    }
}
