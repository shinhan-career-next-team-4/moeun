---
paths:
  - "app/rag/**"
  - "app/ingest/**"
  - "app/llm/**"
---

# RAG와 신뢰성

## 입력 두 갈래
- 첫 입력은 폼(구조화 값)이다. RAG 대상이 아니며 Spring이 검증·계산한다.
- 재질문은 자연어다. 조건 변경은 `recalculate` 도구로 구조화(`ConditionChanges`)하고, 설명 근거는 검색한다.
- 자연어 재질문의 검색어는 발화만 쓰지 않는다. 직전 대화와 `plan_context`로 생략된 주어·대상을 채운 독립 질의로 만든다("그럼 기간 늘리면?" → 무엇의 기간인지 포함).

## 청킹 (`app/ingest/chunker.py`)
- 재질문은 짧은 구어체 한국어다. 청크 하나가 질문 하나에 답할 수 있는 의미 단위(문단·소제목)가 되게 자른다.
- 문장 중간에서 자르지 않는다. 긴 문단은 문장 경계 + overlap으로 나눈다.
- 청크 앞에 문서 제목·소제목 같은 맥락을 붙여 임베딩한다(청크만 떼어 읽어도 무엇에 대한 내용인지 알 수 있게).
- 청킹 규칙을 바꾸면 기존 문서를 `moeun-ingest --rechunk`로 재적재해야 한다. 바꾼 이유와 검색 결과 비교를 PR에 남긴다.

## 검색·적재 불변 조건
- 검색 SQL은 `e.is_active AND e.model_code = %(model)s AND d.is_active`를 반드시 둔다(부분 HNSW 인덱스).
- 임베딩은 1536차원(`EMBEDDING_DIM`). 모델을 바꾸면 `model_code`가 다른 새 행으로 넣는다.
- 청크·임베딩은 UPDATE하지 않는다(`is_active` 전환만 예외). 개정본은 새 `rag_document`로 넣고 이전 것은 `is_active=FALSE`(문서·임베딩을 같은 트랜잭션에서).
- 재청킹(`--rechunk`)은 같은 문서에서 지금 쓰는 `model_code`의 기존 임베딩만 `is_active=FALSE`로 바꾸고 새 청크를 `chunk_no`를 이어 넣는다. 한 문서에서 현재 청크는 그 모델의 임베딩이 활성인 것이다. 청킹이 바뀌지 않았으면 돌리지 않는다(죽은 행만 쌓인다).
- 공시(DISCLOSURE) 근거는 서로 다른 운용사 2곳 이상(FR-AI-03, `diversify_orgs`).

## 응답
- `citation_chunk_ids`는 `_Turn.retrieved_ids`에 있는 것만. 근거가 없으면 빈 배열.
- 응답 필드 조합은 `chat_message` CHECK(`../db/erd.md`)를 만족해야 한다. 보정은 `assemble` 한 곳에서 한다.
