package com.moeun.backend.calc.domain;

import java.time.Instant;

import com.moeun.backend.calc.AppliedDataVersions;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계산 기록. 결과는 저장하지 않고 "무엇으로 계산했는지"만 남긴다(ADR-002, NFR-INT-02).
 * 같은 계획 버전·기준 데이터 버전·엔진 버전이면 언제 다시 계산해도 같은 결과가 나온다.
 * <p>다른 모듈의 행(plan_version, data_version)은 id로만 참조한다.
 */
@Entity
@Table(name = "calc_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CalcRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long planVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RunType runType;

    private Long savingsRateVersionId;

    private Long sp500VersionId;

    @Column(nullable = false)
    private Long calcParamVersionId;

    @Column(nullable = false, length = 20)
    private String engineVersion;

    // DB의 DEFAULT now()가 채운다
    @Column(nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    private CalcRun(long planVersionId, RunType runType, AppliedDataVersions versions) {
        this.planVersionId = planVersionId;
        this.runType = runType;
        this.savingsRateVersionId = versions.savingsRateVersionId();
        this.sp500VersionId = versions.sp500VersionId();
        this.calcParamVersionId = versions.calcParamVersionId();
        this.engineVersion = versions.engineVersion();
    }

    /** 내 계획 결과 기록(run_type = PLAN). */
    public static CalcRun createPlanRun(long planVersionId, AppliedDataVersions versions) {
        return new CalcRun(planVersionId, RunType.PLAN, versions);
    }

    /** 대화 재계산 기록(run_type = CHAT). 요청마다 새 행이다(계약 C2-7). */
    public static CalcRun createChatRun(long planVersionId, AppliedDataVersions versions) {
        return new CalcRun(planVersionId, RunType.CHAT, versions);
    }

    /** 이 기록이 같은 기준 데이터 버전과 엔진 버전으로 계산한 것인지. 다르면 같은 계획 버전이라도 새로 기록한다(G-18). */
    public boolean hasSameVersions(AppliedDataVersions versions) {
        return toVersions().equals(versions);
    }

    public AppliedDataVersions toVersions() {
        return new AppliedDataVersions(savingsRateVersionId, sp500VersionId, calcParamVersionId, engineVersion);
    }
}
