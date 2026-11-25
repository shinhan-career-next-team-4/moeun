package com.moeun.backend.plan;

/**
 * 계획에 고를 수 있는 ETF 종목인지 알려준다(#154). 데이터를 적재한 종목만 고를 수 있다.
 * <p>plan은 refdata에 의존하지 않으므로 이 인터페이스만 두고, refdata를 읽을 수 있는 calc가 구현한다.
 */
@FunctionalInterface
public interface EtfProductCatalog {

    /** 그 종목의 활성 가격 데이터가 있으면 true. */
    boolean isAvailable(String etfProductCode);
}
