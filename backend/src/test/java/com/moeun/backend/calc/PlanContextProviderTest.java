package com.moeun.backend.calc;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.calc.application.CompareConditionsUseCase;
import com.moeun.backend.calc.application.CurrentPlanResult;
import com.moeun.backend.calc.application.CurrentPlanResultService;
import com.moeun.backend.calc.application.RecalculatingPlanContextProvider;
import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.PlanSaveResult;
import com.moeun.backend.plan.PlanSaveService;
import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.DataVersionInfo;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.application.ReferenceDataLoader;
import com.moeun.backend.refdata.application.VersionSpec;

/**
 * planContext 공개 API(#78, 계약 C1a-10, C1a-11). 실제 PostgreSQL에서 확인한다.
 * <p>S&P500은 레포의 테스트용 가짜 121개월을 쓴다(실제 데이터는 레포에 없음).
 */
// TestcontainersConfig(config)와 회원 테이블(auth)이 필요하고, calc가 쓰는 plan·refdata 빈을 함께 띄운다
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES, extraIncludes = {"config", "auth"})
@Import(TestcontainersConfig.class)
@TestPropertySource(properties = "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv")
class PlanContextProviderTest {

    private static final Duration EVENT_WAIT = Duration.ofSeconds(20);

    @Autowired
    PlanContextProvider provider;

    @Autowired
    CurrentPlanResultService resultService;

    @Autowired
    CompareConditionsUseCase compareConditionsUseCase;

    @Autowired
    PlanSaveService planSaveService;

    @Autowired
    ReferenceDataLoader loader;

    @Autowired
    JdbcClient jdbc;

    long memberId;

    @BeforeEach
    void createMember() {
        awaitEventsCompleted();
        jdbc.sql("DELETE FROM calc_run").update();
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        jdbc.sql("DELETE FROM member").update();
        memberId = createMember("context-me");
    }

    private long createMember(String loginId) {
        return jdbc.sql("INSERT INTO member (login_id, password_hash) VALUES (?, 'x') RETURNING id")
                .param(loginId).query(Long.class).single();
    }

    /** 계약 A-1과 같은 계획: 목표 2,000만원, 36개월, 월 30만원, 금리 비움(기본금리), 비중 5:5. */
    private PlanSaveResult savePlan(long member, long heldAssets, long monthlySavingAmount, Long expectedRevision) {
        PlanSaveResult saved = planSaveService.save(member, valid().goalAmount(20_000_000L).targetPeriodMonths(36)
                .heldAssets(heldAssets).monthlySavingAmount(monthlySavingAmount).build(), expectedRevision);
        awaitEventsCompleted();
        return saved;
    }

    private void awaitEventsCompleted() {
        await().atMost(EVENT_WAIT).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }

    private long countPlanRuns() {
        return jdbc.sql("SELECT count(*) FROM calc_run WHERE run_type = 'PLAN'").query(Long.class).single();
    }

    private long latestPlanRunId(long planVersionId) {
        return jdbc.sql("SELECT id FROM calc_run WHERE plan_version_id = ? AND run_type = 'PLAN' "
                + "ORDER BY created_at DESC, id DESC LIMIT 1").param(planVersionId).query(Long.class).single();
    }

    private long createChatRun() {
        return compareConditionsUseCase
                .compare(memberId, new ConditionOverride(500_000L, null, null, null, null, null))
                .calcRunId();
    }

    @Test
    @DisplayName("내 PLAN 기록으로 만들면 그 기록의 계획 버전·데이터 버전 기준이고, 계획이 그 뒤 바뀌어도 그때 결과와 같다")
    void buildsContextFromRecordedRunEvenAfterPlanChanged() {
        PlanSaveResult first = savePlan(memberId, 5_000_000L, 300_000L, null);
        CurrentPlanResult then = resultService.findForCurrentPlan(memberId).orElseThrow();
        savePlan(memberId, 5_000_000L, 500_000L, first.revision());

        PlanContextView view = provider.findForCalcRun(memberId, then.calcRunId(), Scenario.AVG).orElseThrow();

        assertThat(view.calcRunId()).isEqualTo(then.calcRunId());
        assertThat(view.conditions().monthlySavingAmount()).isEqualTo(300_000L);
        assertThat(view.conditions()).isEqualTo(ConditionsView.from(then.input(), then.plan().etfProductCode()));
        assertThat(view.result()).isEqualTo(CalcResultView.from(then.result()));
        assertThat(view.result().baselineScenario()).isEqualTo(Scenario.AVG);
        assertThat(view.display()).isEqualTo(then.display());
    }

