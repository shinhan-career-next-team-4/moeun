---
paths:
  - "app/llm/**"
  - "app/rag/**"
  - "app/ingest/**"
  - "tests/**"
---

# LangChain과 LangGraph

LangChain은 부품(모델 호출, 문서 로딩·분할, retriever 인터페이스), LangGraph는 요청 한 건의 흐름과 상태를 맡는다. `CLAUDE.md`의 절대 원칙과 `rag.md`의 불변 조건은 그대로다. 프레임워크가 그 규칙과 부딪히면 프레임워크 기능을 쓰지 않고 직접 쓴다.

상태: 규칙만 정했다. `app/llm/agent.py`는 아직 `anthropic` SDK 루프다. 새 코드는 이 규칙을 따르고, 기존 코드는 별도 작업으로 옮긴다.

## 바꾸지 않는 것
- 첫 입력(목표·조건)은 지금처럼 폼으로 받고 Spring이 검증·계산한다. 자연어로 받지 않는다.
- `/chat`의 기존 필드와 뜻은 그대로다. 아래 `clarify_fields`만 선택 필드로 더한다.

## LangChain을 쓰는 곳
- **모델 호출**: `langchain-anthropic`의 채팅 모델. 구조화 출력과 도구 스키마는 pydantic 모델로 넘긴다. 모델 ID·effort는 지금처럼 `Settings`에서 받는다.
- **임베딩**: `langchain-openai`의 임베딩. 1536차원 검사(`EMBEDDING_DIM`)는 유지한다.
- **문서 로딩·분할**: 적재 CLI에서 LangChain 로더와 text splitter를 쓴다. 분할 규칙은 `rag.md`의 청킹 규칙을 만족해야 하며, 기본 분할기가 못 지키면(문장 중간 절단, 제목 맥락 누락) 분할기를 감싸거나 직접 쓴다.
- **retriever**: `BaseRetriever`를 상속한 우리 클래스가 `app/rag/search.py`의 SQL을 실행한다. 돌려주는 `Document`의 metadata에 `chunk_id`, `source_url`, `doc_type`, `org_name`, `distance`를 넣는다.

## LangChain을 쓰지 않는 곳
- **벡터 저장소 클래스(`PGVector` 등)**: 자기 테이블을 만들고 자기 SQL을 쓴다. 우리 스키마(`rag_chunk`, `chunk_embedding`, 부분 HNSW, `chat_message_citation`의 FK)와 맞지 않는다. 검색 SQL과 적재 SQL은 psycopg로 직접 쓴다.
- **`add_documents`·upsert류 적재**: 청크·임베딩은 UPDATE하지 않는다. 개정본과 재청킹은 `app/ingest/cli.py`의 방식만 쓴다.
- **미리 만들어진 에이전트(ReAct 실행기 등)**: 흐름은 아래 그래프로 직접 그린다.
- **외부 추적 서비스(LangSmith 등)**: 사용자 발화와 계획 정보가 외부로 나간다. 팀 결정 전에는 켜지 않는다. `.env.example`에 `LANGSMITH_TRACING=false`를 적어 둔다. LangChain은 `.env`가 아니라 프로세스 환경 변수를 읽으므로, 전환 작업에서 `Settings`에 필드를 두고 앱 시작 때 꺼진 값을 강제한다.

