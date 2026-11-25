package com.moeun.backend.archfixture.domain;

import com.moeun.backend.archfixture.application.SampleServiceImpl;
import com.moeun.backend.archfixture.web.SampleController;

/** 위반 표본: domain이 application·web에 의존한다. */
public class LayerDependentRule {

    public String describe(SampleServiceImpl service, SampleController controller) {
        return service.toString() + controller;
    }
}
