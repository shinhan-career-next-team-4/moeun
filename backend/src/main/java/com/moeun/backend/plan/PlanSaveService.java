package com.moeun.backend.plan;

import java.util.Optional;

/** 입력 저장(INPUT) 및 현재 계획 조회 공개 서비스. */
public interface PlanSaveService {

    PlanSaveResult save(long memberId, PlanInput input, Long expectedRevision);

    Optional<PlanView> findCurrent(long memberId);

    /** 내 plan_version 하나를 본다. 현재 버전이 아니어도 돌려주고, 남의 버전·없는 ID면 empty다(G-12). */
    Optional<PlanView> findVersion(long memberId, long planVersionId);
}
