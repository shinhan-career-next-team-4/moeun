package com.moeun.backend.docs;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.moeun.backend.config.TestcontainersConfig;

/** REST Docs 문서화 예시. 이 테스트가 통과해야 openapi3.yaml에 엔드포인트가 들어간다. */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureRestDocs
@Import(TestcontainersConfig.class)
class HealthDocsTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void health() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andDo(document("health",
                        resource(ResourceSnippetParameters.builder()
                                .tag("Ops")
                                .summary("서버 상태 확인")
                                .responseFields(
                                        fieldWithPath("status").description("UP / DOWN"),
                                        fieldWithPath("groups").description("프로브 그룹(liveness, readiness)").optional())
                                .build())));
    }
}