    @Test
    @DisplayName("EXPLAIN의 기준 시나리오는 인자로 받은 값이다(그 뒤 계획의 기준 시나리오가 바뀌어도 인자 값)")
    void usesGivenBaselineScenario() {
        PlanSaveResult saved = savePlan(memberId, 5_000_000L, 300_000L, null);
        long calcRunId = latestPlanRunId(saved.planVersionId());
        jdbc.sql("UPDATE plan SET baseline_scenario = 'BAD' WHERE member_id = ?").param(memberId).update();

        PlanContextView view = provider.findForCalcRun(memberId, calcRunId, Scenario.GOOD).orElseThrow();

        assertThat(view.result().baselineScenario()).isEqualTo(Scenario.GOOD);
    }

    @Test
    @DisplayName("현재 계획으로 만들면 결과 화면 서비스의 기록·결과·display와 같고 기준 시나리오는 계획의 값이다")
    void buildsContextFromCurrentPlan() {
        savePlan(memberId, 5_000_000L, 300_000L, null);
        jdbc.sql("UPDATE plan SET baseline_scenario = 'BAD' WHERE member_id = ?").param(memberId).update();
        CurrentPlanResult current = resultService.findForCurrentPlan(memberId).orElseThrow();

        PlanContextView view = provider.findForCurrentPlan(memberId).orElseThrow();

        assertThat(view.calcRunId()).isEqualTo(current.calcRunId());
        assertThat(view.result()).isEqualTo(CalcResultView.from(current.result()));
        assertThat(view.result().baselineScenario()).isEqualTo(Scenario.BAD);
        assertThat(view.display()).isEqualTo(current.display());
    }

    @Test
    @DisplayName("질문할 때마다 불러도 같은 계획 버전이면 calc_run(PLAN)이 더 쌓이지 않는다")
    void doesNotAddRunsPerQuestion() {
        savePlan(memberId, 5_000_000L, 300_000L, null);
        long before = countPlanRuns();

        long firstId = provider.findForCurrentPlan(memberId).orElseThrow().calcRunId();
        long secondId = provider.findForCurrentPlan(memberId).orElseThrow().calcRunId();

        assertThat(secondId).isEqualTo(firstId);
        assertThat(countPlanRuns()).isEqualTo(before);
    }

    @Test
    @DisplayName("현재 계획의 기록이 없으면 이때 기록한다(읽기 전용 트랜잭션이면 INSERT가 거절되어 실패한다)")
    void recordsMissingRunForCurrentPlan() throws Exception {
        PlanSaveResult saved = savePlan(memberId, 5_000_000L, 300_000L, null);
        jdbc.sql("DELETE FROM calc_run").update();

        PlanContextView view = provider.findForCurrentPlan(memberId).orElseThrow();

        assertThat(view.calcRunId()).isEqualTo(latestPlanRunId(saved.planVersionId()));
        assertThat(countPlanRuns()).isEqualTo(1);
        Method method = RecalculatingPlanContextProvider.class.getMethod("findForCurrentPlan", long.class);
        assertThat(method.getAnnotation(Transactional.class).readOnly()).isFalse();
    }

