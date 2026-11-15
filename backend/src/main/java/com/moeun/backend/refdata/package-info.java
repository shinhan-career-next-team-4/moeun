/**
 * 기준 데이터와 버전. 외부 시점 데이터라 적재 후 불변이며, 종류별 활성 버전은 1개.
 * <p>테이블: data_version, savings_avg_rate, sp500_monthly, calc_param.
 * 주요 클래스: DataVersionService(requireKind), CalcParamKey, CalcParamLoader.
 */
@ApplicationModule(displayName = "기준 데이터", allowedDependencies = {})
package com.moeun.backend.refdata;

import org.springframework.modulith.ApplicationModule;
