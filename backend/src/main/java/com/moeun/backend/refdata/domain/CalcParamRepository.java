package com.moeun.backend.refdata.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CalcParamRepository extends JpaRepository<CalcParam, CalcParamId> {

    List<CalcParam> findByIdDataVersionId(Long dataVersionId);
}
