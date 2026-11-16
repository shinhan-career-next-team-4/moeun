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
`/chat` 실패 시 4xx/5xx `{"error": CODE}`를 돌려주며 Spring이 `FAILED`로 기록한다(FR-AI-04). **예외: 요청 스키마 위반(422)은 FastAPI 기본 본문 `{"detail": [...]}`이다**(`{error: CODE}` 규칙의 예외, 코드 변경 없음).

### `/chat`의 `planContext`

전체 계약과 예시 JSON은 `docs/api/plan-context.md`, `docs/api/examples/plan-context/`가 기준이다. 두 문서가 다르면 `docs/api/plan-context.md`를 따르고 이 절을 고친다.

- `planContext`는 한 계산 기록(`calc_run(PLAN)`)의 최상위 4개 `calcRunId`·`conditions`·`result`·`display`다. `result`는 `/internal/calc`의 `current`와 같은 모양이고 **가정(`assumptions`)은 `result.assumptions`**에 있다. 키는 camelCase다. 계획이 없으면 `planContext: null`이다.
- 숫자는 Spring만 만든다. 모델은 `display` 문자열만 그대로 인용하고 **raw 금액은 모델에 넘기지 않는다**(NFR-INT-01, FR-AI-02). `memberId` 같은 계정 식별 정보는 `planContext`에 없다(D-030).
- **규칙 밖 필드는 오류로 처리하지 않고 무시하며 모델에도 넘기지 않는다**(pydantic `extra` 기본 `ignore`, 덮어쓰지 않는다). `display`는 `dict[str, Any]`로 받아 허용 키 패턴에 맞고 값이 문자열인 항목만 남긴다.

| 위치 | 필수 여부 |
|---|---|
| `result`, `display` | 필수 |
| `result.status` (`CALCULATED` \| `ALREADY_EXCEEDED`, 그 밖의 값은 422) | 필수 |
| `result.scenarios[].{scenario, isAchieved}` | 필수 (`scenarios`는 배열, 초과면 빈 배열) |
| `result.assumptions.isDefaultSavingsRate` | 필수 |
| `result.assumptions.{scenarioMethod, excludedItems}` | 키 필수, 값 null 가능 |
| `calcRunId` | 선택 (EXPLAIN에서는 일치 검사) |
| `conditions`, 그 밖의 raw 필드 | 선택 (읽지 않음) |

검사 순서(`ChatAgent.run()`):

| 순서 | 조건 | 결과 |
|---|---|---|
| 1 | 스키마 위반 | 422 FastAPI 기본 `{"detail": [...]}` |
| 2 | `EXPLAIN`인데 `planContext`가 `null` | `BAD_REQUEST` → 400 |
| 3 | `EXPLAIN`인데 `planContext.calcRunId != ChatRequest.calcRunId` | `BAD_REQUEST` → 400 |
| 4 | `EXPLAIN`이고 `result.status=ALREADY_EXCEEDED` | LLM을 부르지 않고 문구표 `chat.alreadyExceeded`로 `EXPLANATION` 응답 |
| 5 | display 요구 키 누락 | `CONTEXT_DISPLAY_MISSING` → 502 (도구 쪽 `CALC_DISPLAY_MISSING`과 쌍으로 상수화) |
| 6 | 그 밖 | 모델 호출 |

