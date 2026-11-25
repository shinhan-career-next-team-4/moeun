package com.moeun.backend.archfixture.domain;

import org.springframework.util.StringUtils;

/** 위반 표본: domain이 Spring(JPA·Spring Data 외)에 의존한다. */
public class SpringDependentRule {

    public boolean hasName(String name) {
        return StringUtils.hasText(name);
    }
}
