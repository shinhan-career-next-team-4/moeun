package com.moeun.backend.refdata.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EtfMonthlyRepository extends JpaRepository<EtfMonthly, EtfMonthlyId> {

    List<EtfMonthly> findByIdDataVersionIdOrderByIdYmAsc(Long dataVersionId);
}
