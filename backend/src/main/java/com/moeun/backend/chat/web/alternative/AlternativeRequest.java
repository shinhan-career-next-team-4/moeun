package com.moeun.backend.chat.web.alternative;

/**
 * 대안 요청. 본문은 비어 있어도 된다.
 *
 * @param targetPeriodMonths '기한을 늘리면'의 원하는 기간(개월). 없으면 그 카드는 입력 전의 빈 카드다(D-039)
 */
record AlternativeRequest(Integer targetPeriodMonths) {
}
