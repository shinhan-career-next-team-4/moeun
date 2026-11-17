package com.moeun.backend.plan.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    /** 현재 계획을 바꾸고 직전 계획을 기억한다. revision이 다르면 0행(동시 수정). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Plan p set p.previousVersionId = p.currentVersionId, p.currentVersionId = :newVersionId,
                p.revision = p.revision + 1
            where p.memberId = :memberId and p.revision = :expectedRevision""")
    int replaceCurrent(@Param("memberId") Long memberId, @Param("newVersionId") Long newVersionId,
            @Param("expectedRevision") Long expectedRevision);

    /** 입력 화면 재저장: revision 검사 없이 덮어쓴다(나중 저장 우선, TBD-37). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Plan p set p.previousVersionId = p.currentVersionId, p.currentVersionId = :newVersionId,
                p.revision = p.revision + 1
            where p.memberId = :memberId""")
    int overwriteCurrent(@Param("memberId") Long memberId, @Param("newVersionId") Long newVersionId);

    /** 직전 계획으로 되돌리고 직전 기록을 비운다. 되돌리기는 한 단계까지만(FR-PLAN-04). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Plan p set p.currentVersionId = p.previousVersionId, p.previousVersionId = null,
                p.revision = p.revision + 1
            where p.memberId = :memberId and p.revision = :expectedRevision and p.previousVersionId is not null""")
    int restorePrevious(@Param("memberId") Long memberId, @Param("expectedRevision") Long expectedRevision);
}
