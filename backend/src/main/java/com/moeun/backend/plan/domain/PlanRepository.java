package com.moeun.backend.plan.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 현재 계획. 포인터 갱신은 Plan의 메서드와 @Version(revision)으로 한다. */
public interface PlanRepository extends JpaRepository<Plan, Long> {

    /**
     * 첫 계획 저장. 이미 행이 있으면 0행을 돌려줘 동시 생성 충돌을 알린다.
     * JPA persist 대신 쓰는 이유: 키(member_id)가 미리 정해져 save가 merge로 동작하고, PostgreSQL의 unique 위반은
     * 트랜잭션 전체를 깨뜨려 409로 바꾸기 전에 다른 문장을 쓸 수 없다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO plan (member_id, current_version_id, baseline_scenario, revision)
            VALUES (:memberId, :versionId, :baselineScenario, :revision)
            ON CONFLICT (member_id) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("memberId") Long memberId, @Param("versionId") Long versionId,
            @Param("baselineScenario") String baselineScenario, @Param("revision") Long revision);

    /** 새로 만든 첫 계획을 넣는다. 이미 있으면 false. */
    default boolean insertIfAbsent(Plan plan) {
        return insertIfAbsent(plan.getMemberId(), plan.getCurrentVersionId(), plan.getBaselineScenario().name(),
                plan.getRevision()) == 1;
    }
}
