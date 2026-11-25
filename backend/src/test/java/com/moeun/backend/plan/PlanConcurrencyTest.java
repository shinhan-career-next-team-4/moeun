package com.moeun.backend.plan;

import static com.moeun.backend.plan.application.PlanInputBuilder.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moeun.backend.config.TestcontainersConfig;
import com.moeun.backend.plan.application.PlanUndoService;
import com.moeun.backend.plan.domain.PlanRepository;

import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 현재 계획 포인터의 낙관적 락(@Version, #310). 같은 revision으로 동시에 바꾸면 하나만 성공하고 나머지는 409 PLAN_CHANGED다
 * (본문에 현재 상태 없음, 계약 X-10). 실패한 쪽은 롤백되어 plan_version도 남기지 않는다.
 */
@ApplicationModuleTest(extraIncludes = {"config", "auth"})
@Import({TestcontainersConfig.class, LoadedEtfProductsConfig.class})
class PlanConcurrencyTest {

    @Autowired
    PlanSaveService saveService;

    @Autowired
    PlanApplyService applyService;

    @Autowired
    PlanUndoService undoService;

    @Autowired
    PlanRepository planRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcClient jdbc;

    long memberId;

    @BeforeEach
    void createMember() {
        jdbc.sql("DELETE FROM plan").update();
        jdbc.sql("DELETE FROM plan_version").update();
        jdbc.sql("DELETE FROM member").update();
        memberId = jdbc.sql("INSERT INTO member (login_id, password_hash) VALUES ('me', 'x') RETURNING id")
                .query(Long.class).single();
    }

    @Test
    void saveFailsWhenPlanChangesAfterItWasRead() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);

        assertThatThrownBy(() -> afterPlanIsReadAndChangedElsewhere(
                () -> saveService.save(memberId, valid().monthlySavingAmount(1_500_000L).build(), saved.revision())))
                .isInstanceOf(PlanChangedException.class);

        assertThat(versionCount()).isEqualTo(1);
        assertThat(currentVersionId()).isEqualTo(saved.planVersionId());
    }

    @Test
    void applyFailsWhenPlanChangesAfterItWasRead() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);

        assertThatThrownBy(() -> afterPlanIsReadAndChangedElsewhere(() -> applyService.apply(memberId,
                ChangePath.CHAT, Map.of("monthlySavingAmount", JsonNodeFactory.instance.numberNode(1_500_000L)),
                saved.revision(), null)))
                .isInstanceOf(PlanChangedException.class);

        assertThat(versionCount()).isEqualTo(1);
        assertThat(currentVersionId()).isEqualTo(saved.planVersionId());
    }

    @Test
    void undoFailsWhenPlanChangesAfterItWasRead() {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);
        PlanSaveResult second = saveService.save(memberId, valid().monthlySavingAmount(1_500_000L).build(),
                saved.revision());

        assertThatThrownBy(() -> afterPlanIsReadAndChangedElsewhere(() -> undoService.undo(memberId,
                second.revision())))
                .isInstanceOf(PlanChangedException.class);

        assertThat(versionCount()).isEqualTo(2);
        assertThat(currentVersionId()).isEqualTo(second.planVersionId());
    }

    @Test
    void concurrentSavesWithSameRevisionLetOnlyOneWin() throws Exception {
        PlanSaveResult saved = saveService.save(memberId, valid().build(), null);

        List<Object> outcomes = runConcurrently(4,
                () -> saveService.save(memberId, valid().monthlySavingAmount(1_500_000L).build(), saved.revision()));

        assertOneWinnerOthersPlanChanged(outcomes);
        assertThat(versionCount()).isEqualTo(2);
        assertThat(revision()).isEqualTo(saved.revision() + 1);
    }

    @Test
    void concurrentFirstSavesLetOnlyOneWin() throws Exception {
        List<Object> outcomes = runConcurrently(4, () -> saveService.save(memberId, valid().build(), null));

        assertOneWinnerOthersPlanChanged(outcomes);
        assertThat(versionCount()).isEqualTo(1);
        assertThat(revision()).isEqualTo(1L);
    }

    /**
     * 한 트랜잭션에서 계획을 먼저 읽어 둔 뒤, 다른 연결이 revision을 올려 커밋하고 나서 작업을 부른다. 작업은 이 트랜잭션에
     * 합류하므로 읽어 둔(이제 낡은) 계획으로 revision 검사를 통과하고, 저장 시점의 낙관적 락에서 걸려야 한다.
     */
    private void afterPlanIsReadAndChangedElsewhere(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            planRepository.findById(memberId).orElseThrow();
            CompletableFuture.runAsync(() -> jdbc.sql("UPDATE plan SET revision = revision + 1 WHERE member_id = ?")
                    .param(memberId).update()).join();
            action.run();
        });
    }

    private static List<Object> runConcurrently(int threads, Supplier<Object> action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return action.get();
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertOneWinnerOthersPlanChanged(List<Object> outcomes) {
        assertThat(outcomes).filteredOn(PlanSaveResult.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !(outcome instanceof PlanSaveResult))
                .hasSize(outcomes.size() - 1)
                .allSatisfy(outcome -> assertThat(outcome).isInstanceOf(PlanChangedException.class));
    }

    private int versionCount() {
        return jdbc.sql("SELECT count(*) FROM plan_version WHERE member_id = ?").param(memberId)
                .query(Integer.class).single();
    }

    private long currentVersionId() {
        return jdbc.sql("SELECT current_version_id FROM plan WHERE member_id = ?").param(memberId)
                .query(Long.class).single();
    }

    private long revision() {
        return jdbc.sql("SELECT revision FROM plan WHERE member_id = ?").param(memberId).query(Long.class).single();
    }
}
