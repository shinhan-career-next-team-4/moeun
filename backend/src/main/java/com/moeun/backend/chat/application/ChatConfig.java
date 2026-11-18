package com.moeun.backend.chat.application;

import java.net.http.HttpClient;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AiServerProperties.class)
class ChatConfig {

    /** ai-server로 나가는 모든 요청이 거치는 팩토리. 연결·응답 타임아웃이 여기서 정해진다. */
    @Bean
    JdkClientHttpRequestFactory aiServerRequestFactory(AiServerProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    @Bean
    RestClient aiServerRestClient(AiServerProperties properties,
            @Qualifier("aiServerRequestFactory") JdkClientHttpRequestFactory requestFactory) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Api-Key", properties.internalApiKey())
                .build();
    }
}
