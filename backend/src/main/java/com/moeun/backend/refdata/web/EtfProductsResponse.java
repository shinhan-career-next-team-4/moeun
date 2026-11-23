package com.moeun.backend.refdata.web;

import java.math.BigDecimal;
import java.util.List;

import com.moeun.backend.refdata.EtfProduct;

/**
 * ETF 종목 목록 응답(#220). 배열을 바로 주지 않고 감싸서, 나중에 칸을 더해도 프론트가 깨지지 않게 한다.
 *
 * @param products 고를 수 있는 종목. 대표 상품이 첫째
 */
record EtfProductsResponse(List<Product> products) {

    EtfProductsResponse {
        products = List.copyOf(products);
    }

    /**
     * 종목 하나. 데이터 버전 id 같은 내부 값은 싣지 않는다.
     *
     * @param code 종목 코드(SP500, 069500, …). 계획·대화 조건에 쓰는 값
     * @param displayName 화면 이름. 상품명 대신 추종 지수로 쓴다("코스피200 추종 ETF")
     * @param gainTaxRatePct 매매차익 세율(%). 국내 주식형은 0
     */
    record Product(String code, String displayName, BigDecimal gainTaxRatePct) {

        static Product from(EtfProduct product) {
            return new Product(product.code(), product.displayName(), toPlain(product.gainTaxRatePct()));
        }

        /** {@link EtfProduct}가 끝자리 0을 빼므로 10은 1E+1로 온다. JSON에 지수 표기가 나가지 않게 소수 자릿수를 0 밑으로 두지 않는다. */
        private static BigDecimal toPlain(BigDecimal rate) {
            BigDecimal stripped = rate.stripTrailingZeros();
            return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
        }
    }
}
