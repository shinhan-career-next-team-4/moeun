package com.moeun.backend.calc.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moeun.backend.calc.CalcComparison;
import com.moeun.backend.config.copy.CopyBook;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.PlanView;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.DataVersionInfo;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.EtfSeries;

/**
 * current와 changed가 서로 다른 ETF 종목을 쓰는 비교(#157, #150 수용 기준 7). 대화 조건에 종목이 생기기 전이라 비교 계산
 * 단계에서 직접 확인한다. 같은 가짜 종가에 종목 정보만 다르게 붙여서, 결과 차이는 ETF 차익 세금뿐이다.
 */
class CompareTwoProductsTest {

    private static final Formatter FORMATTER = new Formatter(CopyBook.load(Path.of("../copy/copy.ko.yml")));
    private static final CalcParams PARAMS = GoldenCase.createParams(new BigDecimal("15.4"));
    private static final EtfSeries SP500 = GoldenCase.readTestEtfSeries();
    private static final long KOSPI_VERSION_ID = 77L;
    private static final ConditionOverride NO_CHANGE = new ConditionOverride(null, null, null, null, null, null);

    // 비교 계산(computeComparison)은 계획 조회·기준 데이터 조회·기록을 쓰지 않는다
    private final CompareConditionsUseCase useCase = new CompareConditionsUseCase(null, null, new ConditionResolver(),
            new CalcEngine(), null, new ResultDisplayBuilder(FORMATTER), FORMATTER);

    /** 목표 3,000만원, 36개월, 보유 0원, 월 30만원, 금리 2.84%(입력), 적금 50 : ETF 50. */
    private static PlanView plan() {
        return plan(50);
    }

    private static PlanView plan(int savingsRatioPct) {
        return new PlanView(1L, GoalType.HOUSE, null, 30_000_000L, 36, 0L, 300_000L, new BigDecimal("2.84"),
                savingsRatioPct, "2026-11", "AVG", 1L, false, Instant.parse("2026-11-20T00:00:00Z"));
    }

    private static EtfSeries kospi200() {
        EtfProduct product = new EtfProduct("069500", "코스피200 추종 ETF", BigDecimal.ZERO);
        DataVersionInfo version = new DataVersionInfo(KOSPI_VERSION_ID, SP500.version().dataKind(), "test-kospi",
                SP500.version().baseDate(), "테스트용 가짜 데이터", product.code());
        return new EtfSeries(version, product, SP500.monthlyCloses());
    }

    @Test
    @DisplayName("종목이 다르면 current·changed가 각자 종목의 데이터 버전·세율을 쓰고, 기록할 changed 종목 버전이 남는다")
    void keepsEachSideProduct() {
        CompareConditionsUseCase.ComputedComparison computed = useCase.computeComparison(plan(), NO_CHANGE, PARAMS,
                SP500, kospi200(), null);
        CalcComparison comparison = computed.comparison();

        assertThat(comparison.current().dataVersions().sp500VersionId()).isEqualTo(SP500.version().id());
        assertThat(comparison.changed().dataVersions().sp500VersionId()).isEqualTo(KOSPI_VERSION_ID);
        assertThat(comparison.current().assumptions().etfProductCode()).isEqualTo("SP500");
        assertThat(comparison.changed().assumptions().etfProductCode()).isEqualTo("069500");
        assertThat(comparison.changed().assumptions().etfGainTaxRatePct()).isEqualByComparingTo("0");
        // 비과세라 같은 조건에서도 changed가 크다(ETF 차익 세금만큼)
        assertThat(comparison.changed().findBaselineResult().orElseThrow().afterTaxAsset())
                .isGreaterThan(comparison.current().findBaselineResult().orElseThrow().afterTaxAsset());
        assertThat(computed.versions().sp500VersionId()).isEqualTo(SP500.version().id());
        assertThat(computed.changedEtfVersionId()).isEqualTo(KOSPI_VERSION_ID);
    }

    @Test
    @DisplayName("같은 종목이면 두 쪽 데이터 버전이 같고 changed 종목 버전은 따로 남기지 않는다(지금 모든 비교)")
    void recordsNothingExtraForSameProduct() {
        CompareConditionsUseCase.ComputedComparison computed = useCase.computeComparison(plan(),
                new ConditionOverride(500_000L, null, null, null, null, null), PARAMS, SP500, SP500, null);

        assertThat(computed.comparison().changed().dataVersions())
                .isEqualTo(computed.comparison().current().dataVersions());
        assertThat(computed.changedEtfVersionId()).isNull();
    }

    @Test
    @DisplayName("changed가 ETF 0%면(비중 100) 지금처럼 current의 종목 버전을 두 쪽에 함께 싣고 따로 남기지 않는다")
    void keepsUnionWhenOneSideHasNoEtf() {
        CompareConditionsUseCase.ComputedComparison computed = useCase.computeComparison(plan(),
                new ConditionOverride(null, null, 100, null, null, null), PARAMS, SP500, kospi200(), null);

        assertThat(computed.comparison().changed().dataVersions().sp500VersionId()).isEqualTo(SP500.version().id());
        assertThat(computed.changedEtfVersionId()).isNull();
    }

    @Test
    @DisplayName("current가 ETF 0%이고 changed만 다른 종목을 쓰면 그 종목 버전을 두 쪽에 싣고 따로 남기지 않는다")
    void recordsChangedProductAsUnionWhenCurrentHasNoEtf() {
        CompareConditionsUseCase.ComputedComparison computed = useCase.computeComparison(plan(100),
                new ConditionOverride(null, null, 50, null, null, null), PARAMS, SP500, kospi200(), null);

        assertThat(computed.versions().sp500VersionId()).isEqualTo(KOSPI_VERSION_ID);
        assertThat(computed.comparison().changed().dataVersions().sp500VersionId()).isEqualTo(KOSPI_VERSION_ID);
        assertThat(computed.comparison().changed().assumptions().etfProductCode()).isEqualTo("069500");
        assertThat(computed.changedEtfVersionId()).isNull();
    }
}
