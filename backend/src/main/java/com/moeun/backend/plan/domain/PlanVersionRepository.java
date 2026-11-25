package com.moeun.backend.plan.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlanVersionRepository extends JpaRepository<PlanVersion, Long> {

    Optional<PlanVersion> findByIdAndMemberId(Long id, Long memberId);

    /** 그 회원의 계획 버전을 모두 지운다(데모 초기화). calc_run은 ON DELETE CASCADE로 함께 지워진다. plan 행을 먼저 지운 뒤 부른다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM plan_version WHERE member_id = :memberId", nativeQuery = true)
    int deleteAllByMemberId(@Param("memberId") Long memberId);
}
