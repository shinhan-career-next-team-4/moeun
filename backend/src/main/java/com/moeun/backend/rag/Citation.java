package com.moeun.backend.rag;

/** 인용 출처 한 건. 제목·URL·기관명. */
public record Citation(String title, String sourceUrl, String orgName) {
}
