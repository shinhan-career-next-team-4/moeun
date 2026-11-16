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
 * 지금 활성인 기준 데이터를 값으로 꺼낸다.
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
        return dataVersionService.findActive(DataKind.SAVINGS_RATE)
                .flatMap(version -> savingsAvgRateRepository.findById(version.id())
                        .map(rate -> new SavingsAvgRateData(version, YearMonth.from(rate.getBaseMonth()),
                                rate.getAvgRatePct())));
    }

    public Optional<Sp500Series> findActiveSp500Series() {
        return dataVersionService.findActive(DataKind.SP500)
                .map(version -> {
                    List<MonthlyClose> monthlyCloses =
                            sp500MonthlyRepository.findByIdDataVersionIdOrderByIdYmAsc(version.id()).stream()
                                    .map(row -> new MonthlyClose(YearMonth.from(row.getId().ym()),
                                            row.getCloseValue()))
                                    .toList();
                    return new Sp500Series(version, monthlyCloses);
                });
    }

    public Optional<CalcParams> findActiveCalcParams() {
        return dataVersionService.findActive(DataKind.CALC_PARAM)
                .map(version -> {
                    Map<CalcParamKey, BigDecimal> values = new EnumMap<>(CalcParamKey.class);
                    for (CalcParam param : calcParamRepository.findByIdDataVersionId(version.id())) {
                        values.put(param.getId().paramKey(), param.getParamValue());
                    }
                    return new CalcParams(version, values);
                });
    }
}
