# moeun ai-server

FastAPI 서버. 문서 RAG와 사용자 입력(자연어 재질문) RAG로 AI 설명의 신뢰성을 높인다. Spring만 호출한다.

## 절대 원칙
1. 금액·수익률·기간을 계산하지 않는다. 숫자는 `plan_context`나 Spring `/internal/calc` 결과만 인용한다(NFR-INT-01).
2. 호출자는 Spring뿐이다. 모든 엔드포인트(`/health` 제외)는 `InternalOnly`를 건다.
3. 인용(`citation_chunk_ids`)은 이번 요청에서 실제로 검색된 chunk_id만 넣는다.
4. 모델 출력보다 서버 사실(`Turn`)을 믿는다. 응답은 `assemble`이 보정한다.

## 기술 선택
- LangChain: 모델 호출, 임베딩, 문서 로딩·분할, retriever 인터페이스.
- LangGraph: 요청 한 건의 흐름과 상태(입력 확인 → 되묻기 → 계산 API → 근거 검색 → 답변 생성 → 보정). 노드 6개 이내로 시작한다.
- DB는 psycopg와 직접 쓴 SQL이다. LangChain의 벡터 저장소 클래스는 쓰지 않는다(팀 스키마와 맞지 않음).
- 경계와 금지 사항은 `.claude/rules/langchain.md`. 기존 `app/llm/agent.py`는 아직 SDK 루프이며 전환은 별도 작업이다.

## 명령
```bash
uv sync
uv run pytest
uv run ruff check .         # ruff format은 쓰지 않는다(기존 코드의 주석 정렬과 충돌)
uv run mypy                  # strict
uv run uvicorn app.main:app --reload --port 8000
```

## 구조
| 위치 | 역할 |
|---|---|
| `app/main.py` | 앱 생성, lifespan, 라우터 등록, 예외 핸들러 |
| `app/api/` | 라우터(`health.py`, `chat.py`, `rag.py`), 의존성(`deps.py`) |
| `app/core/` | 설정(`config.py`), DB 풀(`db.py`), 내부 키 검사(`security.py`), Spring에 돌려주는 에러 코드(`errors.py`의 `ErrorCode`), Spring과 주고받는 display 키 계약(`contract.py`, 키 구성 요소를 한 번만 정의하고 허용 키·필수 키·누출 검사 정규식을 만든다) |
| `app/llm/` | 대화 흐름(LangGraph로 전환 예정, 현재는 SDK 에이전트 루프), 도구 정의, 프롬프트. `agent.py`(`ChatAgent` 루프, 기존 import 경로 재수출), `assemble.py`(본문 검사·응답 보정·`Turn`), `context.py`(planContext 검사·모델 입력), `tools.py`(도구 스키마·`FinalAnswer`), `conditions.py`(발화 조건·금액 파싱), `etf.py`(검색 종목 필터), `judge.py`(종목 추천 판정 모델), `plan.py`(계획 표시값 읽기, `app.llm`을 import하지 않음), `errors.py`(`AgentError`, `ForbiddenReason`) |
| `app/rag/` | 임베딩, 검색, 입력(재질문) → 검색어 |
| `app/tools/` | 외부 호출 클라이언트(Spring 계산, 받는 쪽 거절 코드 `SpringRejectCode`) |
| `app/ingest/` | 문서 적재 CLI, 청킹 |
| `app/schemas.py` | Spring과 주고받는 모델(`ApiModel`, JSON camelCase) |

라우터는 `app/api/`(`deps.py`, 기능별 라우터), 설정·DB·보안은 `app/core/`에 둔다. **새로 만드는 라우터·공통 코드도 여기에 둔다.** `app/llm`, `app/rag`, `app/ingest`는 FastAPI나 `app/api`를 import하지 않는다(적재 CLI가 서버 없이 돈다).

## 참조 문서
- DB: `../db/tables.md` §9~14(RAG·대화 테이블), `../db/erd.md`의 `chat_message` CHECK 제약, `../db/moved-to-code.md`
- ai-server가 쓰는 테이블: `source_org`, `rag_document`, `rag_chunk`, `chunk_embedding`(적재 CLI만). `chat_message`·`chat_message_citation`은 Spring이 쓴다.

세부 규칙은 `.claude/rules/`, 반복 작업 절차는 `.claude/skills/`.

## 팀 설정
- 파일을 고치면 훅이 `ruff check --fix`를 돌린다. 작업을 끝낼 때 파이썬이 바뀌었으면 훅(`.claude/hooks/check-python.sh`)이 ruff, mypy, pytest를 돌린다. 실패하면 고친 뒤 끝낸다.
- 기능 구현 후나 PR 전에는 `ai-server-reviewer` 에이전트로 규칙 위반을 점검한다.
- 구현·진단 에이전트: `fastapi-pro`(라우터·의존성), `ai-engineer`(LLM·RAG 파이프라인), `prompt-engineer`(프롬프트 문장·도구 설명), `vector-database-engineer`(청킹·검색 품질), `postgres-pro`(SQL 진단, 읽기 전용). 검색 평가 계획은 `docs/rag-eval.md`.
- 개인 설정은 `.claude/settings.local.json`, `CLAUDE.local.md`에 둔다(git 제외).
