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

전체 계약과 예시 JSON(fixture)은 `docs/api/internal-calc.md`, `docs/api/examples/internal-calc/`가 기준이다. 아래는 ai-server 쪽에서 알아야 할 요약이다. 두 문서가 다르면 `docs/api/internal-calc.md`를 따르고 이 절을 고친다.

| 방향 | 엔드포인트 | 헤더 |
|---|---|---|
| Spring → ai | `POST /chat` | `X-Internal-Api-Key` |
| ai → Spring | `POST /internal/calc` `{memberId, conditionChanges}` → `{calcRunId, conditions, current, changed, diff, display}` | `X-Internal-Api-Key` |

`/chat` 응답의 `messageType`, `calcRunId`, `conditionChanges`, `citationChunkIds`는 `chat_message` CHECK 제약을 만족하게 보정되어 나온다.
실패 시 4xx/5xx `{"error": CODE}`를 돌려주며 Spring이 `FAILED`로 기록한다(FR-AI-04).

### `/internal/calc` 요청

- `conditionChanges`에는 **이번 발화에서 사용자가 말한 조건만** 넣는다. 허용 키는 4개(`monthlySavingAmount`, `savingsRatioPct`, `savingsRateInputPct`, `targetPeriodMonths`)이고 값이 `null`이면 "바꾸지 않음"이다.
- **기준은 항상 현재 계획이고 변경은 누적하지 않는다**(D-041). 이전 대화에서 바꾼 조건을 다음 요청에 이어 담지 않는다. 월 30만원 계획에서 `{monthlySavingAmount: 500000}` 다음에 `{targetPeriodMonths: 60}`을 보내면 두 번째 비교는 월 30만원 + 60개월이다.
- `baselineScenario`는 선택이고 Spring은 값을 보지 않는다. 기준 시나리오는 Spring의 `plan.baseline_scenario`다.
- 범위(최소·최대)는 Spring 한 곳에서만 검사한다. 범위 수치를 코드·프롬프트에 두지 않는다.

### `/internal/calc` 응답

- 금액·기간·비율은 `display` 문자열만 그대로 인용한다. raw 숫자(`afterTaxAsset`, `diff.*Delta` 등)는 달성 여부·시나리오 수 같은 판단용이며 모델이 "만원" 문자열을 만들거나 빼기·나누기를 하지 않는다(NFR-INT-01).
- `display` 키 규칙: 응답에 있는 시나리오만 키가 있다(ETF 0%면 `AVG`만). `goalReachMonth`가 `null`이면 그 키가 없다. 차이(`diff.*`)는 `약 N만원 증가`/`약 N만원 감소`, 목표 대비(`gapToGoal`)는 부호 + 라벨(`-약 288만원 미달`, `+약 501만원 달성`)이다. 경계 형식은 `변화 없음`, `1만원 미만 증가/감소`, `0원 달성`, 1억 이상 `약 12,345만원`이다(D-055). 일반 금액 경계는 `0원`, `1만원 미만`, 음수 `-약 N만원`(0만원으로 반올림되면 `-1만원 미만`)이다(D-061).
- `status`가 항상 `CALCULATED`인 것은 `/internal/calc` 응답 한정이다. planContext의 `result`에는 `ALREADY_EXCEEDED`와 `assumptions.excludedItems`가 올 수 있으므로 두 계약에 같은 pydantic 모델을 쓰면 `status`를 `Literal['CALCULATED']`로 만들지 않는다.
- **[전환기]** `display`가 비어 있거나 응답에 있는 시나리오의 `*.afterTaxAsset` 키가 하나라도 없으면 비교를 내지 않고 `AgentError("CALC_DISPLAY_MISSING")`으로 실패시킨다(Spring `FAILED`).
- 성공 1건당 Spring이 `calc_run(CHAT)` 1행을 만들고 그 id가 `calcRunId`다. 실패하면 기록이 없고, `chat_message`가 참조하지 않는 행이 남아도 정리하지 않는다(D-057).

### 오류 코드와 ai-server 동작

오류는 `application/problem+json`이고 `code`, `errors[]`로 판단한다(`title`은 쓰지 않는다).

| HTTP | `code` | ai-server 동작 |
|---|---|---|
| 422 | `VALIDATION_FAILED` | **모델에 `{code, errors}`만** 넘겨 `CLARIFY`로 되묻는다. `errors[].minDisplay`·`maxDisplay`를 인용한다(원 → 만원 나눗셈 금지). 같은 요청 안에서 `recalculate`를 다시 부르지 않는다 |
| 422 | `FIELD_NOT_CHANGEABLE` | 허용 키가 섞여도 요청 전체가 거절된다. 모델에 넘기지 않고 문구표 고정 문장으로 답하며 `messageType=ANSWER`(D-056) |
| 409 | `NO_PLAN` | 모델에 넘기지 않고 문구표 `chat.noPlan` 고정 문장, `ANSWER` |
| 409 | `ALREADY_EXCEEDED` | 모델에 넘기지 않고 문구표 `chat.alreadyExceeded` 고정 문장, `ANSWER` |
| 400 | `MALFORMED_REQUEST` | `AgentError("CALC_BAD_REQUEST")` → Spring `FAILED` |
| 401 | `INVALID_INTERNAL_KEY` | `AgentError("CALC_UNAUTHORIZED")` → `FAILED` |
| 500 | `CALC_FAILED` | `AgentError("CALC_FAILED")` → `FAILED` |

타임아웃·연결 실패는 `AgentError("CALC_UNAVAILABLE")` → `FAILED`. 422·409 본문은 문자열로 자르지 않고 `CalcRejected(code, errors)`로 파싱한다. 목·테스트의 응답 예시는 `docs/api/examples/internal-calc/`의 파일을 그대로 쓴다.

## 테스트

```bash
uv run pytest
uv run ruff check .
uv run mypy          # strict
```
