# moeun ai-server

FastAPI 서버. Spring만 호출한다(브라우저 노출 금지).

- `POST /chat`: 대화 설명, 조건 변경 재계산(도구 호출 → Spring `/internal/calc`), RAG 근거 인용
- `POST /rag/search`: 근거 검색(디버그·Spring용)
- `GET /health`

금액 계산은 하지 않는다(NFR-INT-01). 숫자는 Spring 계산 결과만 인용한다.

## 실행

```bash
uv sync
cp .env.example .env        # ANTHROPIC_API_KEY, OPENAI_API_KEY 채우기
uv run uvicorn app.main:app --reload --port 8000
```

## 문서 적재

```bash
uv run moeun-ingest --file docs/a.pdf --doc-type DISCLOSURE --org "OO자산운용" \
  --org-type ASSET_MANAGER --title "OO S&P500 투자설명서" --url https://... --etf-code 360750
```

같은 URL·같은 내용은 건너뛰고, 같은 URL의 새 내용은 새 행으로 넣으면서 이전 개정본을 검색에서 뺀다.

청킹 규칙을 바꾼 뒤 같은 문서를 다시 넣을 때는 `--rechunk`를 붙인다. 기존 청크 행은 과거 인용을 위해 남기고 지금 쓰는 임베딩 모델의 임베딩만 검색에서 뺀 다음, 같은 문서에 새 청크를 이어 넣는다. 이때 `--title` 같은 문서 정보는 바뀌지 않는다.

적재 끝에는 정합성 점검(비활성 문서 아래 활성 임베딩이 없는지)을 하고, 어긋나면 그 적재를 취소한다.

## Spring과의 계약

| 방향 | 엔드포인트 | 헤더 |
|---|---|---|
| Spring → ai | `POST /chat` | `X-Internal-Api-Key` |
| ai → Spring | `POST /internal/calc` `{memberId, baselineScenario, conditionChanges}` → `{calcRunId, ...결과}` | `X-Internal-Api-Key` |

`/chat` 응답의 `messageType`, `calcRunId`, `conditionChanges`, `citationChunkIds`는 `chat_message` CHECK 제약을 만족하게 보정되어 나온다.
실패 시 4xx/5xx `{"error": CODE}`를 돌려주며 Spring이 `FAILED`로 기록한다(FR-AI-04).

## 테스트

```bash
uv run pytest
uv run ruff check .
uv run mypy          # strict
```
