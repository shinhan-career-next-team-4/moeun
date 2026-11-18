package com.moeun.backend.plan.web;

import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.ConditionOverride;

/** 적용 요청. source는 ALTERNATIVE / COMPARE / CHAT. expectedRevision은 확인창을 열 때의 계획 버전(PR-08). */
record ApplyRequest(ChangePath source, ConditionOverride changes, Long expectedRevision) {
}
