package com.moeun.backend.refdata;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.refdata.EtfSeries.MonthlyClose;
import com.moeun.backend.refdata.domain.CalcParam;
import com.moeun.backend.refdata.domain.CalcParamRepository;
import com.moeun.backend.refdata.domain.EtfMonthlyRepository;
import com.moeun.backend.refdata.domain.EtfProductVersionRepository;
import com.moeun.backend.refdata.domain.SavingsAvgRateRepository;

import lombok.RequiredArgsConstructor;

/**
 * 기준 데이터를 값으로 꺼낸다. 지금 활성인 버전과, calc_run이 기록한 특정 버전 두 가지 길이 있다.
 * 엔티티를 밖으로 내보내지 않아서 계산 엔진이 DB를 모르고도 쓸 수 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReferenceDataService {

    private final DataVersionService dataVersionService;
    private final SavingsAvgRateRepository savingsAvgRateRepository;
    private final EtfMonthlyRepository etfMonthlyRepository;
    private final EtfProductVersionRepository etfProductVersionRepository;
    private final CalcParamRepository calcParamRepository;

    public Optional<SavingsAvgRateData> findActiveSavingsAvgRate() {
        return dataVersionService.findActive(DataKind.SAVINGS_RATE).flatMap(this::findSavingsAvgRate);
    }

    /** 그 종목의 지금 활성인 월말 종가. 데이터를 적재하지 않은 종목이면 비어 있다. */
    public Optional<EtfSeries> findActiveEtfSeries(String productCode) {
        return dataVersionService.findActiveEtf(productCode).map(this::toEtfSeries);
    }

    /** 대표 상품(S&P500 추종 ETF)의 지금 활성인 월말 종가. 종목을 정하지 않은 계획은 이 종목으로 계산한다. */
    public Optional<EtfSeries> findRepresentativeEtfSeries() {
        return findActiveEtfSeries(EtfProduct.REPRESENTATIVE_CODE);
    }

    /**
     * 지금 고를 수 있는 ETF 종목들(데이터가 적재된 종목, 종목 코드 순). 프론트·AI가 목록을 하드코딩하지 않게 이 목록을 쓴다.
     */
    public List<EtfProduct> findActiveEtfProducts() {
        return dataVersionService.findActiveEtfVersions().stream()
                .map(version -> requireProduct(version.id()))
                .toList();
    }

    public Optional<CalcParams> findActiveCalcParams() {
        return dataVersionService.findActive(DataKind.CALC_PARAM).map(this::toCalcParams);
    }

    /**
     * 지정한 버전의 적금 평균금리. 활성이 아니어도 꺼낸다. 계산 기록을 그때의 데이터로 다시 계산할 때 쓴다(#78).
     *
     * @param dataVersionId calc_run에 기록된 금리 버전
     * @throws IllegalArgumentException 버전이 없거나 적금 금리 버전이 아닐 때
     * @throws IllegalStateException 버전은 있는데 금리 행이 없을 때
     */
    public SavingsAvgRateData requireSavingsAvgRate(long dataVersionId) {
        DataVersionInfo version = dataVersionService.requireKind(dataVersionId, DataKind.SAVINGS_RATE);
        return findSavingsAvgRate(version)
                .orElseThrow(() -> new IllegalStateException("savings rate rows are missing: " + dataVersionId));
    }

    /**
     * 지정한 버전의 ETF 월말 종가와 그 버전의 종목 정보. 활성이 아니어도 꺼낸다(#78, #138).
     *
     * @param dataVersionId calc_run에 기록된 ETF 가격 버전
     * @throws IllegalArgumentException 버전이 없거나 ETF 가격 버전이 아닐 때
     * @throws IllegalStateException 버전은 있는데 종가 행이나 종목 정보가 없을 때
     */
    public EtfSeries requireEtfSeries(long dataVersionId) {
        EtfSeries series = toEtfSeries(dataVersionService.requireKind(dataVersionId, DataKind.ETF_PRICE));
        if (series.monthlyCloses().isEmpty()) {
            throw new IllegalStateException("etf price rows are missing: " + dataVersionId);
        }
        return series;
    }

    /**
     * 지정한 버전의 계산 파라미터. 활성이 아니어도 꺼낸다(#78).
     *
     * @param dataVersionId calc_run에 기록된 계산 파라미터 버전
     * @throws IllegalArgumentException 버전이 없거나 계산 파라미터 버전이 아닐 때
     * @throws IllegalStateException 버전은 있는데 파라미터 행이 없을 때
     */
    public CalcParams requireCalcParams(long dataVersionId) {
        CalcParams params = toCalcParams(dataVersionService.requireKind(dataVersionId, DataKind.CALC_PARAM));
        if (params.values().isEmpty()) {
            throw new IllegalStateException("calc param rows are missing: " + dataVersionId);
        }
        return params;
    }

    private Optional<SavingsAvgRateData> findSavingsAvgRate(DataVersionInfo version) {
        return savingsAvgRateRepository.findById(version.id())
                .map(rate -> new SavingsAvgRateData(version, YearMonth.from(rate.getBaseMonth()),
                        rate.getAvgRatePct()));
    }

    private EtfSeries toEtfSeries(DataVersionInfo version) {
        List<MonthlyClose> monthlyCloses =
                etfMonthlyRepository.findByIdDataVersionIdOrderByIdYmAsc(version.id()).stream()
                        .map(row -> new MonthlyClose(YearMonth.from(row.getId().ym()), row.getCloseValue()))
                        .toList();
        EtfProduct product = requireProduct(version.id());
        if (!product.code().equals(version.productCode())) {
            throw new IllegalStateException("etf product code does not match its version: " + version.id());
        }
        return new EtfSeries(version, product, monthlyCloses);
    }

    private EtfProduct requireProduct(long dataVersionId) {
        return etfProductVersionRepository.findById(dataVersionId)
                .orElseThrow(() -> new IllegalStateException("etf product info is missing: " + dataVersionId))
                .toProduct();
    }

    private CalcParams toCalcParams(DataVersionInfo version) {
        Map<CalcParamKey, BigDecimal> values = new EnumMap<>(CalcParamKey.class);
        for (CalcParam param : calcParamRepository.findByIdDataVersionId(version.id())) {
            values.put(param.getId().paramKey(), param.getParamValue());
        }
        return new CalcParams(version, values);
    }
}
