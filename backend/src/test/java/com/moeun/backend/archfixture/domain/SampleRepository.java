package com.moeun.backend.archfixture.domain;

/** 표본 리포지토리. 스프링이 스캔하지 않도록 Spring Data 인터페이스를 상속하지 않는다. */
public interface SampleRepository {

    long count();
}