    @Test
    @DisplayName("현재 계획 경로를 동시에 두 번 불러도 calc_run(PLAN)은 1행이고 calcRunId가 같다")
    void keepsSingleRunUnderConcurrentQuestions() throws Exception {
        PlanSaveResult saved = savePlan(memberId, 5_000_000L, 300_000L, null);
        jdbc.sql("DELETE FROM calc_run").update();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Long> question = () -> {
            start.await();
            return provider.findForCurrentPlan(memberId).orElseThrow().calcRunId();
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = executor.submit(question);
            Future<Long> second = executor.submit(question);
            start.countDown();

            assertThat(first.get()).isEqualTo(second.get());
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM calc_run WHERE plan_version_id = ? AND run_type = 'PLAN'")
                .param(saved.planVersionId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("저장된 계획이 없으면 현재 계획 컨텍스트는 빈 값이다")
    void isEmptyWithoutPlan() {
        assertThat(provider.findForCurrentPlan(memberId)).isEmpty();
    }

    @Test
    @DisplayName("내 PLAN 기록이면 calcRunId·계획 버전과 조회 시점의 계획 기준 시나리오를 재계산 없이 돌려준다")
    void findsOwnPlanRun() {
        PlanSaveResult saved = savePlan(memberId, 5_000_000L, 300_000L, null);
        long calcRunId = latestPlanRunId(saved.planVersionId());
        jdbc.sql("UPDATE plan SET baseline_scenario = 'GOOD' WHERE member_id = ?").param(memberId).update();

        assertThat(provider.findPlanRun(memberId, calcRunId))
                .contains(new PlanRunRef(calcRunId, saved.planVersionId(), Scenario.GOOD));
    }

    @Test
    @DisplayName("없는 기록·남의 기록·CHAT 기록이면 findPlanRun·findForCalcRun 모두 빈 값이다")
    void hidesMissingForeignAndChatRuns() {
        savePlan(memberId, 5_000_000L, 300_000L, null);
        long chatRunId = createChatRun();
        long otherMember = createMember("context-other");
        PlanSaveResult othersPlan = savePlan(otherMember, 5_000_000L, 300_000L, null);
        long othersRunId = latestPlanRunId(othersPlan.planVersionId());

        for (long calcRunId : new long[] {999_999L, othersRunId, chatRunId}) {
            assertThat(provider.findPlanRun(memberId, calcRunId)).isEmpty();
            assertThat(provider.findForCalcRun(memberId, calcRunId, Scenario.AVG)).isEmpty();
        }
    }

    @Test
    @DisplayName("이미 목표를 넘은 계획이어도 scenarios가 있고 display에 목표 금액·보유 자산이 있다")
    void buildsExceededContext() {
        savePlan(memberId, 25_000_000L, 300_000L, null);

        PlanContextView view = provider.findForCurrentPlan(memberId).orElseThrow();

        assertThat(view.result().status()).isEqualTo(CalcStatus.ALREADY_EXCEEDED);
        assertThat(view.result().scenarios()).isNotEmpty();
        assertThat(view.display()).containsKeys("goalAmount", "conditions.heldAssets");
    }

    @Test
    @DisplayName("기록의 기준 데이터를 꺼낼 수 없으면 빈 값이 아니라 예외가 올라온다")
    void propagatesReferenceDataErrors() {
        PlanSaveResult saved = savePlan(memberId, 5_000_000L, 300_000L, null);
        long calcRunId = latestPlanRunId(saved.planVersionId());
        // 금리 자리에 계산 파라미터 버전을 넣어 데이터 종류가 맞지 않게 만든다
        jdbc.sql("UPDATE calc_run SET savings_rate_version_id = calc_param_version_id WHERE id = ?")
                .param(calcRunId).update();

        assertThatThrownBy(() -> provider.findForCalcRun(memberId, calcRunId, Scenario.AVG))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ETF 0%·직접 넣은 금리 계획의 기록이면 S&P500·평균금리 없이 다시 계산하고 AVG 하나·null 값으로 만든다")
    void rebuildsEtfZeroPlanWithEnteredRate() {
        PlanSaveResult saved = planSaveService.save(memberId, valid().goalAmount(20_000_000L).targetPeriodMonths(36)
                .heldAssets(5_000_000L).monthlySavingAmount(300_000L).savingsRateInputPct("3.00").build(), null);
        awaitEventsCompleted();
        // 비중은 입력 화면에서 받지 않아(대안·대화 적용으로만 바뀜) 테스트에서 버전 행을 직접 바꾼다
        jdbc.sql("UPDATE plan_version SET savings_ratio_pct = 100 WHERE id = ?").param(saved.planVersionId()).update();
        jdbc.sql("DELETE FROM calc_run").update();
        long calcRunId = provider.findForCurrentPlan(memberId).orElseThrow().calcRunId();

        PlanContextView view = provider.findForCalcRun(memberId, calcRunId, Scenario.AVG).orElseThrow();

        assertThat(jdbc.sql("SELECT sp500_version_id IS NULL AND savings_rate_version_id IS NULL FROM calc_run "
                + "WHERE id = ?").param(calcRunId).query(Boolean.class).single()).isTrue();
        assertThat(view.result().scenarios()).extracting(ScenarioResultView::scenario).containsExactly(Scenario.AVG);
        assertThat(view.result().scenarios().get(0).annualReturnPct()).isNull();
        assertThat(view.result().assumptions().scenarioMethod()).isNull();
        assertThat(view.result().assumptions().sp500BaseDate()).isNull();
        assertThat(view.result().assumptions().isDefaultSavingsRate()).isFalse();
        assertThat(view.result().dataVersions().sp500VersionId()).isNull();
        assertThat(view.result().dataVersions().savingsRateVersionId()).isNull();
        assertThat(view.conditions().savingsRateInputPct()).isEqualByComparingTo("3.00");
        assertThat(view.display().keySet()).noneMatch(key -> key.startsWith("GOOD.") || key.startsWith("BAD."));
    }

    @Test
    @DisplayName("그 뒤 활성 금리 버전이 바뀌어도 PLAN 기록이 남긴 금리 버전으로 다시 계산한다")
    void usesRecordedDataVersionsAfterActiveVersionChanged() {
        savePlan(memberId, 5_000_000L, 300_000L, null);
        CurrentPlanResult then = resultService.findForCurrentPlan(memberId).orElseThrow();
        long recordedRateVersion = then.result().dataVersions().savingsRateVersionId();
        DataVersionInfo newer = loader.load(DataKind.SAVINGS_RATE,
                new VersionSpec("test-later-plan", LocalDate.of(2026, 11, 1), "테스트용 가짜 데이터"),
                new ByteArrayInputStream("base_month,avg_rate_pct\n2026-11,1.00\n".getBytes(StandardCharsets.UTF_8)))
                .orElseThrow();
        try {
            PlanContextView view = provider.findForCalcRun(memberId, then.calcRunId(), Scenario.AVG).orElseThrow();

            assertThat(view.result()).isEqualTo(CalcResultView.from(then.result()));
            assertThat(view.display()).isEqualTo(then.display());
        } finally {
            // 같은 컨텍스트를 쓰는 다른 테스트가 원래 활성 금리로 계산하도록 되돌린다
            jdbc.sql("UPDATE data_version SET is_active = false WHERE id = ?").param(newer.id()).update();
            jdbc.sql("UPDATE data_version SET is_active = true WHERE id = ?").param(recordedRateVersion).update();
        }
    }

    @Test
    @DisplayName("비과세 종목(069500) 가격 버전으로 남은 PLAN 기록을 다시 계산하면 그 버전의 종목·세율 0%로 계산한다(#150)")
    void rebuildsRecordWithItsEtfProductAndTaxRate() throws Exception {
        PlanSaveResult saved = savePlan(memberId, 5_000_000L, 300_000L, null);
        long calcRunId = latestPlanRunId(saved.planVersionId());
        PlanContextView sp500 = provider.findForCalcRun(memberId, calcRunId, Scenario.AVG).orElseThrow();
        // 같은 가짜 종가에 종목 정보만 비과세로 둔 가격 버전. 기록이 이 버전을 가리키게 바꾼다
        DataVersionInfo kospi;
        try (InputStream csv = new ClassPathResource(
                "refdata/test-sp500-2016-10-to-2026-10.csv").getInputStream()) {
            kospi = loader.loadEtf(new EtfProduct("069500", "코스피200 추종 ETF", BigDecimal.ZERO),
                    new VersionSpec("test-kospi", LocalDate.of(2026, 10, 31), "테스트용 가짜 데이터"), csv)
                    .orElseThrow();
        }
        jdbc.sql("UPDATE calc_run SET sp500_version_id = ? WHERE id = ?").param(kospi.id()).param(calcRunId).update();

        PlanContextView taxFree = provider.findForCalcRun(memberId, calcRunId, Scenario.AVG).orElseThrow();

        assertThat(taxFree.result().assumptions().etfProductCode()).isEqualTo("069500");
        assertThat(taxFree.result().assumptions().etfGainTaxRatePct()).isEqualByComparingTo("0");
        assertThat(taxFree.result().dataVersions().sp500VersionId()).isEqualTo(kospi.id());
        assertThat(taxFree.display()).containsEntry("assumptions.etfGainTaxRatePct",
                "0%(국내 주식형 ETF 매매차익 비과세)");
        assertThat(taxFree.result().scenarios().get(1).afterTaxAsset())
                .isGreaterThan(sp500.result().scenarios().get(1).afterTaxAsset());
        // 그 뒤 활성 S&P500으로 다시 기록하지 않았는지(기록은 그대로)
        assertThat(sp500.result().assumptions().etfProductCode()).isEqualTo("SP500");
    }

    /** ETF 0% 계획(적금 100%)을 저장하고, 계획 버전에 그 종목이 저장된 것처럼 만든다(준비 단계에서만 행을 직접 고친다). */
    private PlanSaveResult saveEtfZeroPlanWithProduct(String etfProductCode) {
        PlanSaveResult saved = planSaveService.save(memberId, valid().goalAmount(20_000_000L).targetPeriodMonths(36)
                .heldAssets(5_000_000L).monthlySavingAmount(300_000L).savingsRatioPct(100).build(), null);
        jdbc.sql("UPDATE plan_version SET etf_product_code = ? WHERE id = ?")
                .param(etfProductCode).param(saved.planVersionId()).update();
        awaitEventsCompleted();
        return saved;
    }

    @Test
    @DisplayName("ETF 0% 계획도 현재 계획 컨텍스트의 conditions에 계획에 저장된 종목 코드가 있다. 계산에 쓴 종목은 없다(#297)")
    void currentPlanContextCarriesPlanProductWithoutEtf() {
        saveEtfZeroPlanWithProduct("069500");

        PlanContextView view = provider.findForCurrentPlan(memberId).orElseThrow();

        assertThat(view.conditions().etfProductCode()).isEqualTo("069500");
        assertThat(view.result().assumptions().etfProductCode()).isNull();
    }

    @Test
    @DisplayName("ETF 0% 계획의 PLAN 기록으로 만들면 그 뒤 계획의 종목이 바뀌어도 그 기록의 계획 버전 종목 코드다(#297)")
    void recordedContextCarriesItsPlanVersionProduct() {
        PlanSaveResult first = saveEtfZeroPlanWithProduct("069500");
        long calcRunId = resultService.findForCurrentPlan(memberId).orElseThrow().calcRunId();
        PlanSaveResult second = savePlan(memberId, 5_000_000L, 300_000L, first.revision());
        jdbc.sql("UPDATE plan_version SET etf_product_code = 'SP500' WHERE id = ?")
                .param(second.planVersionId()).update();

        PlanContextView view = provider.findForCalcRun(memberId, calcRunId, Scenario.AVG).orElseThrow();

        assertThat(view.conditions().etfProductCode()).isEqualTo("069500");
        assertThat(view.result().assumptions().etfProductCode()).isNull();
    }

    @Test
    @DisplayName("ETF가 있는 계획은 현재·기록 두 경로 모두 conditions의 종목 코드와 계산에 쓴 종목 코드가 같다(#297)")
    void etfPlanProductMatchesCalculatedProduct() {
        savePlan(memberId, 5_000_000L, 300_000L, null);

        PlanContextView current = provider.findForCurrentPlan(memberId).orElseThrow();
        PlanContextView recorded = provider.findForCalcRun(memberId, current.calcRunId(), Scenario.AVG).orElseThrow();

        assertThat(current.conditions().etfProductCode()).isEqualTo("SP500")
                .isEqualTo(current.result().assumptions().etfProductCode());
        assertThat(recorded.conditions().etfProductCode()).isEqualTo("SP500")
                .isEqualTo(recorded.result().assumptions().etfProductCode());
    }
}
