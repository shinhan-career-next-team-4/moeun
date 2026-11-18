package com.moeun.backend.plan;

import java.util.Optional;

/** 입력 저장(INPUT) 및 현재 계획 조회 공개 서비스. */
public interface PlanSaveService {

    PlanSaveResult save(long memberId, PlanInput input, Long expectedRevision);

    Optional<PlanView> findCurrent(long memberId);
}
