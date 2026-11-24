package com.moeun.backend.calc.application;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.calc.CalcRunEtfQuery;
import com.moeun.backend.calc.domain.CalcRun;
import com.moeun.backend.calc.domain.CalcRunRepository;

/** calc_run.sp500_version_id가 있으면 ETF를 쓴 계산이다(NULL = ETF 비중 0%, AppliedDataVersions). */
@Service
class CalcRunEtfQueryService implements CalcRunEtfQuery {

    private final CalcRunRepository calcRuns;

    CalcRunEtfQueryService(CalcRunRepository calcRuns) {
        this.calcRuns = calcRuns;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> findRunsUsingEtf(Collection<Long> calcRunIds) {
        if (calcRunIds.isEmpty()) {
            return Set.of();
        }
        return calcRuns.findAllById(calcRunIds).stream()
                .filter(run -> run.getSp500VersionId() != null)
                .map(CalcRun::getId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
