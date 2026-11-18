package com.moeun.backend.plan.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    /** 첫 계획 저장. 이미 행이 있으면 0행을 돌려줘 동시 생성 충돌을 알린다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO plan (member_id, current_version_id, baseline_scenario, revision)
            VALUES (:memberId, :versionId, :baselineScenario, 1)
            ON CONFLICT (member_id) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("memberId") Long memberId, @Param("versionId") Long versionId,
            @Param("baselineScenario") String baselineScenario);

    /** 현재 계획을 바꾸고 직전 계획을 기억한다. revision이 다르면 0행(동시 수정). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Plan p set p.previousVersionId = p.currentVersionId, p.currentVersionId = :newVersionId,
                p.revision = p.revision + 1
            where p.memberId = :memberId and p.revision = :expectedRevision""")
    int replaceCurrent(@Param("memberId") Long memberId, @Param("newVersionId") Long newVersionId,
            @Param("expectedRevision") Long expectedRevision);

    /** 되돌리기(D-068): 복사 생성한 새 버전을 현재로 두고 직전 기록을 비운다. revision이 다르면 0행. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Plan p set p.currentVersionId = :newVersionId, p.previousVersionId = null,
                p.revision = p.revision + 1
            where p.memberId = :memberId and p.revision = :expectedRevision""")
    int applyUndo(@Param("memberId") Long memberId, @Param("newVersionId") Long newVersionId,
            @Param("expectedRevision") Long expectedRevision);
}
