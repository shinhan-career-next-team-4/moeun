package com.moeun.backend.calc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moeun.backend.calc.CalcInput;
import com.moeun.backend.calc.CalcResult;
import com.moeun.backend.calc.Scenario;
import com.moeun.backend.calc.ScenarioResult;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.DataVersionInfo;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.EtfSeries;

/** 경로별 최종 평가액을 다시 만든 값이 엔진이 쓴 경로와 같은지(#302). 레포의 테스트용 가짜 종가를 쓴다. */
class BootstrapPathsTest {

    private static final CalcParams PARAMS = GoldenCase.createParams(new BigDecimal("15.4"));
    private static final EtfSeries SP500 = GoldenCase.readTestEtfSeries();
    private static final YearMonth START = YearMonth.of(2026, 11);

    /** 기준 계획 모양: 목표 2,000만원, 36개월, 보유 500만원, 월 30만원, 금리 2.84%, 적금 50 : ETF 50. */
    private static CalcInput basePlan(int savingsRatioPct) {
        return new CalcInput(20_000_000L, 36, START, 5_000_000L, 300_000L, new BigDecimal("2.84"), savingsRatioPct,
                Scenario.AVG);
    }

    private static long engineFinal(CalcResult result, Scenario scenario) {
        return result.scenarios().stream().filter(s -> s.scenario() == scenario).findFirst()
                .map(ScenarioResult::afterTaxAsset).orElseThrow();
    }

    @Test
    @DisplayName("경로 수만큼 최종 금액이 나오고, 거기서 고른 3구간이 엔진의 BAD·AVG·GOOD와 원 단위까지 같다")
    void matchesEngineScenarios() {
        CalcInput input = basePlan(50);

        BootstrapPaths.Result paths = BootstrapPaths.simulate(input, PARAMS, SP500);
        CalcResult engine = new CalcEngine().calculate(input, PARAMS, SP500);

        assertThat(paths.finalTotals()).hasSize(10_000);
        assertThat(paths.badWon()).isEqualTo(engineFinal(engine, Scenario.BAD));
        assertThat(paths.averageWon()).isEqualTo(engineFinal(engine, Scenario.AVG));
        assertThat(paths.goodWon()).isEqualTo(engineFinal(engine, Scenario.GOOD));
    }

    @Test
    @DisplayName("ETF 100%·비과세 종목에서도 엔진과 같다(적금 0원, 종목 세율 0%)")
    void matchesEngineForEtfOnlyTaxFreeProduct() {
        EtfProduct kospi = new EtfProduct("069500", "코스피200 추종 ETF", BigDecimal.ZERO);
        EtfSeries taxFree = new EtfSeries(new DataVersionInfo(77L, SP500.version().dataKind(), "test-kospi",
                SP500.version().baseDate(), "테스트용 가짜 데이터", kospi.code()), kospi, SP500.monthlyCloses());
        CalcInput input = basePlan(0);

        BootstrapPaths.Result paths = BootstrapPaths.simulate(input, PARAMS, taxFree);
        CalcResult engine = new CalcEngine().calculate(input, PARAMS, taxFree);

        assertThat(paths.badWon()).isEqualTo(engineFinal(engine, Scenario.BAD));
        assertThat(paths.averageWon()).isEqualTo(engineFinal(engine, Scenario.AVG));
        assertThat(paths.goodWon()).isEqualTo(engineFinal(engine, Scenario.GOOD));
    }

    @Test
    @DisplayName("같은 입력·같은 데이터면 경로별 값이 순서까지 같다(시드 고정)")
    void isDeterministic() {
        assertThat(BootstrapPaths.simulate(basePlan(50), PARAMS, SP500).finalWon())
                .isEqualTo(BootstrapPaths.simulate(basePlan(50), PARAMS, SP500).finalWon());
    }

    @Test
    @DisplayName("(예외) ETF 0% 계획은 경로가 갈리지 않으므로 다시 만들지 않는다")
    void rejectsSavingsOnlyPlan() {
        assertThatIllegalArgumentException().isThrownBy(() -> BootstrapPaths.simulate(basePlan(100), PARAMS, SP500));
    }
}
