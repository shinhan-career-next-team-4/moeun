package com.moeun.backend.calc.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

/**
 * calc 처리기는 plan 처리기보다 뒤에 본다. Spring은 순서상 처음 맞는 advice를 쓰므로, 같은 순서면 어느 쪽이 먼저일지
 * bean 등록 순서에 달린다. 특히 {@link InternalCalcExceptionHandler}는 {@code Exception} 전체를 잡아서, 앞서면
 * plan 예외(409·422)가 400·500으로 바뀐다.
 */
class ExceptionHandlerOrderTest {

    private static int orderOf(Class<?> type) {
        return type.getAnnotation(Order.class).value();
    }

    @Test
    @DisplayName("calc 처리기 두 개는 plan 처리기보다 순서 값이 크다(나중에 본다)")
    void checksPlanHandlerFirst() throws Exception {
        int plan = orderOf(Class.forName("com.moeun.backend.plan.web.PlanExceptionHandler"));

        assertThat(orderOf(PlanResultExceptionHandler.class)).isGreaterThan(plan);
        assertThat(orderOf(InternalCalcExceptionHandler.class)).isGreaterThan(plan);
    }
}
