package com.moeun.backend.calc;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.moeun.backend.calc.application.CalcEngine;
import com.moeun.backend.calc.application.CalcRunRecorder;
import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.PlanChanged;
import com.moeun.backend.plan.PlanSaveResult;
import com.moeun.backend.plan.PlanSaveService;

/**
 * calc_run(PLAN) 기록과 현재 계획 결과 조회(#29, 계약 G-18, C12a-6-3, C12a-6-6). 실제 PostgreSQL에서 확인한다.
 * <p>S&P500은 레포의 테스트용 가짜 121개월을 쓴다(실제 데이터는 레포에 없음).
 */
// TestcontainersConfig(config)와 회원 테이블(auth)이 필요하고, calc가 쓰는 plan·refdata 빈을 함께 띄운다
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES, extraIncludes = {"config", "auth"})
@Import(TestcontainersConfig.class)
@TestPropertySource(properties = "MOEUN_SP500_FILE=classpath:refdata/test-sp500-2016-10-to-2026-10.csv")
class CurrentPlanResultServiceTest {

    private static final Duration EVENT_WAIT = Duration.ofSeconds(20);

    @Autowired
    CurrentPlanResultService resultService;

    @Autowired
    CalcRunRecorder recorder;

    @Autowired
    PlanSaveService planSaveService;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ApplicationEventPublisher events;

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
        memberId = jdbc.sql("INSERT INTO member (login_id, password_hash) VALUES ('calc-me', 'x') RETURNING id")
                .query(Long.class).single();
    }

    private PlanSaveResult savePlan() {
        PlanSaveResult saved = planSaveService.save(memberId, valid().build(), null);
        awaitEventsCompleted();
        return saved;
    }

    private void awaitEventsCompleted() {
        await().atMost(EVENT_WAIT).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }

    private long countPlanRuns(long planVersionId) {
        return jdbc.sql("SELECT count(*) FROM calc_run WHERE plan_version_id = ? AND run_type = 'PLAN'")
                .param(planVersionId).query(Long.class).single();
    }

    private CurrentPlanResult findInTransaction() {
        return transactions.execute(status -> resultService.findForCurrentPlan(memberId).orElseThrow());
    }

    @Test
    @DisplayName("계획을 저장하면 PlanChanged 처리로 calc_run(PLAN) 1행이 기록되고 버전 3종이 채워진다")
    void recordsPlanRunOnPlanChanged() {
        PlanSaveResult saved = savePlan();

        assertThat(countPlanRuns(saved.planVersionId())).isEqualTo(1);
        var row = jdbc.sql("SELECT savings_rate_version_id, sp500_version_id, calc_param_version_id, engine_version "
                + "FROM calc_run WHERE plan_version_id = ?").param(saved.planVersionId()).query().singleRow();
        // 기본 입력은 금리를 비우고(기본금리) 비중 5:5(ETF 포함)라서 세 버전이 모두 채워진다
        assertThat(row.get("savings_rate_version_id")).isNotNull();
        assertThat(row.get("sp500_version_id")).isNotNull();
        assertThat(row.get("calc_param_version_id")).isNotNull();
        assertThat(row.get("engine_version")).isEqualTo(CalcEngine.ENGINE_VERSION);
    }

    @Test
    @DisplayName("같은 PlanChanged가 두 번 와도 calc_run이 중복되지 않는다")
    void doesNotDuplicateOnRepeatedEvent() {
        PlanSaveResult saved = savePlan();
        PlanChanged event = new PlanChanged(memberId, "INPUT", null, saved.planVersionId(), saved.revision());

        // 실제 리스너(@ApplicationModuleListener)가 받도록 커밋되는 트랜잭션에서 같은 이벤트를 두 번 발행한다
        transactions.executeWithoutResult(status -> events.publishEvent(event));
        transactions.executeWithoutResult(status -> events.publishEvent(event));
        awaitEventsCompleted();

        assertThat(countPlanRuns(saved.planVersionId())).isEqualTo(1);
    }

    @Test
    @DisplayName("기록이 아직 없는 새 버전을 조회하면 다시 계산해 기록하고 결과를 돌려준다")
    void recordsWhenMissingOnQuery() {
        PlanSaveResult saved = savePlan();
        jdbc.sql("DELETE FROM calc_run").update();

        CurrentPlanResult result = findInTransaction();

        assertThat(countPlanRuns(saved.planVersionId())).isEqualTo(1);
        assertThat(result.calcRunId()).isEqualTo(jdbc.sql("SELECT id FROM calc_run WHERE plan_version_id = ?")
                .param(saved.planVersionId()).query(Long.class).single());
        assertThat(result.result().scenarios()).extracting(ScenarioResult::scenario)
                .containsExactly(Scenario.GOOD, Scenario.AVG, Scenario.BAD);
    }

    @Test
    @DisplayName("활성 버전과 같은 기록이 있으면 여러 번 조회해도 새 행 없이 같은 calcRunId다(G-18)")
    void returnsSameCalcRunIdForRepeatedQueries() {
        PlanSaveResult saved = savePlan();

        long first = findInTransaction().calcRunId();
        long second = findInTransaction().calcRunId();
        long third = findInTransaction().calcRunId();

        assertThat(List.of(second, third)).containsOnly(first);
        assertThat(countPlanRuns(saved.planVersionId())).isEqualTo(1);
    }

    @Test
    @DisplayName("반환값에 현재 계획 스냅샷·calcRunId·결과·display가 함께 들어 있다(C12a-6-3)")
    void returnsSnapshotWithResultAndDisplay() {
        PlanSaveResult saved = savePlan();

        CurrentPlanResult result = findInTransaction();

        assertThat(result.plan().currentVersionId()).isEqualTo(saved.planVersionId());
        assertThat(result.plan().revision()).isEqualTo(saved.revision());
        assertThat(result.input().calcStartMonth().toString()).isEqualTo(result.plan().calcStartMonth());
        assertThat(result.result().assumptions().isDefaultSavingsRate()).isTrue();
        assertThat(result.result().dataVersions().savingsRateVersionId()).isNotNull();
        assertThat(result.display()).containsKeys("goalAmount", "AVG.afterTaxAsset", "conditions.heldAssets",
                "assumptions.taxRatePct", "assumptions.savingsRateBaseMonth");
    }

    @Test
    @DisplayName("계획이 없으면 빈 결과다(P-4가 409 NO_PLAN으로 바꿈)")
    void returnsEmptyWithoutPlan() {
        Optional<CurrentPlanResult> result = transactions.execute(status -> resultService.findForCurrentPlan(memberId));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("리스너와 결과 조회가 같은 버전을 동시에 처리해도 calc_run은 1행이고 calcRunId가 같다")
    void keepsSingleRowUnderConcurrency() throws Exception {
        PlanSaveResult saved = savePlan();
        CurrentPlanResult computed = findInTransaction();
        jdbc.sql("DELETE FROM calc_run").update();
        // 두 트랜잭션이 기록 메서드 바로 앞에서 함께 출발하게 맞춘다. 잠금이 없으면 둘 다 "기록 없음"을 보고 2행이 생긴다
        CyclicBarrier bothReady = new CyclicBarrier(2);
        Callable<Long> record = () -> transactions.execute(status -> {
            awaitTogether(bothReady);
            return recorder.recordPlanRunIfAbsent(memberId, saved.planVersionId(),
                    computed.result().dataVersions());
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = executor.submit(record);
            Future<Long> second = executor.submit(record);

            assertThat(first.get()).isEqualTo(second.get());
        } finally {
            executor.shutdownNow();
        }
        assertThat(countPlanRuns(saved.planVersionId())).isEqualTo(1);
    }

    @Test
    @DisplayName("리스너 경로와 결과 조회 경로를 함께 실행해도 calc_run은 1행이고 calcRunId가 같다")
    void keepsSingleRowAcrossListenerAndQueryPaths() throws Exception {
        PlanSaveResult saved = savePlan();
        jdbc.sql("DELETE FROM calc_run").update();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Long> listenerPath = () -> {
            start.await();
            return transactions.execute(status -> resultService.recordForCurrentPlan(memberId).orElseThrow());
        };
        Callable<Long> queryPath = () -> {
            start.await();
            return transactions.execute(status -> resultService.findForCurrentPlan(memberId).orElseThrow()
                    .calcRunId());
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> fromListener = executor.submit(listenerPath);
            Future<Long> fromQuery = executor.submit(queryPath);
            start.countDown();

            assertThat(fromListener.get()).isEqualTo(fromQuery.get());
        } finally {
            executor.shutdownNow();
        }
        assertThat(countPlanRuns(saved.planVersionId())).isEqualTo(1);
    }

    private static void awaitTogether(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    @DisplayName("읽기 전용 트랜잭션 안에서 결과 조회를 부르면 기록 전에 이유가 담긴 예외로 막는다")
    void rejectsRecordingInReadOnlyTransaction() {
        savePlan();
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.execute(status -> resultService.findForCurrentPlan(memberId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    @DisplayName("기록 메서드는 트랜잭션 밖에서 부르면 실패한다(MANDATORY)")
    void rejectsRecordingOutsideTransaction() {
        PlanSaveResult saved = savePlan();
        CurrentPlanResult result = findInTransaction();

        assertThatThrownBy(() -> recorder.recordPlanRunIfAbsent(memberId, saved.planVersionId(),
                result.result().dataVersions()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("결과 조회 서비스는 읽기 전용 트랜잭션으로 열리지 않는다(기록 INSERT가 거절되므로)")
    void isNotReadOnlyTransactional() throws NoSuchMethodException {
        for (String name : List.of("findForCurrentPlan", "recordForCurrentPlan")) {
            Method method = CurrentPlanResultService.class.getMethod(name, long.class);
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional).as(name).isNotNull();
            assertThat(transactional.readOnly()).as(name).isFalse();
        }
    }

    @Test
    @DisplayName("엔진 버전이나 기준 데이터 버전이 바뀌어 최신 기록과 다르면 같은 계획 버전에도 새 행을 기록한다")
    void recordsNewRowWhenVersionsChanged() {
        PlanSaveResult saved = savePlan();
        long before = findInTransaction().calcRunId();
        jdbc.sql("UPDATE calc_run SET engine_version = 'calc-0.9' WHERE id = ?").param(before).update();

        long afterEngineChange = findInTransaction().calcRunId();

        long otherParamVersion = jdbc.sql("INSERT INTO data_version (data_kind, version_label, base_date, source, "
                + "is_active) VALUES ('CALC_PARAM', 'old-test', DATE '2026-01-01', 'test', false) RETURNING id")
                .query(Long.class).single();
        jdbc.sql("UPDATE calc_run SET calc_param_version_id = ? WHERE id = ?")
                .param(otherParamVersion).param(afterEngineChange).update();

        long afterDataChange = findInTransaction().calcRunId();

        assertThat(afterEngineChange).isNotEqualTo(before);
        assertThat(afterDataChange).isNotEqualTo(afterEngineChange);
        assertThat(countPlanRuns(saved.planVersionId())).isEqualTo(3);
        assertThat(findInTransaction().calcRunId()).isEqualTo(afterDataChange);
    }

    @Test
    @DisplayName("입력 저장과 같은 트랜잭션에서 기록하면 커밋 뒤 리스너는 기록을 건너뛴다")
    void listenerSkipsWhenRecordedInSaveTransaction() {
        long planVersionId = transactions.execute(status -> {
            PlanSaveResult saved = planSaveService.save(memberId, valid().build(), null);
            resultService.findForCurrentPlan(memberId).orElseThrow();
            return saved.planVersionId();
        });
        awaitEventsCompleted();

        assertThat(countPlanRuns(planVersionId)).isEqualTo(1);
    }
}
