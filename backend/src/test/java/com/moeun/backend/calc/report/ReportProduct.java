package com.moeun.backend.calc.report;

import java.util.Objects;
import java.util.Optional;

import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.EtfSeries;

/**
 * 리포트에 넣을 ETF 종목 하나. 가격 파일은 레포에 없어(TBD-08) 개발자마다 없을 수 있으므로 시계열은 비어 있어도 된다.
 *
 * @param product 종목 정보(화면 이름, 매매차익 세율)
 * @param series 월말 종가. 파일이 없으면 null
 */
record ReportProduct(EtfProduct product, EtfSeries series) {

    ReportProduct {
        Objects.requireNonNull(product, "product");
    }

    static ReportProduct of(EtfSeries series) {
        return new ReportProduct(series.product(), series);
    }

    static ReportProduct missing(EtfProduct product) {
        return new ReportProduct(product, null);
    }

    Optional<EtfSeries> findSeries() {
        return Optional.ofNullable(series);
    }
}
