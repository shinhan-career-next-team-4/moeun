package com.moeun.backend.calc.application;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.moeun.backend.calc.AppliedDataVersions;
import com.moeun.backend.calc.domain.CalcRun;
import com.moeun.backend.calc.domain.CalcRunRepository;
import com.moeun.backend.calc.domain.RunType;
import com.moeun.backend.refdata.DataKind;
import com.moeun.backend.refdata.DataVersionService;

/**
 * 계획 버전의 calc_run(PLAN)을 "없으면 기록"한다(계약 G-18, C12a-6-6).
 * <p>결과 조회와 PlanChanged 리스너가 거의 동시에 같은 버전을 기록하려 할 수 있다. 그래서 회원 단위
 * advisory 잠금을 잡은 <b>뒤에</b> 최신 기록을 조회한다. 늦은 쪽은 앞쪽이 커밋한 행을 보고 건너뛴다.
 * 잠금이 제대로 동작하려면 아래를 지킨다.
 * <ol>
 *     <li>호출자 트랜잭션 안에서만 돈다(MANDATORY). {@code pg_advisory_xact_lock}은 트랜잭션 끝에 풀린다</li>
 *     <li>잠금 획득 뒤에 조회한다. 잠금 전에 조회한 결과를 재사용하지 않는다</li>
 *     <li>읽기 전용 트랜잭션에서 부르지 않는다(INSERT가 거절된다)</li>
 *     <li>리스너는 커밋 뒤 새 트랜잭션에서 이 메서드를 부른다({@code @ApplicationModuleListener})</li>
 * </ol>
 */
@Component
public class CalcRunRecorder {

    /** 잠금 키 이름표. 다른 advisory 잠금과 겹치지 않게 문자열을 해시해 64비트 키로 만든다. */
    private static final String LOCK_NAMESPACE = "calc_run_plan:";

    private final CalcRunRepository calcRunRepository;
    private final DataVersionService dataVersionService;
    private final JdbcClient jdbc;

    public CalcRunRecorder(CalcRunRepository calcRunRepository, DataVersionService dataVersionService,
            JdbcClient jdbc) {
        this.calcRunRepository = calcRunRepository;
        this.dataVersionService = dataVersionService;
        this.jdbc = jdbc;
    }

    /**
     * 그 계획 버전의 최신 PLAN 기록이 같은 기준 데이터·엔진 버전이면 그 id를, 아니면 새로 기록한 id를 돌려준다.
     *
     * @param memberId 잠금을 잡을 회원
     * @param planVersionId 계산한 계획 버전
     * @param versions 이번 계산에 쓴 기준 데이터 버전과 엔진 버전
     * @return calc_run id(calcRunId)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long recordPlanRunIfAbsent(long memberId, long planVersionId, AppliedDataVersions versions) {
        // 바깥 트랜잭션이 읽기 전용이면 REQUIRED/MANDATORY가 거기에 합류해 INSERT가 거절된다. 원인을 바로 알 수 있게 먼저 막는다
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("calc_run cannot be recorded in a read-only transaction");
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                .param(LOCK_NAMESPACE + memberId)
                .query((resultSet, rowNumber) -> Boolean.TRUE)
                .list();
        Optional<CalcRun> latest = calcRunRepository
                .findFirstByPlanVersionIdAndRunTypeOrderByCreatedAtDescIdDesc(planVersionId, RunType.PLAN);
        if (latest.isPresent() && latest.get().hasSameVersions(versions)) {
            return latest.get().getId();
        }
        requireKinds(versions);
        return calcRunRepository.save(CalcRun.createPlanRun(planVersionId, versions)).getId();
    }

    /** DB가 데이터 종류 일치를 막지 않으므로(ADR-002) 금리 자리에 지수 버전이 들어가지 않게 기록 전에 확인한다. */
    private void requireKinds(AppliedDataVersions versions) {
        if (versions.savingsRateVersionId() != null) {
            dataVersionService.requireKind(versions.savingsRateVersionId(), DataKind.SAVINGS_RATE);
        }
        if (versions.sp500VersionId() != null) {
            dataVersionService.requireKind(versions.sp500VersionId(), DataKind.SP500);
        }
        dataVersionService.requireKind(versions.calcParamVersionId(), DataKind.CALC_PARAM);
    }
}
