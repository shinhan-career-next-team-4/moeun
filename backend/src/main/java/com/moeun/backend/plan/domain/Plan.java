package com.moeun.backend.plan.domain;

import com.moeun.backend.plan.NothingToUndoException;
import com.moeun.backend.plan.PlanChangedException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 사용자별 현재 계획 포인터. revision은 JPA {@code @Version}이라 포인터를 옮기면 저장 시점에
 * {@code UPDATE ... WHERE revision = ?}로 낙관적 락이 걸린다. 첫 행은 PlanRepository.insertIfAbsent로 넣는다.
 */
@Entity
@Table(name = "plan")
public class Plan {

    private static final long FIRST_REVISION = 1L;

    @Id
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "current_version_id", nullable = false)
    private Long currentVersionId;

    @Column(name = "previous_version_id")
    private Long previousVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "baseline_scenario", nullable = false, length = 10)
    private BaselineScenario baselineScenario;

    @Version
    @Column(name = "revision", nullable = false)
    private Long revision;

    protected Plan() {
    }

    private Plan(Long memberId, Long currentVersionId, BaselineScenario baselineScenario, Long revision) {
        this.memberId = memberId;
        this.currentVersionId = currentVersionId;
        this.baselineScenario = baselineScenario;
        this.revision = revision;
    }

    /** 첫 계획. 기준 시나리오는 평균(TBD-40), revision은 1이다. */
    public static Plan start(long memberId, long firstVersionId) {
        return new Plan(memberId, firstVersionId, BaselineScenario.AVG, FIRST_REVISION);
    }

    /** 화면이 가진 revision과 다르면(없다고 믿었으면 null) 409 PLAN_CHANGED. */
    public void requireRevision(Long expectedRevision) {
        if (!revision.equals(expectedRevision)) {
            throw new PlanChangedException();
        }
    }

    public boolean canUndo() {
        return previousVersionId != null;
    }

    /** 새 버전을 현재로 두고 지금 버전을 되돌리기 대상으로 기억한다. */
    public void pointTo(long versionId) {
        previousVersionId = currentVersionId;
        currentVersionId = versionId;
    }

    /** 되돌리기(D-068): 직전 버전의 복사본을 현재로 두고 직전 기록을 비운다. 한 단계만 되돌린다. */
    public void undoTo(long copiedVersionId) {
        if (!canUndo()) {
            throw new NothingToUndoException();
        }
        currentVersionId = copiedVersionId;
        previousVersionId = null;
    }

    public Long getMemberId() {
        return memberId;
    }

    public Long getCurrentVersionId() {
        return currentVersionId;
    }

    public Long getPreviousVersionId() {
        return previousVersionId;
    }

    public BaselineScenario getBaselineScenario() {
        return baselineScenario;
    }

    public Long getRevision() {
        return revision;
    }
}