## LangGraph
- 그래프는 요청 한 건(`/chat` 호출 한 번)의 흐름이다. 기준 흐름: 입력 확인 → (부족하면 되묻기로 종료) → 계산 API 호출 → 근거 검색 → 답변 생성 → 응답 보정.
- **노드는 최소로 잡는다. 상한은 6개다.** 기준 흐름의 단계라도 지금 동작에 필요 없으면 노드로 만들지 않는다. 노드를 늘리거나 순환(재검색 루프)을 넣으려면 평가 결과나 실패 사례를 근거로 든다.
- **상태가 서버 사실이다.** 지금의 `_Turn` 역할을 그래프 상태가 맡는다. `retrieved_ids`, `calc_run_id`, `condition_changes`는 노드가 실제 호출 결과로만 쓴다. 모델 출력에서 가져오지 않는다.
- **마지막 노드는 `assemble`이다.** 모든 경로(되묻기 포함)가 `assemble`을 거쳐 `ChatResponse`가 된다. 인용은 상태의 `retrieved_ids`에 있는 것만 남긴다.
- **입력 확인 노드는 되물을지만 판단한다.** 조건이 빠졌거나 모호한지 보고 `CLARIFY`로 끝낸다. 값의 유효성 검증과 금액·수익률·기간 계산은 Spring이 한다(NFR-INT-01).
- **계산 노드는 Spring `/internal/calc`만 호출한다.** 실패는 `CalcError` → `AgentError(code)`로 올린다.
- **대화 간 상태는 저장하지 않는다.** checkpointer를 쓰지 않는다. 되묻기는 응답으로 끝나고, 다음 요청의 `history`는 Spring이 준다. checkpointer는 자기 테이블을 만들므로 도입하려면 `../db`와 먼저 정한다.
- 노드는 의존성(`Calculator`, retriever, 채팅 모델)을 `Protocol`·생성자 주입으로 받는 async 함수다. 그래프는 lifespan에서 한 번 만든다. `app/llm`은 FastAPI를 import하지 않는다.

## 되묻기 계약(`clarify_fields`)
되묻기 뒤의 요청에서 무엇이 부족했는지를 `history` 텍스트로 다시 추론하지 않기 위한 필드다.

- **응답**: `ChatResponse.clarify_fields`(JSON `clarifyFields`). `message_type=CLARIFY`일 때 부족하거나 모호한 조건의 목록, 그 밖에는 빈 배열이다.
- **요청**: `ChatRequest.clarify_fields`. Spring은 직전 assistant 응답이 `CLARIFY`였으면 그 값을 고치지 않고 그대로 넘긴다. 아니면 빈 배열이다.
- **값**: `ConditionChanges`의 필드 이름(snake_case, `condition_changes`의 키와 같은 표기)만 허용한다. 조건과 무관한 되묻기는 빈 배열이다.
- **서버 사실로 다룬다.** `assemble`이 허용된 이름만 남기고, `CLARIFY`가 아니면 비운다. 입력 확인 노드는 요청의 `clarify_fields`를 상태에 넣어 쓰고, 모델에게 `history`에서 다시 찾게 하지 않는다.
- 구현은 LangGraph 전환 작업에서 한다. 선택 필드이며 기본값이 빈 배열이므로 기존 요청·응답은 그대로 통한다.
- **미정**: Spring이 이 값을 요청 사이에 어디에 보관할지(`chat_message` 컬럼 추가 여부)는 `../db`와 backend가 정한다. 정해지기 전에는 ai-server 쪽 필드만 만든다.

## 전환 작업(`app/llm/agent.py`)
- 별도 작업·별도 PR로 한다. 이 규칙 파일의 변경과 섞지 않는다.
- **완료 기준**: 기존 SDK 루프의 입력·출력 테스트(`tests/`의 `/chat` 케이스)가 같은 입력에 같은 출력으로 통과한다. 테스트의 기대값은 고치지 않고, fake만 SDK용에서 LangChain용으로 바꾼다.
- 동작을 바꾸는 일(`clarify_fields` 채우기, 독립 질의 변환)은 위 기준을 통과한 뒤 그 위에 따로 얹는다.

## 테스트
- 네트워크를 쓰지 않는다. 채팅 모델은 LangChain의 fake 채팅 모델, 검색은 fake retriever, 계산은 fake `Calculator`.
- 노드는 함수 단위로 테스트한다(상태 입력 → 상태 변화). 그래프 전체는 경로별로 1건씩: 정상, 되묻기, 계산 실패, 근거 없음.
- 모델이 검색되지 않은 `chunk_id`나 다른 `calc_run_id`를 내놓는 케이스를 넣어 `assemble`이 걸러 내는지 확인한다.

## 버전
- LangChain·LangGraph는 사용법이 자주 바뀐다. 기억으로 쓰지 말고 설치된 버전의 공식 문서를 확인한다. `pyproject.toml`에 하한을 적고 `uv.lock`으로 고정한다.
- 타입이 없는 부분에는 `# type: ignore[코드]`를 쓰되 `python.md` 규칙대로 서드파티 타입 부재일 때만 쓴다.
