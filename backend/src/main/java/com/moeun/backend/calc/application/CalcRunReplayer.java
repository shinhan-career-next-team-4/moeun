package com.moeun.backend.calc.application;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.moeun.backend.calc.domain.CalcRun;
import com.moeun.backend.calc.domain.CalcRunRepository;
import com.moeun.backend.calc.domain.RunType;
import com.moeun.backend.plan.PlanSaveService;
import com.moeun.backend.plan.PlanView;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.EtfSeries;
import com.moeun.backend.refdata.ReferenceDataService;
import com.moeun.backend.refdata.SavingsAvgRateData;

/**
 * 계산 기록 재현의 공통 단계. planContext({@link RecalculatingPlanContextProvider})와 대화 비교 재계산
 * ({@link ChatComparisonRecomputer})이 같이 쓴다: calc_run 조회 → plan 공개 API로 소유권 확인 → 그 기록이 남긴 기준 데이터
 * 버전으로 데이터 조회. 트랜잭션은 부르는 쪽이 연다.
 */
@Component
class CalcRunReplayer {

    private final CalcRunRepository calcRunRepository;
    private final PlanSaveService planSaveService;
    private final ReferenceDataService referenceDataService;

    CalcRunReplayer(CalcRunRepository calcRunRepository, PlanSaveService planSaveService,
            ReferenceDataService referenceDataService) {
        this.calcRunRepository = calcRunRepository;
        this.planSaveService = planSaveService;
        this.referenceDataService = referenceDataService;
    }

    /**
     * 소유권 확인 2단계(C1a-10). ① 종류까지 맞는 calc_run ② 그 계획 버전이 이 회원의 것인지 plan 공개 API로 확인.
     * 없는 기록·남의 기록·다른 종류의 기록은 모두 빈 값이다.
     */
    Optional<OwnedRun> findOwnedRun(long memberId, long calcRunId, RunType runType) {
        return calcRunRepository.findByIdAndRunType(calcRunId, runType)
                .flatMap(run -> planSaveService.findVersion(memberId, run.getPlanVersionId())
                        .map(plan -> new OwnedRun(run, plan)));
    }

    /** 기록이 남긴 버전의 기준 데이터. 기록에 없는 데이터(ETF 0%의 S&P500, 직접 넣은 금리의 평균금리)는 쓰지 않았다. */
    RecordedData loadRecordedData(CalcRun run) {
        CalcParams params = referenceDataService.requireCalcParams(run.getCalcParamVersionId());
        EtfSeries sp500 = run.getSp500VersionId() == null
                ? null : referenceDataService.requireEtfSeries(run.getSp500VersionId());
        SavingsAvgRateData defaultRate = run.getSavingsRateVersionId() == null
                ? null : referenceDataService.requireSavingsAvgRate(run.getSavingsRateVersionId());
        // CHAT 기록의 changed가 다른 종목이었으면 그 버전, 아니면 current와 같은 종목(#157)
        EtfSeries changedEtf = run.getChangedEtfVersionId() == null
                ? sp500 : referenceDataService.requireEtfSeries(run.getChangedEtfVersionId());
        return new RecordedData(params, sp500, changedEtf, defaultRate);
    }

    record OwnedRun(CalcRun run, PlanView plan) {
    }

    record RecordedData(CalcParams params, EtfSeries sp500, EtfSeries changedEtf, SavingsAvgRateData defaultRate) {
    }
}
