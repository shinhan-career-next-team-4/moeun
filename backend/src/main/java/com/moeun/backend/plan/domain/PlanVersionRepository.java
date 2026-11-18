package com.moeun.backend.plan.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanVersionRepository extends JpaRepository<PlanVersion, Long> {

    Optional<PlanVersion> findByIdAndMemberId(Long id, Long memberId);
}
