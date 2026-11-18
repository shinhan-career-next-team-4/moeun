package com.moeun.backend.plan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 사용자별 현재 계획 포인터. 갱신은 PlanRepository의 revision 조건 UPDATE로만 한다(낙관적 락). */
@Entity
@Table(name = "plan")
public class Plan {

    @Id
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "current_version_id", nullable = false)
    private Long currentVersionId;

    @Column(name = "previous_version_id")
    private Long previousVersionId;

    @Column(name = "baseline_scenario", nullable = false, length = 10)
    private String baselineScenario;

    @Column(name = "revision", nullable = false)
    private Long revision;

    protected Plan() {
    }

    public Plan(Long memberId, Long currentVersionId, String baselineScenario) {
        this.memberId = memberId;
        this.currentVersionId = currentVersionId;
        this.baselineScenario = baselineScenario;
        this.revision = 1L;
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

    public String getBaselineScenario() {
        return baselineScenario;
    }

    public Long getRevision() {
        return revision;
    }
}
