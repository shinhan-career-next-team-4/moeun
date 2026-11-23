package com.moeun.backend.calc;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

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
import org.springframework.transaction.support.TransactionTemplate;

import com.moeun.backend.calc.application.CalcRunRecorder;
import com.moeun.backend.calc.application.CompareConditionsUseCase;
import com.moeun.backend.calc.application.CompareConditionsUseCase.ComparisonResult;
import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.ConditionOverride;
import com.moeun.backend.plan.PlanSaveResult;
import com.moeun.backend.plan.PlanSaveService;
import com.moeun.backend.plan.PlanView;
import com.moeun.backend.refdata.CalcParams;
import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.DataVersionInfo;
import com.moeun.backend.refdata.EtfProduct;
import com.moeun.backend.refdata.EtfSeries;
import com.moeun.backend.refdata.ReferenceDataService;
import com.moeun.backend.refdata.SavingsAvgRateData;
import com.moeun.backend.refdata.application.ReferenceDataLoader;
import com.moeun.backend.refdata.application.VersionSpec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 대화 비교 기록 재계산 공개 API(#78, 계약 H-8b, C12b-6). 실제 PostgreSQL에서 확인한다.
 * <p>기대값은 같은 회원·조건으로 {@code /internal/calc}가 쓰는 비교 유스케이스를 직접 부른 결과다.
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES, extraIncludes = {"config", "auth"})
@Import(TestcontainersConfig.class)
@TestPropertySource(properties = "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv")
class ChatComparisonQueryTest {

    private static final Duration EVENT_WAIT = Duration.ofSeconds(20);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String TAX_FREE_LABEL = "test-kospi-chat";

    @Autowired
    ChatComparisonQuery query;

    @Autowired
    CompareConditionsUseCase compareConditionsUseCase;

    @Autowired
    PlanSaveService planSaveService;

    @Autowired
    ReferenceDataLoader loader;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CalcRunRecorder calcRunRecorder;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ReferenceDataService referenceDataService;

    long memberId;

    @BeforeEach
    void createMember() {
        awaitEventsCompleted();
        jdbc.sql("DELETE FROM calc_run").update();
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        jdbc.sql("DELETE FROM member").update();
        memberId = createMember("chat-compare-me");
    }

    private long createMember(String loginId) {
        return jdbc.sql("INSERT INTO member (login_id, password_hash) VALUES (?, 'x') RETURNING id")
                .param(loginId).query(Long.class).single();
    }

    /** 계약 A-1과 같은 계획: 목표 2,000만원, 36개월, 보유 500만원, 월 30만원, 금리 비움(기본금리), 비중 5:5. */
    private PlanSaveResult savePlan(long member, Long expectedRevision, long monthlySavingAmount) {
        PlanSaveResult saved = planSaveService.save(member, valid().goalAmount(20_000_000L).targetPeriodMonths(36)
                .heldAssets(5_000_000L).monthlySavingAmount(monthlySavingAmount).build(), expectedRevision);
        awaitEventsCompleted();
        return saved;
    }

    private void awaitEventsCompleted() {
        await().atMost(EVENT_WAIT).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }

    private static Map<String, JsonNode> changes(String key, JsonNode value) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private long latestPlanRunId(long planVersionId) {
        return jdbc.sql("SELECT id FROM calc_run WHERE plan_version_id = ? AND run_type = 'PLAN' "
                + "ORDER BY created_at DESC, id DESC LIMIT 1").param(planVersionId).query(Long.class).single();
    }

    @Test
    @DisplayName("내 CHAT 기록을 다시 계산하면 /internal/calc 때의 current·changed·diff·display와 같다")
    void recomputesSameComparisonAsInternalCalc() {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        ComparisonResult then = compareConditionsUseCase.compare(memberId,
                new ConditionOverride(500_000L, null, null, null, null, null));

        ChatComparisonView view = query.recompute(memberId, then.calcRunId(),
                changes("monthlySavingAmount", NODES.numberNode(500_000))).orElseThrow();

        assertThat(view.basePlanVersionId()).isEqualTo(saved.planVersionId());
        assertThat(view.comparison()).isEqualTo(then.comparison());
        assertThat(view.display()).isEqualTo(then.display());
    }

