package com.moeun.backend.plan.web;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moeun.backend.plan.GoalType;
import com.moeun.backend.plan.application.PlanRules;

/** GET /api/input-rules 응답. 프론트가 같은 규칙으로 즉시 검증한다. 금액은 원. */
record InputRulesView(Map<String, FieldRuleView> fields, List<GoalTypeView> goalTypes, List<Integer> periodPresets,
        int baseSavingsRatioPct) {

    static InputRulesView from(PlanRules rules) {
        Map<String, FieldRuleView> fields = new LinkedHashMap<>();
        rules.fields().forEach((field, rule) -> fields.put(field.apiName(),
                new FieldRuleView(rule.required(), rule.min(), rule.max(), rule.unit(), rule.scale(), rule.step())));
        List<GoalTypeView> goalTypes = Arrays.stream(GoalType.values())
                .map(type -> {
                    PlanRules.GoalDefault goalDefault = rules.goalDefaults().get(type);
                    return goalDefault == null
                            ? new GoalTypeView(type, null, null)
                            : new GoalTypeView(type, goalDefault.amount(), goalDefault.periodMonths());
                })
                .toList();
        return new InputRulesView(fields, goalTypes, rules.periodPresets(), rules.baseSavingsRatioPct());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FieldRuleView(boolean required, BigDecimal min, BigDecimal max, String unit, Integer scale, Long step) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GoalTypeView(GoalType code, Long defaultAmount, Integer defaultPeriodMonths) {
    }
}
