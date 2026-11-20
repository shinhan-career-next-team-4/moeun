# `planContext` 계약 (Spring → ai-server `POST /chat`)

> 최종본 (2026-11-16, moeun #11, 요구사항 FR-AI-01·02·04·06·09·10, FR-RES-01·06, NFR-INT-01·03, CP-04·05, 결정 D-027·D-030·D-040·D-041·D-045·D-055·D-058~D-062·D-065·D-067·D-071·D-073·D-090·D-092).
> 협상 기록과 원문은 기획 repo project-workspace `docs/design/contracts/plan-context.md`(합의 55항목, 쟁점 X-1~X-4 → D-059~D-062)다. 이 문서가 구현 기준이며 `ai-server/README.md` "planContext" 절은 이 문서의 요약이다.
> 예시 JSON은 [`docs/api/examples/plan-context/`](examples/plan-context/)에 파일로 둔다. Spring 테스트와 ai-server 테스트가 같은 파일을 읽는다(C1a-13).
> **[D-059]~[D-062]**는 협상 쟁점이었다가 사람이 결정한 항목이다. 항목 ID(C1a-x)는 기획 repo 문서와 같다. `/internal/calc` 계약은 [`internal-calc.md`](internal-calc.md)다.

## 0. 이 계약의 원칙

[`internal-calc.md`](internal-calc.md) §0을 그대로 따른다. 이 계약에서 중요한 것만 다시 적는다.

1. **숫자는 Spring만 만든다.** 모델이 말하는 금액·기간·시점·비율은 `display` 문자열을 그대로 인용한 것뿐이다(NFR-INT-01, FR-AI-02). **raw 금액은 모델에 넘기지 않는다**(C1a-8). 왜: 모델 입력에 원 단위 숫자가 없으면 반올림·뺄셈할 재료가 없다.
2. **JSON 키는 camelCase**, 금액은 원 정수, 기간은 개월 정수, 퍼센트는 숫자(`3.12` = 3.12%).
3. **null은 키를 빼지 않는다.** 단 `display`는 값이 없으면 키를 만들지 않는다(C2-6과 같음).
4. **받는 쪽은 모르는 키를 무시한다.** 이 계약에서 "무시" = **오류로 처리하지 않고 모델에도 넘기지 않는다.** (pydantic 기본 `extra="ignore"`)
5. **계정 식별 정보는 넣지 않는다**(D-030).
6. **새 라이브러리 없음.** Spring은 Jackson 기본, ai-server는 기존 pydantic `ApiModel`.

---

## C1a-1. 목적·범위

| 항목 | 값 |
|---|---|
| 무엇 | Spring → ai-server `POST /chat` 요청의 `planContext` 필드(`ChatRequest.plan_context`) |
| 뜻 | **한 계산 기록(`calc_run(PLAN)`)의 조건·결과·표시 문자열·가정**. 모델이 "지금 계획"을 말할 때 인용하는 유일한 숫자 출처 |
| 쓰는 곳 | `mode=EXPLAIN`(FR-AI-01·02), `mode=QUESTION`(FR-AI-06·09) |
| 쓰지 않는 곳 | 대화 재계산의 비교·차이는 `/internal/calc`(C2-3~C2-6)가 맡는다 |
| 담당 | 만드는 쪽 Spring `calc` 모듈(A팀, C1a-10). 쓰는 쪽 ai-server와 Spring `chat`(B팀). 계약은 A·B 공동 |

## C1a-2. 최상위 구조

```json
{
  "calcRunId": 540,
  "conditions": { "...": "C1a-3" },
  "result": { "...": "C1a-4 (= /internal/calc의 current와 같은 모양)" },
  "display": { "...": "C1a-6" }
}
```

| 필드 | 타입 | Spring이 항상 보냄 | ai-server 필수 여부(C1a-8) | 뜻 |
|---|---|---|---|---|
| `calcRunId` | integer | 예 | 선택(EXPLAIN에서는 일치 검사) | 이 컨텍스트를 계산한 `calc_run(PLAN)` id |
| `conditions` | object | 예 | 선택(읽지 않음) | 계산에 실제 쓴 조건(C1a-3) |
| `result` | object | 예 | **필수** | 계산 결과(C1a-4). 가정은 `result.assumptions`(C1a-5) |
| `display` | object | 예 | **필수** | Spring이 만든 표시 문자열(C1a-6) |

- 계획이 없으면 `"planContext": null`(C1a-11).
- **가정(`assumptions`)은 최상위가 아니라 `result` 안(`result.assumptions`)에 있다.** 왜: `CalcResult.assumptions`가 이미 결과 안에 있어 최상위로 꺼내면 DTO가 하나 는다.
- `result`는 `/internal/calc`의 `current`와 같은 DTO를 쓰고, 그 DTO는 **calc 루트 공개 타입** `calc.CalcResultView`, `calc.ScenarioResultView`다. 왜: `PlanContextView`(calc 루트, 공개 API)가 calc 하위 패키지(비공개) 타입을 노출하면 chat이 참조하는 순간 `ModularityTests`가 깨진다(`modulith.md`).
- `calcRunId` 최상위 유지. 왜: EXPLAIN 일치 검사와, QUESTION에서 어떤 기록을 기준으로 답했는지 로그로 남길 유일한 값.
- 이 계약의 `result`는 `internal-calc.md` C2-4와 두 곳이 다르다: `status`에 `ALREADY_EXCEEDED`가 올 수 있고, `assumptions.excludedItems`를 소비자가 쓴다. ai-server가 두 계약에 같은 pydantic 모델을 쓰면 `status`를 `Literal["CALCULATED"]`로 만들지 않는다.

## C1a-3. `conditions`

계산에 **실제로 쓴 값**. 6개 키가 항상 모두 있다.

| 키 | JSON 타입 | 단위 | 출처 | display 키 |
|---|---|---|---|---|
| `goalAmount` | integer | KRW | `CalcInput.goalAmount` | `goalAmount` |
| `heldAssets` | integer | KRW | `CalcInput.heldAssets` | `conditions.heldAssets` |
| `monthlySavingAmount` | integer | KRW | `CalcInput.monthlySavingAmount` | `conditions.monthlySavingAmount` |
| `targetPeriodMonths` | integer | MONTH | `CalcInput.targetPeriodMonths` | `conditions.targetPeriodMonths` |
| `savingsRatioPct` | integer | PCT | `CalcInput.savingsRatioPct`(해석된 비중) | `conditions.savingsRatioPct` |
| `savingsRateInputPct` | number | PCT(연, 세전) | `CalcInput.savingsRatePct`(실제 쓴 금리) | `conditions.savingsRateInputPct` |

- 출처는 `CalcInput`(ConditionResolver 결과) 하나. 왜: `plan_version.savings_rate_input_pct`는 NULL일 수 있어 raw 컬럼을 읽으면 틀린다. `/internal/calc`의 `conditions.current`(4키)와 같은 매퍼를 쓴다.
- `CalcInput`의 `monthlySavingAmount`·`targetPeriodMonths`는 nullable 타입이다. 해커톤에서는 항상 값이 있지만(D-045) DTO는 nullable 그대로 두고 null이면 `null`로 보낸다(원칙 3).
- **와이어에는 남기고 모델에는 넘기지 않는다.** ai-server `PlanContext`는 이 객체를 **선택 필드**로 둔다. 왜 남기나: 제공자 비용이 매퍼 하나이고 로그·fixture 대조에 쓰인다.

## C1a-4. `result`

`CalcResult`를 JSON으로 바꾼 것. `internal-calc.md` C2-4의 모양·직렬화 표를 그대로 쓴다. 다른 점만 적는다.

| 필드 | 이 계약에서의 규칙 |
|---|---|
| `status` | `CALCULATED` 또는 `ALREADY_EXCEEDED`. 초과면 `scenarios: []`(C1a-11) |
| `goalAmount`, `calcStartMonth`, `targetMonth` | C2-4와 같다(`"YYYY-MM"`) |
| `baselineScenario` | EXPLAIN: chat이 F-3 때 `chat_message.baseline_scenario`에 저장한 값. QUESTION: `plan.baseline_scenario`(C1a-10). 어느 경우든 `ChatRequest.baselineScenario`와 같다 |
| `scenarios[]` | **항상 GOOD·AVG·BAD 순서**. ETF 포함 3개, ETF 0% AVG 1개, 초과 0개. `monthlyPoints` **키 자체가 없다** |
| `scenarios[].goalReachMonth` | raw는 엔진 값 그대로(`"YYYY-MM"` \| null). 엔진 정의상 "처음 목표 이상이 된 달"이라 `isAchieved=false`여도 non-null일 수 있다(중간에 넘었다가 목표월에 내려온 경우). raw는 모델에 넘기지 않으므로 무해하고, display 키 규칙은 C1a-6 |
| `requiredMonthlySaving` | 해커톤에서 항상 `null`(D-045) |
| `assumptions` | C1a-5 |
| `dataVersions` | 보낸다. 모델에 넘기지 않는다 |

- `gapToGoal = afterTaxAsset − goalAmount`, `afterTaxGain = afterTaxAsset − principal`, 0원은 달성(CP-01). 모두 Spring 값.
- 달성/미달은 raw `isAchieved`로 판단하고 display 라벨도 같은 판정을 따른다. 라벨과 `isAchieved`가 어긋나지 않는지는 **Spring 빌더 단위 테스트(G-5a)**에서 잡고 ai-server fixture에는 두지 않는다.
- `baselineScenario`는 모델 입력(`<plan_context>`)에서 뺀다. 왜: `build_messages`가 이미 `<baseline_scenario>` 태그로 넣는다. 와이어에는 남는다.
- **엔진 불변식(G-17, #27)**: `isAchieved=true`인 시나리오는 `goalReachMonth`가 null이 아니고 `targetMonth` 이하다. 왜: ai-server가 이 키를 요구 키로 검사하므로(C1a-8) 어기면 설명이 502로 실패한다.

## C1a-5. `assumptions` (`result.assumptions`)

`calc.Assumptions` 그대로. 가정 보기(FR-RES-06)와 같은 원천이다.

| 필드 | 타입 | 모델에 넘김 | display 키 | 비고 |
|---|---|---|---|---|
| `savingsRatePct` | number | 아니오 | (`conditions.savingsRateInputPct`) | |
| `isDefaultSavingsRate` | boolean | 예 | — | 기본금리 사용 여부 판단용 |
| `savingsRateBaseMonth` | `"YYYY-MM"` \| null | 아니오 | `assumptions.savingsRateBaseMonth` | null이면 display 키 없음 |
| `savingsRatioPct` | integer | 아니오 | (`conditions.savingsRatioPct`) | |
| `taxRatePct` | number | 아니오 | **[D-060]** `assumptions.taxRatePct` | raw는 모델에 넘기지 않는다. **#150부터 적금 이자 세율**(ETF 차익은 `etfGainTaxRatePct`). 규칙 8·세금 응답 보장의 기준을 종목 세율까지 넓히는 일은 B팀 합의 대기 |
| `scenarioMethod` | `CAGR` \| `ROLLING_DISTRIBUTION` \| null | **코드는 아니오. 문구로 바꿔서 넘김** | — | ai-server가 문구표 `scenarioMethod.<CODE>` 문장으로 바꿔 `scenarioMethodNote`로 넣는다. null(ETF 0%)이면 넣지 않음 |
| `sp500BaseDate` | `"YYYY-MM-DD"` \| null | 아니오 | `assumptions.sp500BaseDate` | null이면 display 키 없음. 이름은 그대로지만 값은 계산에 쓴 ETF 종목 데이터의 기준일(#138) |
| `etfProductCode` | string \| null | 아니오 | — | **추가(#150)** 계산에 쓴 ETF 종목 코드. ETF 0%면 null |
| `etfProductName` | string \| null | 아니오 | `assumptions.etfProductName`(합의 대기) | **추가(#150)** 종목 화면 이름("코스피200 추종 ETF", CP-06). ETF 0%면 null |
| `etfGainTaxRatePct` | number \| null | 아니오 | `assumptions.etfGainTaxRatePct`(합의 대기) | **추가(#150)** 종목 매매차익 세율. 국내 주식형 0. `taxRatePct`는 적금 이자 세율이 된다 |
| `excludedItems` | string[] | **코드는 아니오. 문구로 바꿔서 넘김** | — | ai-server가 문구표 `excluded.<CODE>` 문장 배열로 바꿔 `excludedNotes`로 넣는다 |

**반영하지 않은 항목·3구간 산정 방식 문장**
- **Spring은 `excludedItems`·`scenarioMethod` 코드만 보낸다.** 왜: planContext에 문구표 의존이 생기지 않고 display와 성격이 다른 맵이 늘지 않는다.
- ai-server가 코드 → 문구표 문장(`excludedNotes`, `scenarioMethodNote`)으로 바꿔 모델에 넣고, **모델은 그 문장을 글자 그대로 인용한다.** 왜: D-027 "환율 변동은 반영하지 않았어요"가 AI 문장에서도 화면과 같아야 하고, 코드의 뜻을 모델이 지어내면 안 된다. CP-05(프론트와 AI 서버가 문구표를 함께 읽음)를 그대로 이행하는 것이다.
- 문구표 출처: #18이 만든 `ai-server/app/core/copy.py` 로더가 `copy/copy.ko.yml`을 읽고, #35가 그 뒤에 온다. 테스트: "`ExcludedItem`·`ScenarioMethod` enum의 모든 코드에 문장이 있다".
- 모르는 코드(문장 없음)는 **버리고 경고 로그**를 남긴다(모델에 코드를 넘기지 않음).
- `scenarioMethod.<CODE>` 문장 내용은 TBD-01(3구간 산정 방식, 미결)을 따른다. #18의 "없는 키를 읽으면 실패" 규칙 때문에 TBD-01이 끝나기 전에도 **임시 문장을 넣는다**(빈 값 금지).
- `ExcludedItem`·`ScenarioMethod` enum 값을 늘리면 #18 문구표도 같이 고친다.

## C1a-6. `display` 키·형식 — C2-6 재사용

`internal-calc.md` **C2-6의 키 규칙·형식·경계 규칙(D-055)을 그대로 쓴다.** 비교가 없으므로 `<side>.` 접두어를 뺀다. C2-6의 기존 형식만 쓰는 **추가 키 4개**(`calcStartMonth`, `conditions.heldAssets`, `assumptions.savingsRateBaseMonth`, `assumptions.sp500BaseDate`)와 세율 `assumptions.taxRatePct`를 더한다.

| 키 | 형식 | C2-6 대응 |
|---|---|---|
| `goalAmount` | `약 N만원` | `goalAmount` |
| `calcStartMonth` | `YYYY년 M월` | **추가** |
| `targetMonth` | `YYYY년 M월` | `<side>.targetMonth` |
| `<S>.afterTaxAsset`, `<S>.principal`, `<S>.afterTaxGain` | `약 N만원` | `<side>.<S>.*` |
| `<S>.gapToGoal` | [D-055] `-약 288만원 미달`, `+약 501만원 달성` | `<side>.<S>.gapToGoal` |
| `<S>.goalReachMonth` | `YYYY년 M월`. **`isAchieved=true`이고 raw 값이 non-null일 때만 키를 만든다.** 그 밖(미달이면 raw가 non-null이어도, 또는 null)은 키 없음 | `<side>.<S>.goalReachMonth` |
| `conditions.heldAssets` | `약 N만원` | **추가** |
| `conditions.monthlySavingAmount` | `약 N만원` | `conditions.<side>.monthlySavingAmount` |
| `conditions.targetPeriodMonths` | `n년 n개월` | `conditions.<side>.targetPeriodMonths` |
| `conditions.savingsRatioPct` | `적금 a : ETF b` | `conditions.<side>.savingsRatioPct` |
| `conditions.savingsRateInputPct` | `연 N.NN%` | `conditions.<side>.savingsRateInputPct` |
| `assumptions.savingsRateBaseMonth` | `YYYY년 M월`. null이면 키 없음 | **추가** |
| `assumptions.sp500BaseDate` | `YYYY년 M월`(일은 버림). null이면 키 없음 | **추가** |
| `assumptions.taxRatePct` | **[D-060]** `N.N%`, 끝 0 제거(`15.4`·`15.40` → `15.4%`) | 새 형식 |
| `assumptions.etfProductName` | 종목 화면 이름 그대로. ETF 0%면 키 없음 | **추가(#150), B팀 합의 대기**: Spring은 만들지만 아직 허용 패턴·요구 키에 없어 ai-server가 버린다 |
| `assumptions.etfGainTaxRatePct` | `N.N%`. 비과세면 `0%(국내 주식형 ETF 매매차익 비과세)`(문구표 `tax.etfTaxFree`). ETF 0%면 키 없음 | **추가(#150), B팀 합의 대기** |

`<S>` = `GOOD` \| `AVG` \| `BAD`(시나리오 이름). 실제로 있는 시나리오만 키를 만든다.

**`<S>.goalReachMonth` 방출 규칙을 좁힌 이유**: 엔진의 `goalReachMonth`는 "처음 목표 이상이 된 달", `isAchieved`는 "목표월 값 기준"이라 뜻이 다르다. BAD 시나리오가 중간에 목표를 넘었다가 목표월에 내려오면 `BAD.gapToGoal: "-약 38만원 미달"`과 `BAD.goalReachMonth: "2029년 5월"`이 함께 나가 모델이 "미달인데 5월에 도달"이라는 모순 문장을 만든다. 빌더가 미달이면 키를 만들지 않으면 프롬프트 규칙 3이 그대로 참이 된다. ai-server는 이 값을 보정하지 않는다(계산 금지). 테스트: G-5a "중간에 닿았다가 목표월에 내려온 BAD(`isAchieved=false`, raw `goalReachMonth` non-null) → `BAD.goalReachMonth` 키 없음" 1건.

### 경계 형식

| 경우 | 형식 | 근거 |
|---|---|---|
| 목표 대비 정확히 0원 | `0원 달성` | D-055 |
| 목표 대비가 0원은 아니지만 0만원으로 반올림 | `-1만원 미만 미달` / `+1만원 미만 달성` | D-055, D-065 |
| 1억 이상(모든 금액) | `약 12,345만원` | D-055, D-065 |
| **일반 금액**(`heldAssets`, `afterTaxGain` 등)이 정확히 0원 | `0원` | D-061 |
| **일반 금액**이 0원이 아니지만 0만원으로 반올림 | `1만원 미만` | D-061 |
| **일반 금액**이 음수(`afterTaxGain` 손실) | `-약 N만원`. 0만원으로 반올림되면 `-1만원 미만` | D-061. BAD 단기 계획에서 실제로 나오는 값이고 목표 대비(`-약 288만원 미달`)와 부호 위치가 같다 |

### 만드는 곳: display 맵 빌더 하나

- `calc.application.ResultDisplayBuilder` 하나를 결과 화면(#30), `/internal/calc`(#32), planContext(G-7)가 같이 쓴다. 입력: `CalcInput` + `CalcResult` + **결과 접두어**(`""` / `"current."` / `"changed."`) + **조건 접두어**(`"conditions."` / `"conditions.current."` / `"conditions.changed."`). 왜: 같은 값을 세 곳에서 만들면 "화면 숫자 = AI 숫자 글자까지"(PR-04)를 코드로 보장할 수 없다. 계약 키는 그대로다.
- 빌더와 빌더 단위 테스트(G-5a)는 #28에서 만든다(#28 범위 "Formatter + 공유 display 맵 빌더". A팀이 어렵다고 판단하면 #29에 넣고 #29 선행에 #28을 더한다).
- **빌더 불변식(G-5a 테스트)**: `CALCULATED`면 C1a-8 요구 키 표의 키가 모두 있다. `ALREADY_EXCEEDED`면 `goalAmount`·`conditions.heldAssets`가 있다. 이 테스트가 계약 표와 빌더를 묶는다.
- `Formatter`(#28)는 형식만 맡는다. 추가 키 4개는 기존 두 형식(금액, 시점)만 써서 Formatter 변경이 없다.
- 반올림은 TBD-16을 따르고 `Formatter` 한 곳에서 한다. 문구 템플릿(`달성`, `미달`, `적금 a : ETF b`)은 문구표(CP-05, #18).

### 모델 규칙에 쓰이는 display 해석

- **`<S>.goalReachMonth` 키가 없을 때**: 그 시나리오는 미달이거나 계약 위반이다. 모델은 `isAchieved=false`일 때 "기간 안에 도달하지 않아요"라고 말한다. `isAchieved=true`인데 키가 없으면 C1a-8 요구 키 검사에서 502로 실패한다(엔진 불변식 G-17이 이 경우를 막는다).
- **`goalAmount` 키 이름 겹침**: `planContext.display.goalAmount`와 `/internal/calc` `display.goalAmount`는 이름이 같다. 목표 금액은 입력값이라 항상 같은 값이므로 허용한다. 그 밖의 키는 접두어로 갈린다. **비교·차이는 도구 결과의 접두어 키만 인용한다**(프롬프트 규칙 4).
- **display가 없는 값**(모델이 말하지 않음): `annualReturnPct`, `dataVersions`, `requiredMonthlySaving`, 초과 금액(`heldAssets − goalAmount`).

## C1a-7. 넣지 않는 필드 (D-030)

| 필드 | 이유 |
|---|---|
| `memberId`, `login_id`, 비밀번호 해시, JWT 등 | D-030. `memberId`는 `ChatRequest` 최상위에만 있고 프롬프트에 넣지 않는다(테스트로 고정) |
| `plan_version.id`, `plan.revision`, `plan.previous_version_id` | 설명에 쓰이지 않음 |
| `goalCustomName` | 자유 글이라 개인정보·프롬프트 주입 경로가 될 수 있다 |
| `goalType` | `CalcInput`에 없어 넣으려면 plan 조회·DTO가 늘고, 소비자 요구도 없다 |
| `existingMonthlyContribution` | FR-INP-08 Won't(D-045) |
| `scenarios[].monthlyPoints` | 토큰 낭비, 설명에 불필요 |
| 대화 기록, 다른 계산 기록 | `history`는 `ChatRequest`의 별도 필드 |

- "계정 식별 정보 없음"은 `planContext` 객체 안과 모델 입력에 한정이다. `chat-request-*.json`의 최상위 `memberId`는 `ChatRequest` 필드이고, `plan-context-polluted.json`의 `loginId`·`result.memberId`는 ai-server가 무시하는지 보려고 일부러 넣은 값이다.
- Spring 쪽은 `PlanContextView`에 계정 필드가 아예 없어 타입으로 보장된다. 직렬화 결과에 `memberId` 키가 없는지 G-5b에서 확인한다.

## C1a-8. ai-server 처리

### 받기 (pydantic `PlanContext`)

- `PlanContext`와 하위 모델은 `ApiModel`을 상속하고 **`extra`를 덮어쓰지 않는다(pydantic 기본 `ignore`).** 규칙 밖 필드가 와도 오류로 처리하지 않고 무시하며 모델에도 넘기지 않는다. 시나리오 이름은 기존 `Scenario` enum 재사용.
- **필수/선택 목록**:

| 위치 | 필수 | 비고 |
|---|---|---|
| `result` | 필수 | |
| `result.status` | 필수 | `CALCULATED` \| `ALREADY_EXCEEDED` 외의 값 → 422 |
| `result.scenarios` | 필수 | 배열(빈 배열 가능) |
| `result.scenarios[].scenario`, `.isAchieved` | 필수 | |
| `result.assumptions.isDefaultSavingsRate` | 필수 | |
| `result.assumptions.scenarioMethod`, `.excludedItems` | 키 필수, 값 null 가능 | |
| `display` | 필수 | JSON 객체가 아니면 422. 값 타입은 아래 필터에서 처리 |
| `calcRunId` | 선택 | EXPLAIN에서는 아래 검사로 사실상 필수 |
| `conditions`, 그 밖의 raw 필드 | 선택(선언하지 않아도 됨) | 이름이 바뀌어도 모델 입력이 깨지지 않음 |

  왜: 모델이 쓰는 필드가 사라지면 즉시 422로 드러나고, 모델이 안 쓰는 raw 필드 변경에는 깨지지 않는다.
- **display 필터**: `display`는 `dict[str, Any]`로 받고, **키가 허용 패턴에 맞고 값이 문자열인 항목만** 남긴다. 나머지는 조용히 버린다. 값 내용 필터는 두지 않는다.
- 허용 패턴(ai-server 상수 하나, `re.fullmatch`):
  ```
  (goalAmount|calcStartMonth|targetMonth|(GOOD|AVG|BAD)\.(afterTaxAsset|principal|afterTaxGain|gapToGoal|goalReachMonth)|conditions\.(heldAssets|monthlySavingAmount|targetPeriodMonths|savingsRatioPct|savingsRateInputPct)|assumptions\.(savingsRateBaseMonth|sp500BaseDate|taxRatePct))
  ```
  테스트: `AVG.afterTaxAsset` 통과, `loginId`·`current.AVG.afterTaxAsset`(비교 키) 차단.

### 검사 (`ChatAgent.run()`의 기존 mode 분기에 추가)

| 순서 | 조건 | 결과 |
|---|---|---|
| 1 | 스키마 위반(필수 누락, 타입, enum) | FastAPI 기본 **422** `{"detail": [...]}` |
| 2 | `mode=EXPLAIN`이고 `planContext`가 `null` | `AgentError("BAD_REQUEST")` → **400**. 왜: 모델이 "없음"을 받고 설명을 지어내는 것을 막는다 |
| 3 | `mode=EXPLAIN`이고 `planContext.calcRunId != ChatRequest.calcRunId` | `AgentError("BAD_REQUEST")` → **400**. 왜: 다른 계산을 설명하면 FR-AI-02 대조가 깨진다 |
| 4 | `mode=EXPLAIN`이고 `result.status=ALREADY_EXCEEDED` | **LLM을 부르지 않고** 문구표 `chat.alreadyExceeded` 문장으로 `EXPLANATION` 응답(`calcRunId` 그대로). [D-062] Spring은 초과 계획에서 설명 요청을 보내지 않지만(#54·#49), 이 검사는 방어로 남긴다 |
| 5 | display 요구 키 누락(아래) | `AgentError("CONTEXT_DISPLAY_MISSING")` → **502** |
| 6 | 그 밖 | 모델 호출 |

- 400·422·502 모두 Spring은 `FAILED`로 기록한다(FR-AI-04, #41 기준과 같아 Spring 추가 분기 없음).
- **422 본문은 FastAPI 기본을 그대로 둔다**(G-6). `ai-server/README.md`의 "실패는 `{"error": CODE}`" 규칙에는 "요청 검증 실패(422)는 FastAPI 기본 본문 `{detail: [...]}`" 예외가 있다.
- **`planContext` 본문을 로그에 남기지 않는다.** 422 `detail`에 값이 에코될 수 있으므로 로그에는 `calcRunId`와 오류 코드만.
- `CONTEXT_DISPLAY_MISSING`은 도구 쪽 `CALC_DISPLAY_MISSING`과 쌍으로 상수화한다.

**display 요구 키**(필터를 거친 뒤 기준):

| `status` | 반드시 있어야 하는 키 |
|---|---|
| `CALCULATED` | `goalAmount`, `calcStartMonth`, `targetMonth`, `conditions.{heldAssets, monthlySavingAmount, targetPeriodMonths, savingsRatioPct, savingsRateInputPct}`, `result.scenarios`의 각 `<S>`에 대해 `<S>.{afterTaxAsset, principal, afterTaxGain, gapToGoal}`, 그리고 `isAchieved=true`인 `<S>`의 `<S>.goalReachMonth`, **[D-060]** `assumptions.taxRatePct` |
| `ALREADY_EXCEEDED` | `goalAmount`, `conditions.heldAssets` |
| 선택(없어도 됨) | `assumptions.savingsRateBaseMonth`, `assumptions.sp500BaseDate`. (`isAchieved=false`인 `<S>.goalReachMonth`는 빌더가 만들지 않는다. 와도 검사는 하지 않는다) |

왜 전체인가: `<S>.afterTaxAsset`만 보면 `gapToGoal`이 빠졌을 때 모델 앞에 `goalAmount`와 `afterTaxAsset`만 남아 뺄셈 유혹이 생긴다.

### 모델에 넣는 것 (`<plan_context>`)

| 위치 | 모델에 넘김 |
|---|---|
| `calcRunId`, `conditions.*`, `dataVersions`, `requiredMonthlySaving` | 아니오 |
| `result.status` | 예 |
| `result.baselineScenario` | **아니오**(`<baseline_scenario>` 태그와 중복) |
| `result.scenarios[].scenario`, `.isAchieved` | 예 |
| `result.scenarios[]`의 금액·수익률·시점 raw | 아니오 |
| `result.goalAmount`, `calcStartMonth`, `targetMonth` raw | 아니오 |
| `result.assumptions.isDefaultSavingsRate` | 예 |
| `result.assumptions.scenarioMethod` | 코드 대신 `scenarioMethodNote`(문구표 문장, null이면 키 없음) |
| `result.assumptions.excludedItems` | 코드 대신 `excludedNotes`(문구표 문장 배열) |
| `result.assumptions`의 그 밖 | 아니오 |
| `display` | 필터를 통과한 항목만 |
| **[D-067]** 문구표 `tax.note` | `taxNote`로 넣는다(`excludedNotes`처럼 ai-server가 문구표에서 붙임, Spring 변경 없음) |

- 직렬화: 키는 camelCase(`by_alias=True`), `json.dumps(..., ensure_ascii=False)`.
- **테스트 기대값은 `json.loads`한 dict 비교**로 한다. 키 순서·공백은 계약하지 않는다.
- 계획이 없으면(`planContext: null`) `<plan_context>null</plan_context>`처럼 "없음"이 드러나게 넣는다.

### 프롬프트 규칙

| # | 규칙 | 넣는 Issue |
|---|---|---|
| 1 | 금액·기간·시점·비율은 `display` 값을 글자 그대로 인용한다 | #35 |
| 2 | 두 display 값을 빼거나 나눠 새 숫자를 만들지 않는다(초과 금액 포함) | #35 |
| 3 | `isAchieved=false`이고 `<S>.goalReachMonth` 키가 없으면 "기간 안에 도달하지 않아요"처럼 시점 없이 말한다 | #35 |
| 4 | 비교·차이는 `recalculate` 결과의 접두어 키(`current.`/`changed.`/`diff.`)만 인용하고 planContext의 `<S>.*`와 섞지 않는다 | #23 |
| 5 | 반영하지 않은 항목·3구간 산정 방식은 `excludedNotes`·`scenarioMethodNote` 문장을 그대로 쓴다 | #35 |
| 6 | 구체적인 연 수익률은 말하지 않는다. "과거 데이터로 만든 구간이며 수익을 약속하지 않는다"고만 답한다. 주어지지 않은 정보는 모른다고 한다 | #35 |
| 7 | **[D-059·D-092]** 대화로 바꿀 수 있는 다섯 조건(목표 금액·월 저축액·비중·적금 금리·기한)을 "더/덜/늘리면/줄이면"처럼 상대 표현으로 바꾸면 **계산하지 않고** `CLARIFY`로 바꿀 값을 절대값으로 되묻는다(지금 값은 display로 인용 가능). 되물을 때 예시 금액·금리를 지어내지 않는다(지금 값은 display 인용만) | #21 |
| 8 | **[D-060]** 세율은 `assumptions.taxRatePct` display만 인용한다. [D-067] 세율을 말할 때는 `taxNote`를 함께 쓰고 "~로 가정했어요"로 말한다 | #35 |
| 9 | (쪽별 적용) `status=ALREADY_EXCEEDED`인 쪽은 초과 사실만 말하고 초과 금액·비교 문장을 만들지 않는다. 409 초과 안내는 "대화로 목표 금액을 더 높게 바꿔 볼 수 있어요"를 안내한다(D-092) | #35 |

- `afterTaxGain`이 `-`로 시작할 때 "손실로 말한다" 같은 해석 규칙은 넣지 않는다(규칙이 늘어남). 음수 형식은 #28 Formatter 테스트로 고정한다.
- 모델이 실제로 규칙을 지키는지는 FakeClaude로 검증되지 않는다. **소량 수동 평가 목록 3~5건**(예: A-1에서 "목표까지 얼마나 모자라요?" → `-약 288만원 미달`을 인용하는지)을 실행하고 결과를 #35 PR 본문에 적는다.
- `prompts.py`는 #21·#22·#23·#35·#38이 같이 고친다. **#21 → #22 → #23 → #35 순서**로 처리한다.
- `SYSTEM_PROMPT`의 대화 변경 가능 조건은 D-090·D-092에 따라 **5개**(목표 금액 추가, 1단계)로 갱신한다(#21).

### 세금 응답 보장 (D-067, D-073)

- 응답에 `assumptions.taxRatePct` 표시값이 있으면 ai-server가 `tax.note`를 글자 그대로 덧붙인다(모델이 이미 넣었으면 중복하지 않는다). 모델 위반을 실패로 처리하지 않는다. → #35, #18
- 상품별 세제 혜택·개인 세금 계산·절세 방법 질문은 FR-AI-09 범위 밖이다. `OUT_OF_SCOPE` + 문구표 `outOfScope.tax`로 답한다(전문가 확인 안내). 같은 `tax.note`를 가정 보기(FR-RES-06)에도 쓴다.

## C1a-9. 키 표기 camelCase

- `planContext` 안의 모든 JSON 키는 camelCase. `display` 키도 camelCase 경로를 점으로 이은 것. snake_case 키는 없다.
- Spring: View DTO에 `@JsonProperty("isAchieved")`·`@JsonProperty("isDefaultSavingsRate")`. Spring Boot 4(Jackson 3)에서 record의 `boolean isAchieved` 접근자가 is-getter 규칙과 겹쳐 `achieved`로 나갈 수 있으므로 도메인 `ScenarioResult`/`Assumptions`를 직접 직렬화하지 않는다. G-5b가 고정한다.
- ai-server: `ApiModel`(`alias_generator=to_camel`, `serialize_by_alias=True`). `display`는 `dict`라 alias 영향 없음.
- 범위 밖: `ChatResponse.conditionChanges`의 snake_case 키는 #44.

## C1a-10. 누가 언제 만드는가 (Spring)

**만드는 곳: `calc` 모듈의 공개 API `PlanContextProvider`.** QUESTION 경로가 `calc_run`을 INSERT할 수 있어 이름에 `Query`를 쓰지 않는다.

```java
// calc 루트 패키지(공개 API)
public interface PlanContextProvider {
    /** F-3 요청 시점: 그 회원의 PLAN 기록인지와 기준 시나리오만 확인한다. 재계산 없음. 아니면 empty. */
    Optional<PlanRunRef> findPlanRun(long memberId, long calcRunId);
    /** EXPLAIN: 지정한 PLAN 기록으로 만든다. baselineScenario는 chat이 F-3 때 저장한 값. 아니면 empty. */
    Optional<PlanContextView> findForCalcRun(long memberId, long calcRunId, Scenario baselineScenario);
    /** QUESTION: 현재 계획의 최신 PLAN 기록으로 만든다. 계획이 없으면 empty. */
    Optional<PlanContextView> findForCurrentPlan(long memberId);
}
public record PlanRunRef(long calcRunId, long planVersionId, Scenario baselineScenario) {}
public record PlanContextView(long calcRunId, ConditionsView conditions, CalcResultView result, Map<String, String> display) {}
```

- 시그니처 세부는 제공자 재량. 위 세 가지 책임과 인자는 계약이다.
- **의존 방향**: `plan {}` ← `calc {plan, refdata}` ← `chat {calc, rag}`. chat은 calc 루트만, calc는 plan 루트 공개 API만 부른다. 새 순환 없음.
- **소유권 확인은 2단계, JOIN 금지**: ① calc 리포지토리 `findByIdAndRunType(calcRunId, PLAN)` ② plan 공개 API `findVersion(memberId, run.planVersionId)` → 비면 empty. 왜: `calc_run`에는 `member_id`가 없고, calc가 plan 테이블을 JOIN하면 `modulith.md`의 "다른 모듈의 엔티티·리포지토리를 쓰지 않는다"를 어긴다. `persistence.md`에 calc_run 예외를 한 줄 적는다(G-13).
- **plan 공개 API(#19)에 두 가지를 둔다**(G-12): 현재 계획 조회 + `findVersion(long memberId, long planVersionId)`(소유권 포함). EXPLAIN은 현재 버전이 아닌 그 기록의 버전을 읽어야 한다.
- **`findPlanRun`의 기준 시나리오 출처**: `PlanRunRef.baselineScenario`는 조회 시점의 `plan.baseline_scenario`다. 이 컬럼은 `plan` 테이블에 있고 `plan_version`에는 없다(`schema.sql`). 그래서 `findPlanRun`은 PK 조회 3번: `calc_run` → `findVersion`(소유권) → 현재 계획 조회(기준 시나리오). plan은 의존이 `{}`라 `calc.Scenario`를 쓸 수 없으므로 **plan 공개 API는 기준 시나리오를 문자열(또는 plan 자체 enum)로 돌려주고, calc가 `Scenario`로 바꿔 `PlanRunRef`에 담는다.**
- **EXPLAIN의 `baselineScenario`는 인자로 받는다.** `calc_run`에 기준 시나리오가 저장되지 않으므로, F-3 이후 기준 시나리오가 바뀌어도 `ChatRequest.baselineScenario == planContext.result.baselineScenario`가 깨지지 않게 chat이 `chat_message.baseline_scenario`에 저장한 값을 넘긴다. QUESTION은 `plan.baseline_scenario`(현재).
- **F-3 404**(구현 #43, 계약 문장 #12): "없는 기록", "남의 기록", "PLAN이 아닌(CHAT) 기록"을 모두 같은 `404 NOT_FOUND`로 낸다(id 추측 방지). `findPlanRun`이 셋 다 empty → #43 컨트롤러가 `NOT_FOUND` 예외 → 전역 `@RestControllerAdvice`(#17)가 ProblemDetail `code=NOT_FOUND`.
- **언제**: chat의 `ChatAnswerRequested` 리스너가 ai-server `/chat`을 호출하기 직전에 만든다. 저장하지 않고 호출마다 만든다(ADR-002).
- **트랜잭션: ai-server 호출 리스너는 트랜잭션 없이 돈다**(G-9a, #41). 순서: "planContext 만들기(`PlanContextProvider`의 자체 `@Transactional`) → **트랜잭션 밖** ai-server HTTP 호출 → 결과 저장(짧은 트랜잭션)". 왜: 30초 호출 동안 DB 커넥션을 잡지 않는다.
  - 문제: `modulith.md` 이벤트 절은 "수신은 `@ApplicationModuleListener`로 한다(커밋 후, 비동기, **새 트랜잭션**)"이다. 그대로 쓰면 리스너 메서드 전체가 새 트랜잭션이라 위 순서가 깨진다.
  - 방법(둘 중 하나, #41 담당 재량): (a) 쓰는 Spring Modulith 버전의 `@ApplicationModuleListener`가 전파 속성을 받으면 `propagation = NOT_SUPPORTED`. (b) 받지 않으면 `@Async @TransactionalEventListener` + 저장 구간만 `TransactionTemplate`. **(a)의 전파 속성 지원 여부는 아직 확인하지 않았다** — #41 착수 시 먼저 확인한다. 어느 쪽이든 `event_publication` 기록은 유지되어야 한다.
  - `backend/.claude/rules/modulith.md` 이벤트 절에 예외 1줄을 더한다: "외부 HTTP를 부르는 리스너는 트랜잭션 없이 돈다". #41 paths에 이 파일을 추가한다.
- **생성 실패**: `Optional.empty()`는 "계획 없음"(QUESTION → `planContext: null`) 또는 "기록 없음"(EXPLAIN, 정상 경로에서는 F-3에서 걸러짐). 엔진·기준 데이터 오류는 예외로 올라오고, **chat 리스너는 ai-server를 부르지 않고 메시지를 `FAILED`·`FAILURE`로 바꾼다**(FR-AI-04, #41).

| mode | `planContext`의 기준 | `ChatRequest.calcRunId` |
|---|---|---|
| `EXPLAIN` | 요청의 `calcRunId`. 그 기록의 `plan_version`과 데이터 버전으로 계산. 현재 계획이 그 뒤 바뀌었어도 그 기록 기준 | `planContext.calcRunId`와 같은 값 |
| `QUESTION` | 현재 계획 버전의 최신 PLAN 기록. #29의 "현재 계획의 결과를 조회하는 공개 서비스"(결과 화면 Q1(a)~(c), `db/erd.md` §4 Q1)를 그대로 호출해 얻는다(없거나 활성 버전과 다르면 계산·기록). planContext용으로 따로 구현하지 않는다 | `null`. **`planContext.calcRunId`는 있는 것이 정상** |

- **QUESTION 매 턴 INSERT 방지**: Q1 서비스는 "활성 버전과 같은 최신 PLAN 기록이 있으면 재기록하지 않는다". 그래서 대화 질문마다 `calc_run`이 쌓이지 않는다(G-18, #29 본문에서 확인).
- 같은 (버전, PLAN) 기록은 #29 advisory 잠금으로 하나만 생긴다(기준 데이터 활성 버전·`engine_version`이 그대로인 동안. 같은 revision = 같은 `calcRunId`, `frontend-api-plan.md` C12a-6-6).
- **데이터 버전**: planContext는 그 PLAN 기록의 버전, 같은 턴의 `/internal/calc` `current`는 활성 버전. QUESTION은 Q1 때문에 같고, 차이는 오래된 EXPLAIN 기록에서만 생기며 해커톤 중 재적재가 없으면 생기지 않는다(D-045). #10은 다시 열지 않고 프롬프트 규칙 4로 막는다.

## C1a-11. 특수한 경우

| 경우 | `planContext` | ai-server |
|---|---|---|
| 저장된 계획이 없음 | `null` | QUESTION: 숫자 없이 답한다. 계산·설명 요청이면 문구표 `chat.noPlan`. EXPLAIN: 400(C1a-8 검사 2) |
| ETF 0% | `scenarios`에 AVG 1개, `annualReturnPct`·`scenarioMethod`·`sp500BaseDate`·`dataVersions.sp500VersionId` = `null`, display에 `AVG.*`만 | 3구간을 말하지 않는다. `scenarioMethodNote` 없음. `plan-context-etf0.json` |
| 이미 목표 초과 | `scenarios: []`, display는 `goalAmount`, `calcStartMonth`, `targetMonth`, `conditions.*`, `assumptions.*` | EXPLAIN: LLM 없이 `chat.alreadyExceeded`(C1a-8 검사 4). QUESTION: 모델에 주되 초과 금액은 말하지 않는다(프롬프트 규칙 2·9). 대화 재계산은 목표를 바꾸지 않거나 여전히 초과일 때만 `409 ALREADY_EXCEEDED`이고, 목표를 올려 초과를 벗어나면 `200` 비대칭 비교다(D-092). `plan-context-exceeded.json` |
| 목표 기간 안에 도달 못 함(`isAchieved=false`) | raw `goalReachMonth`는 null 또는 "중간에 처음 닿은 달". **어느 경우든 display 키 없음** | "기간 안에 도달하지 않아요" |

- 초과 계획에서도 `conditions.heldAssets` display를 남긴다(사용자가 입력한 값이고 화면 조건 바에도 있다). 초과 금액 차단은 프롬프트 규칙 2·9가 맡는다.
- **문구표 키 고정**(#18이 이 이름을 쓴다):

| 키 | 쓰는 곳 | 문장(확정은 #18) |
|---|---|---|
| `chat.noPlan` | 계획 없음, `/internal/calc` `NO_PLAN`(C3-2)과 같은 문장 | "먼저 목표와 조건을 입력해 계획을 만들어 주세요" |
| `chat.alreadyExceeded` | 초과 EXPLAIN, `/internal/calc` `409 ALREADY_EXCEEDED`(C3-2)와 같은 문장 | "이미 가진 자산이 목표 금액을 넘었어요. 대화로 목표 금액을 더 높게 바꿔 볼 수 있어요." |
| `excluded.<CODE>` | `ExcludedItem` 5개(`ETF_TOTAL_EXPENSE`, `EXCHANGE_RATE`, `ETF_DISTRIBUTION`, `HELD_ASSET_RETURN`, `SAVINGS_REDEPOSIT`). `excluded.EXCHANGE_RATE` = "환율 변동은 반영하지 않았어요"(D-027) | 나머지 4개는 #18 |
| `scenarioMethod.<CODE>` | `CAGR`, `ROLLING_DISTRIBUTION` 2개 | TBD-01 결과에 따름. **결정 전에도 임시 문장을 넣는다**(빈 값 금지) |
| `tax.note` | [D-067] 모델 입력 `taxNote`, 가정 보기 | 일반 과세 기준 가정, 비과세·세금우대 상품·금융소득·세법 변경 시 달라질 수 있음, 정확한 세금은 금융기관·세무 전문가 확인(#18) |
| `outOfScope.tax` | [D-073] 범위 밖 세금 질문의 `OUT_OF_SCOPE` 응답 | #18 문구표에 이미 있음 |

  왜 C3-2와 같은 키인가: 같은 상황(계획 없음·초과)에 경로마다 다른 문장이 나오지 않게 한다.

## C1a-12. 크기·버전

- 크기: 시나리오 3개 기준 JSON 약 3KB, 모델 입력 약 1.5KB. 상한을 코드로 강제하지 않는다.
- **버전 필드(`contextVersion`)를 두지 않는다.** 한 repo·한 Compose 배포이고, 필드 추가는 "모르는 키 무시"로 안전하며, 모델이 쓰는 필드의 이름 변경은 필수 필드 누락 422와 양쪽 fixture 테스트(G-4·G-5)가 바로 잡는다.
- 필드를 더하는 변경: 이 문서 수정 → Spring이 보냄 → ai-server가 모델·패턴에 더할 때까지 모델에는 안 들어감(안전). 빼거나 뜻을 바꾸는 변경: 재협상.

## C1a-13. 예시 JSON과 fixture

- **fixture 위치: 공유 파일 [`docs/api/examples/plan-context/*.json`](examples/plan-context/).** backend 테스트와 ai-server 테스트가 같은 파일을 읽는다. 왜: 복사본 두 벌은 반드시 어긋난다.
- 숫자는 형식 예시다(엔진 결과 아님). 서로 맞게 만들었다: `principal = heldAssets + monthlySavingAmount × targetPeriodMonths`, `afterTaxAsset = principal + afterTaxGain`, `gapToGoal = afterTaxAsset − goalAmount`, display는 만원 반올림.
- 공통 계획(A-1): 목표 2,000만원, 보유 자산 500만원, 월 30만원, 36개월, 적금 금리 미입력(기본 3.12%), 비중 미입력(5:5), 시작월 2026-11. `internal-calc` `success-response.json`의 `current`와 같다.
- **파일 두 종류의 쓰임**
  - `plan-context-*.json`: **`planContext` 객체만** 담는다. ai-server 테스트는 헬퍼 `make_request(plan_context, mode, calc_run_id)`(테스트 코드, #35)로 `ChatRequest`에 감싸 쓴다.
  - `chat-request-*.json`: **`ChatRequest` 전체**를 담는다(줄임 없음). 그대로 `/chat` 요청 본문으로 쓴다.
  - `model-input-basic.json`: `plan-context-basic.json`의 모델 입력 기대값. **`excludedNotes`·`scenarioMethodNote` 두 키는 파일에 넣지 않는다.** 테스트가 문구표(`COPY`)에서 `[COPY[f"excluded.{c}"] for c in excludedItems]`, `COPY[f"scenarioMethod.{m}"]`로 두 키를 만들어 기대값 dict의 `result.assumptions`에 덧붙인 뒤 비교한다. 왜: 자리표시자 문자열을 파일에 두면 `json.loads` 비교가 항상 실패하고 사람이 "진짜 값인가" 헷갈린다. `excluded.EXCHANGE_RATE`는 D-027 문장으로 고정이고 별도 단언 1건으로 확인한다. [D-067] 문구표 `tax.note`를 넣는 `taxNote`도 파일에 넣지 않고 테스트가 `COPY["tax.note"]`로 채운다. **`taxNote`가 모델 입력의 어느 위치(`result.assumptions` 안인지 최상위인지)에 들어가는지는 이 계약이 정하지 않았다. #35가 정하고 이 문서를 고친다.**
- **G-5는 둘로 나뉜다**(엔진을 돌리지 않는 단위 테스트):
  - **G-5a(빌더, #28)**: 손으로 만든 `CalcInput`/`CalcResult` → `ResultDisplayBuilder`(접두어 `""`, `"conditions."`) 결과 = fixture의 `display` 객체. 요구 키 불변식, `goalReachMonth` 방출 규칙(미달 BAD 1건), 라벨-`isAchieved` 일치.
  - **G-5b(`PlanContextProvider` Issue, G-7)**: 같은 입력 → `PlanContextView` 직렬화 결과 = fixture 전체. `memberId` 키 없음, `isAchieved`·`isDefaultSavingsRate` 키 이름.
  - **숫자는 값으로 비교한다(`BigDecimal` 스케일 무시).** 왜: `Assumptions.taxRatePct`·`savingsRatePct`가 `BigDecimal`이라 `15.4`와 `15.40`이 JSON 트리 비교에서 다르게 판정될 수 있다. 비교 방법은 구현 재량(새 라이브러리 없이).
- ai-server 테스트는 모델 입력을 dict로 비교한다(C1a-8).

| 파일 | 내용 | Spring 테스트가 읽음(G-5) | ai-server 테스트가 읽음(G-4) | 기대 |
|---|---|---|---|---|
| `plan-context-basic.json` | 기본, 3구간 모두 미달 | 예 | 예 | — |
| `plan-context-etf0.json` | ETF 0% | 예 | 예 | — |
| `plan-context-exceeded.json` | 이미 초과 | 예 | 예 | — |
| `plan-context-achieved.json` | 달성·미달 섞임, `goalReachMonth` 있음 | 예 | 예 | `<S>.goalReachMonth` 키 패턴 통과 |
| `plan-context-zero-held.json` | `heldAssets=0`, 1만원 미만 이득 | 예 | 예 | `conditions.heldAssets: "0원"`, `AVG.afterTaxGain: "1만원 미만"`(D-061) |
| `plan-context-polluted.json` | 규칙 밖 키(`loginId`, `result.memberId`, 비교 키 `current.AVG.afterTaxAsset`, 패턴 밖 `AVG.annualReturnPct`, 값이 숫자인 `BAD.goalReachMonth`) | 아니오(ai-server 전용) | 예 | 요청 성공, 모델 입력이 `model-input-basic.json`(+ 문구 키)과 같음 |
| `plan-context-display-missing.json` | `AVG.gapToGoal` 없음 | 아니오(ai-server 전용) | 예 | `CONTEXT_DISPLAY_MISSING` 502, 모델 호출 없음 |
| `chat-request-explain.json` | EXPLAIN 전체 요청 | 아니오(ai-server 전용) | 예 | — |
| `chat-request-question-no-plan.json` | QUESTION, 계획 없음 | 아니오(ai-server 전용) | 예 | `<plan_context>null</plan_context>` |
| `chat-request-question-with-plan.json` | QUESTION, 계획 있음 | 아니오(ai-server 전용) | 예 | `calcRunId: null`이 정상 |
| `chat-request-explain-mismatch.json` | EXPLAIN, calcRunId 불일치(999 ≠ 540) | 아니오(ai-server 전용) | 예 | 400 `BAD_REQUEST`, 모델 호출 없음 |
| `chat-request-explain-null.json` | EXPLAIN, planContext null | 아니오(ai-server 전용) | 예 | 400 `BAD_REQUEST`, 모델 호출 없음 |
| `chat-request-explain-exceeded.json` | EXPLAIN, 초과 계획 | 아니오(ai-server 전용) | 예 | 200, 모델 호출 없음, `messageType=EXPLANATION`, 본문 = 문구표 `chat.alreadyExceeded`, `calcRunId` 542 |
| `model-input-basic.json` | `plan-context-basic.json`의 모델 입력(문구 키 제외) | 아니오(ai-server 전용) | 예 | dict 비교 기대값 |

- `ai-server`의 `FakeCalc.recalculate` 반환 모양은 옛 모양(`goodCase.finalAmount`)이다. "planContext display + 도구 display가 같은 턴에 들어가는" 테스트를 하려면 `internal-calc` fixture(`success-response.json`)로 바꿔야 한다. 이 교체는 **#23**(`tests/fakes/**`가 paths)이 하고 #35의 선행이다.
- `chat-request-*`의 `memberId`는 `ChatRequest` 최상위에만 있다(D-030). `planContext` 안에는 없다.

## C1a-14. 구현 현재 코드와 다른 곳

| # | 위치 | 지금 | 이 계약 | 후속 |
|---|---|---|---|---|
| D-1 | `ai-server/app/schemas.py` `ChatRequest.plan_context` | `dict[str, Any] \| None` | `PlanContext \| None`(C1a-8 필수/선택 목록) | #35 |
| D-2 | `ai-server/app/llm/agent.py` `build_messages` | dict 통째로 `json.dumps`, 계획 없으면 `{}` | 모델 전달 필드만, display 필터, 문구 매핑, `null` 표시 | #35 |
| D-3 | `ai-server/app/llm/prompts.py` | raw 숫자 인용 허용 문장 | 프롬프트 규칙 1~9 | #35, #21, #23 |
| D-4 | `ai-server/tests/test_agent.py` | planContext 없는 요청만, `FakeCalc` 옛 모양 | C1a-13 fixture 테스트 | #35(+#23 FakeCalc 교체) |
| D-5 | `backend/.../calc/` | `PlanContextProvider`·`PlanContextView`·`ConditionsView`·`CalcResultView`·`ScenarioResultView`·`ResultDisplayBuilder`·`Formatter` 없음 | `CalcResultView`·`ScenarioResultView`는 calc 루트(#32), `ResultDisplayBuilder`는 #28, `findPlanRun` 경량 조회 | G-7 새 Issue, #28, #32 |
| D-6 | `backend/.../plan/` | 공개 조회 API 없음 | 현재 계획 조회(기준 시나리오 문자열 포함) + `findVersion`(소유권 포함) | #19 |
| D-7 | `agent.py` `run()` EXPLAIN 검사 | `calc_run_id is None`만 | C1a-8 검사 2~4 추가 | #35 |
| D-8 | `backend/.claude/rules/modulith.md` 이벤트 절 | 수신은 `@ApplicationModuleListener`(새 트랜잭션)만 | 외부 HTTP 리스너는 트랜잭션 없이 예외 1줄 | #41 |

## C1a-15. Issue #11 수용 기준 대응

| # | 수용 기준(Issue 본문 순서) | 이 문서 |
|---|---|---|
| 1 | 예시에 `memberId`·계정 식별 정보가 없다(D-030) | C1a-7, fixture의 `planContext` 안·모델 입력, G-5b |
| 2 | display는 Spring이 만든 문자열이고 LLM이 반올림하지 않음, raw 금액은 모델 입력에 없음 | C1a-4, C1a-6, C1a-8(모델에 넣는 것, 프롬프트 규칙 1·2) |
| 3 | 규칙 밖 필드는 오류 없이 무시하고 모델에도 넘기지 않음 | 원칙 4, C1a-8, `plan-context-polluted.json` |
| 4 | display가 C2-6 규칙을 따름, 추가 키 4개와 세율 `assumptions.taxRatePct` | C1a-6 표 |
| 5 | `<S>.goalReachMonth` 방출 규칙 | C1a-6 표와 설명 |
| 6 | 일반 금액 경계(`0원`, `1만원 미만`, 음수) | C1a-6 경계 형식(D-061) |
| 7 | (예외) snake_case 키 없음 | C1a-9, fixture 전체 |
| 8 | `result` 구조(`result.assumptions`, 시나리오 순서, `monthlyPoints` 없음, `status`) | C1a-2, C1a-4, C1a-5 |
| 9 | ai-server 필수/선택 표, 검사 순서 1~6 | C1a-8 |
| 10 | 가정 문장은 Spring이 코드만, ai-server가 문구표 문장으로 | C1a-5 |
| 11 | fixture 14개 | C1a-13 표, `docs/api/examples/plan-context/` |
| 12 | fixture 표의 Spring/ai-server 사용 열 | C1a-13 표 |
| 13 | (예외) 422 본문은 FastAPI 기본, `{error: CODE}` 예외 | C1a-8, `ai-server/README.md` |
| 14 | EXPLAIN·QUESTION의 `calcRunId`, 계획 없음 `planContext: null` | C1a-10 표, C1a-11 |
| 15 | 문구표 키 이름 | C1a-11 |
| 16 | 프롬프트 규칙 1~9와 담당 Issue | C1a-8 프롬프트 규칙 |
