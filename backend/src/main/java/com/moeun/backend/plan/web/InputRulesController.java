package com.moeun.backend.plan.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.plan.application.PlanRules;

@RestController
class InputRulesController {

    private final InputRulesView view;

    InputRulesController(PlanRules rules) {
        this.view = InputRulesView.from(rules);
    }

    @GetMapping("/api/input-rules")
    InputRulesView inputRules() {
        return view;
    }
}