- `EXPLAIN`은 `planContext.calcRunId == ChatRequest.calcRunId`이다. `QUESTION`은 `ChatRequest.calcRunId=null`이고 `planContext.calcRunId`는 **있는 것이 정상**이며 400이 아니다.
- 요구 키: `CALCULATED`는 `goalAmount`, `calcStartMonth`, `targetMonth`, `conditions.{heldAssets, monthlySavingAmount, targetPeriodMonths, savingsRatioPct, savingsRateInputPct}`, 시나리오마다 `<S>.{afterTaxAsset, principal, afterTaxGain, gapToGoal}`, `isAchieved=true`인 `<S>.goalReachMonth`, `assumptions.taxRatePct`다. `ALREADY_EXCEEDED`는 `goalAmount`, `conditions.heldAssets`다. `<S>.goalReachMonth`는 `isAchieved=true`이고 raw 값이 있을 때만 Spring이 키를 만든다.
- 가정 문장: Spring은 `excludedItems`·`scenarioMethod` **코드만** 보내고, ai-server가 문구표(`excluded.<CODE>` 5개, `scenarioMethod.<CODE>` 2개)에서 문장(`excludedNotes`, `scenarioMethodNote`)으로 바꿔 모델에 넘긴다. 모델은 그 문장을 그대로 인용한다. 문구표 `tax.note`는 `taxNote`로 모델 입력에 항상 넣고(D-067), 응답에 `assumptions.taxRatePct` 표시값이 있으면 `tax.note`를 글자 그대로 덧붙인다(모델이 이미 넣었으면 중복하지 않는다, D-073).
- 문구표 키: `chat.noPlan`, `chat.alreadyExceeded`, `excluded.<CODE>` 5개, `scenarioMethod.<CODE>` 2개, `tax.note`, `outOfScope.tax`(문장은 `copy/copy.ko.yml`, #18).
- 모델 입력(`<plan_context>`)에는 `result.status`, `scenarios[].{scenario,isAchieved}`, `assumptions.isDefaultSavingsRate`, `excludedNotes`, `scenarioMethodNote`, `taxNote`, 필터를 통과한 `display`만 들어간다. 로그에는 `planContext` 본문을 남기지 않고 `calcRunId`와 오류 코드만 남긴다.
- 프롬프트 규칙 1~9와 담당 Issue: 1·2·3·5·6·8·9 → #35, 4 → #23, 7(D-059, "더/덜" 같은 상대 표현이면 계산하지 않고 `CLARIFY`로 절대값을 되묻는다. 예시 금액·금리를 지어내지 않는다) → #21. 규칙 8(D-060)은 세율을 `taxRatePct` display만 인용하고, 규칙 9는 초과 계획이면 초과 금액을 말하지 않는다. 순서는 #21 → #22 → #23 → #35다.
- fixture 14개(`plan-context-{basic,etf0,exceeded,achieved,zero-held,polluted,display-missing}.json`, `chat-request-{explain,question-no-plan,question-with-plan,explain-mismatch,explain-null,explain-exceeded}.json`, `model-input-basic.json`)는 `docs/api/examples/plan-context/`에 있다. `plan-context-*`는 `planContext` 객체만, `chat-request-*`는 `ChatRequest` 전체다. `model-input-basic.json`에서는 문구표로 채우는 키(`excludedNotes`, `scenarioMethodNote`, `taxNote`)를 뺐고 테스트가 `COPY`로 채워 dict로 비교한다. Spring 테스트가 읽는 파일은 basic, etf0, exceeded, achieved, zero-held이고 나머지는 ai-server 전용이다.

### `/internal/calc` 요청

- `conditionChanges`에는 **이번 발화에서 사용자가 말한 조건만** 넣는다. 허용 키는 4개(`monthlySavingAmount`, `savingsRatioPct`, `savingsRateInputPct`, `targetPeriodMonths`)이고 값이 `null`이면 "바꾸지 않음"이다.
- **기준은 항상 현재 계획이고 변경은 누적하지 않는다**(D-041). 이전 대화에서 바꾼 조건을 다음 요청에 이어 담지 않는다. 월 30만원 계획에서 `{monthlySavingAmount: 500000}` 다음에 `{targetPeriodMonths: 60}`을 보내면 두 번째 비교는 월 30만원 + 60개월이다.
- `baselineScenario`는 선택이고 Spring은 값을 보지 않는다. 기준 시나리오는 Spring의 `plan.baseline_scenario`다.
- 범위(최소·최대)는 Spring 한 곳에서만 검사한다. 범위 수치를 코드·프롬프트에 두지 않는다.

### `/internal/calc` 응답

- 금액·기간·비율은 `display` 문자열만 그대로 인용한다. raw 숫자(`afterTaxAsset`, `diff.*Delta` 등)는 달성 여부·시나리오 수 같은 판단용이며 모델이 "만원" 문자열을 만들거나 빼기·나누기를 하지 않는다(NFR-INT-01).
- `display` 키 규칙: 응답에 있는 시나리오만 키가 있다(ETF 0%면 `AVG`만). `goalReachMonth`가 `null`이면 그 키가 없다. 차이(`diff.*`)는 `약 N만원 증가`/`약 N만원 감소`, 목표 대비(`gapToGoal`)는 부호 + 라벨(`-약 288만원 미달`, `+약 501만원 달성`, 반올림해 0만원이면 `-1만원 미만 미달`/`+1만원 미만 달성`)이다. 경계 형식은 `변화 없음`, `1만원 미만 증가/감소`, `0원 달성`, 1억 이상 `약 12,345만원`이다(D-055). 일반 금액 경계는 `0원`, `1만원 미만`, 음수 `-약 N만원`(0만원으로 반올림되면 `-1만원 미만`)이다(D-061).
- `status`가 항상 `CALCULATED`인 것은 `/internal/calc` 응답 한정이다. planContext의 `result`에는 `ALREADY_EXCEEDED`가 올 수 있고 `assumptions.excludedItems`는 planContext 경로에서 쓴다. 두 계약에 같은 pydantic 모델을 쓰면 `status`를 `Literal['CALCULATED']`로 만들지 않고 `excludedItems`를 지우지 않는다.
- 200이면 `calcRunId`가 정수인지 검사한다. 모델에 넘기는 도구 결과는 추린 것만이다: `display`, `diff`, `conditions`, `current`·`changed`의 `scenarios[].{scenario,isAchieved}`·`baselineScenario`, `assumptions.isDefaultSavingsRate`.
- 두 `display` 값을 빼서 차이를 만들지 않고 차이는 `diff.*` 키만 인용한다. 달성 시점 두 달 사이의 개월 수는 말하지 않는다.
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

목표 금액 변경은 두 겹으로 막는다(도구 스키마·프롬프트, Spring `FIELD_NOT_CHANGEABLE`). 두 경로의 사용자 결과(같은 문장, 같은 `messageType`, 비교 없음)는 같아야 한다. `VALIDATION_FAILED`인데 `minDisplay`·`maxDisplay`가 없으면(계약 위반) 숫자 없이 "허용 범위를 벗어났어요"라고만 말하고 다시 묻는다.

타임아웃·연결 실패는 `AgentError("CALC_UNAVAILABLE")` → `FAILED`. 422·409 본문은 문자열로 자르지 않고 `CalcRejected(code, errors)`로 파싱한다. 목·테스트의 응답 예시는 `docs/api/examples/internal-calc/`의 파일을 그대로 쓴다.

## 테스트

```bash
uv run pytest
uv run ruff check .
uv run mypy          # strict
```
