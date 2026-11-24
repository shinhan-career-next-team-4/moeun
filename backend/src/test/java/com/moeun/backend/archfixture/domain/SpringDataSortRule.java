package com.moeun.backend.archfixture.domain;

import org.springframework.data.domain.Sort;

/** 허용 표본: domain은 Spring Data에 의존해도 된다. */
public class SpringDataSortRule {

    public Sort newestFirst() {
        return Sort.by(Sort.Direction.DESC, "id");
    }
}