    @Test
    @DisplayName("계획이 그 뒤 바뀌어도 그 CHAT 기록의 계획 버전을 기준으로 다시 계산한다")
    void usesRecordedPlanVersionAfterPlanChanged() {
        PlanSaveResult first = savePlan(memberId, null, 300_000L);
        ComparisonResult then = compareConditionsUseCase.compare(memberId,
                new ConditionOverride(null, 24, null, null, null, null));
        savePlan(memberId, first.revision(), 400_000L);

        ChatComparisonView view = query.recompute(memberId, then.calcRunId(),
                changes("targetPeriodMonths", NODES.numberNode(24))).orElseThrow();

        assertThat(view.basePlanVersionId()).isEqualTo(first.planVersionId());
        assertThat(view.comparison().conditions().current().monthlySavingAmount()).isEqualTo(300_000L);
        assertThat(view.comparison()).isEqualTo(then.comparison());
    }

    @Test
    @DisplayName("그 뒤 활성 금리 버전이 바뀌어도 기록이 남긴 금리 버전으로 계산한다")
    void usesRecordedDataVersionsAfterActiveVersionChanged() {
        savePlan(memberId, null, 300_000L);
        ComparisonResult then = compareConditionsUseCase.compare(memberId,
                new ConditionOverride(500_000L, null, null, null, null, null));
        long recordedRateVersion = then.comparison().current().dataVersions().savingsRateVersionId();
        DataVersionInfo newer = loader.load(DataKind.SAVINGS_RATE,
                new VersionSpec("test-later", LocalDate.of(2026, 11, 1), "테스트용 가짜 데이터"),
                new ByteArrayInputStream("base_month,avg_rate_pct\n2026-11,1.00\n".getBytes(StandardCharsets.UTF_8)))
                .orElseThrow();
        try {
            ChatComparisonView view = query.recompute(memberId, then.calcRunId(),
                    changes("monthlySavingAmount", NODES.numberNode(500_000))).orElseThrow();

            assertThat(view.comparison()).isEqualTo(then.comparison());
            assertThat(view.comparison().current().dataVersions().savingsRateVersionId())
                    .isEqualTo(recordedRateVersion);
        } finally {
            // 같은 컨텍스트를 쓰는 다른 테스트가 원래 활성 금리로 계산하도록 되돌린다
            jdbc.sql("UPDATE data_version SET is_active = false WHERE id = ?").param(newer.id()).update();
            jdbc.sql("UPDATE data_version SET is_active = true WHERE id = ?").param(recordedRateVersion).update();
        }
    }

    @Test
    @DisplayName("범위 밖이지만 타입이 맞는 값(월 12,000,000원)은 예외 없이 그대로 계산한다")
    void recomputesOutOfRangeValueWithoutValidation() {
        savePlan(memberId, null, 300_000L);
        ComparisonResult then = compareConditionsUseCase.compare(memberId,
                new ConditionOverride(500_000L, null, null, null, null, null));

        ChatComparisonView view = query.recompute(memberId, then.calcRunId(),
                changes("monthlySavingAmount", NODES.numberNode(12_000_000L))).orElseThrow();

        assertThat(view.comparison().conditions().changed().monthlySavingAmount()).isEqualTo(12_000_000L);
    }

