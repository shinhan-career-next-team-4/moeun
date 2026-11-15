# moeun ai-server

FastAPI 서버. 문서 RAG와 사용자 입력(자연어 재질문) RAG로 AI 설명의 신뢰성을 높인다. Spring만 호출한다.

## 절대 원칙
1. 금액·수익률·기간을 계산하지 않는다. 숫자는 `plan_context`나 Spring `/internal/calc` 결과만 인용한다(NFR-INT-01).
2. 호출자는 Spring뿐이다. 모든 엔드포인트(`/health` 제외)는 `InternalOnly`를 건다.
3. 인용(`citation_chunk_ids`)은 이번 요청에서 실제로 검색된 chunk_id만 넣는다.
4. 모델 출력보다 서버 사실(`_Turn`)을 믿는다. 응답은 `assemble`이 보정한다.

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
| `app/main.py` | 앱 생성, lifespan, 라우트(분리 전) |
| `app/llm/` | Claude 에이전트 루프, 도구 정의, 프롬프트 |
| `app/rag/` | 임베딩, 검색, 입력(재질문) → 검색어 |
| `app/tools/` | 외부 호출 클라이언트(Spring 계산) |
| `app/ingest/` | 문서 적재 CLI, 청킹 |
| `app/schemas.py` | Spring과 주고받는 모델(`ApiModel`, JSON camelCase) |

목표 구조(아직 이동 안 함): 라우터는 `app/api/`(`deps.py`, 기능별 라우터), 설정·DB·보안은 `app/core/`. **새로 만드는 라우터·공통 코드는 목표 위치에 둔다.** 기존 파일 이동은 별도 작업으로 한다.

## 참조 문서
- DB: `../db/tables.md` §9~14(RAG·대화 테이블), `../db/erd.md`의 `chat_message` CHECK 제약, `../db/moved-to-code.md`
- ai-server가 쓰는 테이블: `source_org`, `rag_document`, `rag_chunk`, `chunk_embedding`(적재 CLI만). `chat_message`·`chat_message_citation`은 Spring이 쓴다.

세부 규칙은 `.claude/rules/`, 반복 작업 절차는 `.claude/skills/`.

## 팀 설정
- 파일을 고치면 훅이 `ruff check --fix`를 돌린다. 작업을 끝낼 때 파이썬이 바뀌었으면 훅(`.claude/hooks/check-python.sh`)이 ruff, mypy, pytest를 돌린다. 실패하면 고친 뒤 끝낸다.
- 기능 구현 후나 PR 전에는 `ai-server-reviewer` 에이전트로 규칙 위반을 점검한다.
- 개인 설정은 `.claude/settings.local.json`, `CLAUDE.local.md`에 둔다(git 제외).
