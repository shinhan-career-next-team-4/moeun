package com.moeun.backend.refdata;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.refdata.Sp500Series.MonthlyClose;
import com.moeun.backend.refdata.domain.CalcParam;
import com.moeun.backend.refdata.domain.CalcParamRepository;
import com.moeun.backend.refdata.domain.SavingsAvgRateRepository;
import com.moeun.backend.refdata.domain.Sp500MonthlyRepository;

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
    private final Sp500MonthlyRepository sp500MonthlyRepository;
    private final CalcParamRepository calcParamRepository;

    public Optional<SavingsAvgRateData> findActiveSavingsAvgRate() {
        return dataVersionService.findActive(DataKind.SAVINGS_RATE).flatMap(this::findSavingsAvgRate);
    }

    public Optional<Sp500Series> findActiveSp500Series() {
        return dataVersionService.findActive(DataKind.SP500).map(this::toSp500Series);
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
     * 지정한 버전의 S&P500 월말 종가. 활성이 아니어도 꺼낸다(#78).
     *
     * @param dataVersionId calc_run에 기록된 S&P500 버전
     * @throws IllegalArgumentException 버전이 없거나 S&P500 버전이 아닐 때
     * @throws IllegalStateException 버전은 있는데 종가 행이 없을 때
     */
    public Sp500Series requireSp500Series(long dataVersionId) {
        Sp500Series series = toSp500Series(dataVersionService.requireKind(dataVersionId, DataKind.SP500));
        if (series.monthlyCloses().isEmpty()) {
            throw new IllegalStateException("sp500 rows are missing: " + dataVersionId);
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

    private Sp500Series toSp500Series(DataVersionInfo version) {
        List<MonthlyClose> monthlyCloses =
                sp500MonthlyRepository.findByIdDataVersionIdOrderByIdYmAsc(version.id()).stream()
                        .map(row -> new MonthlyClose(YearMonth.from(row.getId().ym()), row.getCloseValue()))
                        .toList();
        return new Sp500Series(version, monthlyCloses);
    }

    private CalcParams toCalcParams(DataVersionInfo version) {
        Map<CalcParamKey, BigDecimal> values = new EnumMap<>(CalcParamKey.class);
        for (CalcParam param : calcParamRepository.findByIdDataVersionId(version.id())) {
            values.put(param.getId().paramKey(), param.getParamValue());
        }
        return new CalcParams(version, values);
    }
}
