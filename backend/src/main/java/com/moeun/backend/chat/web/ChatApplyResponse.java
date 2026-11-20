package com.moeun.backend.chat.web;

/** F-5 응답. 적용 뒤 새 plan.revision 하나뿐이다(버전 id·되돌리기 가능 여부는 싣지 않는다, 계약 H-6·C12b-7). */
record ChatApplyResponse(long revision) {
}
