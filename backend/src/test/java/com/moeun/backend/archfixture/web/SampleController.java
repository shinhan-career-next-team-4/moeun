package com.moeun.backend.archfixture.web;

import com.moeun.backend.archfixture.domain.SampleRepository;

/** 위반 표본: 컨트롤러가 domain 리포지토리를 직접 쓴다. */
public class SampleController {

    private final SampleRepository repository;

    public SampleController(SampleRepository repository) {
        this.repository = repository;
    }

    public long count() {
        return repository.count();
    }
}
