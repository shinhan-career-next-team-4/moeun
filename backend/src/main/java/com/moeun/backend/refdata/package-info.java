/**
 * 기준 데이터와 버전. 외부 시점 데이터라 적재 후 불변이며, 종류별 활성 버전은 1개(ETF 가격은 종목별 1개).
 * <p>테이블: data_version, savings_avg_rate, etf_monthly, etf_product_version, calc_param.
 * (sp500_monthly는 #138 전 구조로, 기존 로컬 DB 보정에만 쓰고 읽지 않는다)
 * 주요 클래스: DataVersionService(requireKind), CalcParamKey, CalcParamLoader.
 * <p>다른 모듈은 ReferenceDataService로 활성 데이터를 값(SavingsAvgRateData, EtfSeries, CalcParams)으로 받는다.
 * 적재는 application.ReferenceDataLoader가 버전 단위로 하고, 서버가 뜰 때 moeun.refdata.seeds의 CSV를 넣는다.
 */
@ApplicationModule(displayName = "기준 데이터", allowedDependencies = {})
package com.moeun.backend.refdata;

import org.springframework.modulith.ApplicationModule;
