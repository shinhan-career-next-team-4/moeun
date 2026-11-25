package com.moeun.backend.refdata;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * ETF 종목 하나(#138). 종목 정보는 코드에 박지 않고 기준 데이터 설정(application.yml의 seeds)에서 온다.
 * 종목을 늘릴 때는 데이터 파일과 설정 한 줄만 더한다.
 *
 * @param code 종목 코드. 예: {@code SP500}, {@code 069500}
 * @param displayName 화면 이름. 상품명 대신 추종 지수로 쓴다(CP-06). 예: "코스피200 추종 ETF"
 * @param gainTaxRatePct 매매차익 세율(%). 국내 주식형 ETF는 비과세라 0
 */
public record EtfProduct(String code, String displayName, BigDecimal gainTaxRatePct) {

    /** 대표 상품(S&P500 추종 ETF). 종목을 정하지 않은 계획은 이 종목으로 계산한다. */
    public static final String REPRESENTATIVE_CODE = "SP500";

    public EtfProduct {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(gainTaxRatePct, "gainTaxRatePct");
        // DB(NUMERIC(6,3))에서 읽은 15.400과 설정의 15.4가 같은 종목으로 보이게 자릿수를 맞춘다
        gainTaxRatePct = gainTaxRatePct.stripTrailingZeros();
    }
}