    @Test
    @DisplayName("타입을 바꿀 수 없는 값·허용 5개 밖의 키·정수 자리의 소수면 예외다")
    void rejectsUnconvertibleChanges() {
        savePlan(memberId, null, 300_000L);
        long calcRunId = compareConditionsUseCase.compare(memberId,
                new ConditionOverride(500_000L, null, null, null, null, null)).calcRunId();

        assertThatThrownBy(() -> query.recompute(memberId, calcRunId,
                changes("monthlySavingAmount", NODES.stringNode("abc"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.recompute(memberId, calcRunId,
                changes("heldAssets", NODES.numberNode(10_000_000L))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.recompute(memberId, calcRunId,
                changes("targetPeriodMonths", NODES.numberNode(new java.math.BigDecimal("24.5")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("내 CHAT 기록이면 기준 계획 버전 id를 재계산 없이 돌려준다")
    void findsChatRunPlanVersion() {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        long calcRunId = compareConditionsUseCase.compare(memberId,
                new ConditionOverride(500_000L, null, null, null, null, null)).calcRunId();

        assertThat(query.findChatRunPlanVersion(memberId, calcRunId)).contains(saved.planVersionId());
    }

    @Test
    @DisplayName("없는 기록·남의 CHAT 기록·PLAN 기록이면 두 메서드 모두 빈 값이다")
    void hidesMissingForeignAndPlanRuns() {
        PlanSaveResult mine = savePlan(memberId, null, 300_000L);
        long planRunId = latestPlanRunId(mine.planVersionId());
        long otherMember = createMember("chat-compare-other");
        savePlan(otherMember, null, 300_000L);
        long othersChatRunId = compareConditionsUseCase.compare(otherMember,
                new ConditionOverride(500_000L, null, null, null, null, null)).calcRunId();
        Map<String, JsonNode> changes = changes("monthlySavingAmount", NODES.numberNode(500_000));

        for (long calcRunId : new long[] {999_999L, othersChatRunId, planRunId}) {
            assertThat(query.findChatRunPlanVersion(memberId, calcRunId)).isEmpty();
            assertThat(query.recompute(memberId, calcRunId, changes)).isEmpty();
        }
    }

    /**
     * 같은 가짜 종가에 종목 정보만 비과세로 둔 069500 가격 버전의 id. 이 클래스의 여러 테스트와, 같은 컨텍스트(같은 DB)를
     * 쓰는 다른 테스트 클래스(PlanContextProviderTest의 "test-kospi")가 함께 부르므로 라벨을 따로 두고, 이미 있으면 그
     * 버전을 쓴다.
     */
    private long loadTaxFreeProduct() throws Exception {
        try (InputStream csv = new ClassPathResource("refdata/test-sp500-2016-10-to-2026-10.csv").getInputStream()) {
            return loader.loadEtf(new EtfProduct("069500", "코스피200 추종 ETF", BigDecimal.ZERO),
                    new VersionSpec(TAX_FREE_LABEL, LocalDate.of(2026, 10, 31), "테스트용 가짜 데이터"), csv)
                    .map(DataVersionInfo::id)
                    .orElseGet(() -> jdbc.sql("SELECT id FROM data_version WHERE data_kind = 'ETF_PRICE' "
                            + "AND product_code = '069500' AND version_label = ?").param(TAX_FREE_LABEL)
                            .query(Long.class).single());
        }
    }

    @Test
    @DisplayName("계획의 종목이 069500이면 대화 비교도 그 종목으로 계산하고 calc_run(CHAT)에 그 가격 버전을 남긴다(#154)")
    void comparesWithEtfProductOfPlan() throws Exception {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        long kospi = loadTaxFreeProduct();
        // 준비 단계에서만 행을 직접 고친다(앱 코드는 plan_version을 INSERT만 한다)
        jdbc.sql("UPDATE plan_version SET etf_product_code = '069500' WHERE id = ?").param(saved.planVersionId())
                .update();

        ComparisonResult result = compareConditionsUseCase.compare(memberId,
                new ConditionOverride(500_000L, null, null, null, null, null));

        assertThat(jdbc.sql("SELECT sp500_version_id FROM calc_run WHERE id = ?").param(result.calcRunId())
                .query(Long.class).single()).isEqualTo(kospi);
    }

    @Test
    @DisplayName("같은 종목 비교는 calc_run(CHAT)에 changed 종목 버전을 따로 남기지 않는다(#157)")
    void leavesChangedEtfVersionEmptyForSameProduct() {
        savePlan(memberId, null, 300_000L);
        long calcRunId = compareConditionsUseCase.compare(memberId,
                new ConditionOverride(500_000L, null, null, null, null, null)).calcRunId();

        assertThat(jdbc.sql("SELECT changed_etf_version_id IS NULL FROM calc_run WHERE id = ?").param(calcRunId)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("changed 종목 버전이 남은 CHAT 기록을 다시 계산하면 changed만 그 종목·세율로 계산한다(#157)")
    void recomputesChangedSideWithItsRecordedProduct() throws Exception {
        savePlan(memberId, null, 300_000L);
        ComparisonResult then = compareConditionsUseCase.compare(memberId,
                new ConditionOverride(500_000L, null, null, null, null, null));
        long kospi = loadTaxFreeProduct();
        // 대화로 종목을 바꾸는 길이 아직 없어(D-040 대기) 기록을 직접 바꿔 "changed는 비과세 종목" 기록을 만든다
        jdbc.sql("UPDATE calc_run SET changed_etf_version_id = ? WHERE id = ?").param(kospi)
                .param(then.calcRunId()).update();

        ChatComparisonView view = query.recompute(memberId, then.calcRunId(),
                changes("monthlySavingAmount", NODES.numberNode(500_000))).orElseThrow();

        assertThat(view.comparison().current()).isEqualTo(then.comparison().current());
        assertThat(view.comparison().changed().assumptions().etfProductCode()).isEqualTo("069500");
        assertThat(view.comparison().changed().dataVersions().sp500VersionId()).isEqualTo(kospi);
        assertThat(view.comparison().changed().findBaselineResult().orElseThrow().afterTaxAsset())
                .isGreaterThan(then.comparison().changed().findBaselineResult().orElseThrow().afterTaxAsset());
    }

    @Test
    @DisplayName("두 종목 비교를 기록하면 changed 종목 버전이 저장되고, 그 기록만으로 다시 계산한 결과가 그때와 같다(#157)")
    void recordsTwoProductComparisonAndRecomputesSame() throws Exception {
        savePlan(memberId, null, 300_000L);
        long kospi = loadTaxFreeProduct();
        PlanView plan = planSaveService.findCurrent(memberId).orElseThrow();
        CalcParams params = referenceDataService.findActiveCalcParams().orElseThrow();
        EtfSeries sp500 = referenceDataService.findActiveEtfSeries("SP500").orElseThrow();
        SavingsAvgRateData defaultRate = referenceDataService.findActiveSavingsAvgRate().orElseThrow();
        ConditionOverride override = new ConditionOverride(500_000L, null, null, null, null, null);

        // 대화 조건에 종목이 생기기 전이라(D-040 대기) /internal/calc와 같은 계산·기록 단계를 직접 부른다
        CompareConditionsUseCase.ComputedComparison then = compareConditionsUseCase.computeComparison(plan, override,
                params, sp500, referenceDataService.requireEtfSeries(kospi), defaultRate);
        long calcRunId = transactions.execute(status -> calcRunRecorder.recordChatRun(plan.currentVersionId(),
                then.versions(), then.changedEtfVersionId()));

        assertThat(jdbc.sql("SELECT sp500_version_id, changed_etf_version_id FROM calc_run WHERE id = ?")
                .param(calcRunId).query((row, number) -> new long[] {row.getLong(1), row.getLong(2)}).single())
                .containsExactly(sp500.version().id(), kospi);
        ChatComparisonView view = query.recompute(memberId, calcRunId,
                changes("monthlySavingAmount", NODES.numberNode(500_000))).orElseThrow();
        assertThat(view.comparison()).isEqualTo(then.comparison());
        assertThat(view.display()).isEqualTo(then.display());
    }

    @Test
    @DisplayName("기록할 때 changed 종목 버전의 데이터 종류를 확인한다(금리 버전 id를 넣으면 막힘)")
    void rejectsWrongKindForChangedEtfVersion() {
        PlanSaveResult saved = savePlan(memberId, null, 300_000L);
        ComparisonResult then = compareConditionsUseCase.compare(memberId,
                new ConditionOverride(500_000L, null, null, null, null, null));
        long rateVersionId = then.comparison().current().dataVersions().savingsRateVersionId() == null
                ? jdbc.sql("SELECT id FROM data_version WHERE data_kind = 'SAVINGS_RATE'").query(Long.class).single()
                : then.comparison().current().dataVersions().savingsRateVersionId();

        assertThatThrownBy(() -> transactions.execute(status -> calcRunRecorder.recordChatRun(saved.planVersionId(),
                then.comparison().current().dataVersions(), rateVersionId)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
