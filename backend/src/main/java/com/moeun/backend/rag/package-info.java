/**
 * RAG 자료 조회. 적재와 검색은 ai-server가 하고, Spring은 인용 출처 표시용으로 읽기만 한다.
 * <p>테이블: source_org, rag_document, rag_chunk, chunk_embedding (읽기 전용).
 */
@ApplicationModule(displayName = "RAG 자료", allowedDependencies = {})
package com.moeun.backend.rag;

import org.springframework.modulith.ApplicationModule;
