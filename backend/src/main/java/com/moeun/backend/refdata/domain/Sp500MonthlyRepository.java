package com.moeun.backend.refdata.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface Sp500MonthlyRepository extends JpaRepository<Sp500Monthly, Sp500MonthlyId> {

    List<Sp500Monthly> findByIdDataVersionIdOrderByIdYmAsc(Long dataVersionId);
}
