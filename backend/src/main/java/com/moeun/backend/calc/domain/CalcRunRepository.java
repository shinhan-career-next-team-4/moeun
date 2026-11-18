package com.moeun.backend.calc.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CalcRunRepository extends JpaRepository<CalcRun, Long> {

    /** 그 계획 버전의 가장 최근 기록. 같은 시각이면 나중에 넣은 id가 최신이다(ix_calc_run_latest). */
    Optional<CalcRun> findFirstByPlanVersionIdAndRunTypeOrderByCreatedAtDescIdDesc(long planVersionId,
            RunType runType);
}
