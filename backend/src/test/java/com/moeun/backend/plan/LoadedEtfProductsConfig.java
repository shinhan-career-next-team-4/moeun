package com.moeun.backend.plan;

import java.util.Set;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * plan 모듈만 띄우는 테스트용 종목 목록. 실제 앱에서는 calc가 refdata의 적재된 종목으로 {@link EtfProductCatalog}를
 * 구현한다(#154). plan 모듈 테스트에는 calc가 없어 여기서 대신 넣는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LoadedEtfProductsConfig {

    /** 세 종목을 적재한 상태. */
    static final Set<String> LOADED = Set.of("SP500", "069500", "133690");

    @Bean
    EtfProductCatalog etfProductCatalog() {
        return LOADED::contains;
    }
}
