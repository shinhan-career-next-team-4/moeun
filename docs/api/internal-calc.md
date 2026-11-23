# `/internal/calc` 계약 (ai-server ↔ Spring)

> 최종본 (2026-11-16, moeun #10, 요구사항 NFR-INT-03·FR-AI-06·FR-AI-07·CP-02·CP-04, 결정 D-033·D-040·D-041·D-055·D-056·D-057·D-061·D-090·D-092).
> 협상 기록과 원문은 기획 repo project-workspace `docs/design/contracts/internal-calc.md`이며, 원문 이후 planContext 계약 최종본의 정정(G-11: C2-4 루트 타입·`/internal/calc` 한정 문구, 일반 금액 경계 표, 공유 `ResultDisplayBuilder`, 문구표 키 연결)을 반영했다. 이 문서가 구현 기준이며 `ai-server/README.md` "Spring과의 계약"은 이 문서의 요약이다.
> 응답·오류 예시 JSON은 [`docs/api/examples/internal-calc/`](examples/internal-calc/)에 파일로 둔다. B팀 목(ai-server)과 A팀 REST Docs 테스트가 같은 파일을 기준으로 삼는다(부록 A).
> **[전환기]** 표시는 담당 Issue가 머지되기 전까지만 허용하는 동작이다. **[D-055]·[D-056]·[D-057]**은 협상 쟁점이었다가 사람이 결정한 항목이다. 항목 ID(C2-x, C3-x)는 기획 repo 문서와 같다.

## 0. 이 계약의 원칙 (전 항목 공통)

1. **숫자는 Spring만 만든다.** 차이(`diff`)와 표시 문자열(`display`, `errors[].minDisplay` 등)도 Spring이 만든다. 표시 문자열은 모두 calc 모듈(`Formatter`와 공유 `calc.application.ResultDisplayBuilder`, C2-6·C3-1)에서만 만든다. **모델이 사용자에게 말하는 금액·기간·비율은 `display` 문자열을 그대로 인용한 것뿐이다.** raw 숫자는 판단용(달성 여부, 시나리오 수 등)이며 모델이 이 값으로 "만원" 문자열을 만들거나 빼기·나누기를 하지 않는다. 왜: LLM의 반올림·뺄셈도 계산이라 NFR-INT-01·FR-AI-02를 어기고 숫자 대조(PR-04)를 어렵게 한다.
2. **범위 검증은 Spring 한 곳**(D-033). ai-server는 JSON 타입만 맞춰 보낸다. 범위 수치를 코드·프롬프트에 두지 않는다.
3. **기준은 항상 현재 계획, 변경은 누적하지 않는다**(D-041). Spring은 요청 한 건만 보고 계산하며 대화 상태를 들고 있지 않다.
4. **JSON은 camelCase**, 금액은 원(KRW) 정수, 기간은 개월 정수, 퍼센트는 숫자(`3.12` = 3.12%).
5. **새 라이브러리 없음.** 오류는 Spring 내장 `ProblemDetail`, 직렬화는 Jackson 기본, ai-server는 기존 `httpx`·pydantic.
6. **null은 키를 빼지 않는다.** Spring 전역 설정에서 `default-property-inclusion: non_null`을 켜지 않는다. 왜: 목·fixture가 `"goalReachMonth": null`을 기준으로 삼는다.
7. **받는 쪽은 모르는 키를 무시한다.** 필드가 늘어도 깨지지 않게.

---

## C2. `POST /internal/calc` (ai-server → Spring)

### C2-1. 경로·헤더·호출 주체

| 항목 | 값 |
|---|---|
| 메서드·경로 | `POST /internal/calc` |
| 호출자 | ai-server만 (도구 `recalculate` → `CalcClient.recalculate`) |
| 인증 | 헤더 `X-Internal-Api-Key: <공유 비밀>`. 사용자 JWT 없음 |
| 요청 Content-Type | `application/json` |
| 성공 응답 | `200 OK`, `application/json` |
| 오류 응답 | C3-1 형식, `application/problem+json` |
| 담당 | Spring `calc` 모듈(A팀). 계약은 A·B 공동 |

- 브라우저에 노출하지 않는다. 요청 본문의 `memberId`를 믿는 유일한 경로다(`backend/.claude/rules/persistence.md`).
- Spring은 `/internal/**` 전용 `SecurityFilterChain`(API 키 필터)을 둔다. 지금 `SecurityConfig`의 `anyRequest().authenticated()`와 분리한다. 왜: JWT 체인과 섞이면 내부 호출이 401로 막힌다.
- **401 본문은 필터가 직접 쓴다.** `@RestControllerAdvice`를 거치지 않으므로 `instance`가 없을 수 있다. `code`는 반드시 있다(C3-2).

### C2-2. 요청

```json
{
  "memberId": 7,
  "conditionChanges": { "monthlySavingAmount": 500000 }
}
```

| 필드 | 타입 | 필수 | 뜻 |
|---|---|---|---|
| `memberId` | integer | 예 | 계산할 회원. 그 회원의 **현재 계획**이 기준(C2-8) |
| `conditionChanges` | object | 예 | 바꿀 조건만 담는다. 키가 없거나 값이 `null`이면 "바꾸지 않음" |
| `baselineScenario` | string | **아니오(무시)** | 지금 `CalcClient`가 보내므로 받기는 하지만 **Spring은 값을 보지 않는다.** `String`으로 받고 검사하지 않으며, 모르는 값이 와도 거절하지 않는다. 기준 시나리오는 `plan.baseline_scenario`에서 읽는다. 다음 계약 버전에서 뺀다 |

- `baselineScenario`를 계획에서 읽는 이유: 원천이 한 곳이고 같은 `plan` 행이라 비용이 없다. 해커톤에서는 항상 AVG(FR-RES-09 Should)라 ai-server 동작은 바뀌지 않는다. ai-server는 지금 코드를 고치지 않아도 된다.

`conditionChanges` 허용 키 (D-090·D-092·#160, CP-07 — **6개**):

| 키 | JSON 타입 | 단위 | 대응 컬럼(`plan_version`) |
|---|---|---|---|
| `goalAmount` | integer | KRW | `goal_amount` |
| `monthlySavingAmount` | integer | KRW | `monthly_saving_amount` |
| `savingsRatioPct` | integer | PCT (적금 비중, 나머지 ETF) | `savings_ratio_pct` |
| `savingsRateInputPct` | number | PCT (연, 세전) | `savings_rate_input_pct` |
| `targetPeriodMonths` | integer | MONTH | `target_period_months` |
| `etfProductCode` | **string** | — (활성 ETF 종목 코드. 지금 `SP500`, `069500`, `133690`) | 계획의 ETF 종목(#154). 종목을 저장하기 전의 옛 계획은 `SP500` |

`etfProductCode` 규칙 (#160 합의):
- 허용 값은 활성 ETF 종목 목록(ETF 목록 API와 같은 출처)이다. 검증은 Spring이 한다. ai-server는 모델 도구 스키마에 고정된 값 목록(enum)이 필요해서 코드 목록을 `app/rag/etf_codes.py` **한 곳**에 두고, 도구 enum·설명이 여기서 나온다(#267). 종목을 더하거나 빼면 이 파일과 프롬프트를 같이 고친다. ai-server 목록에 있지만 Spring에서 꺼진 종목은 422(아래 검사 순서 6)로 되묻는다.
- 계획과 같은 종목을 보내면 바꾸지 않은 것으로 본다(`calc_run.changed_etf_version_id`도 NULL, C2-7).
- `changed`의 ETF 비중이 0%(`savingsRatioPct=100`)여도 거절하지 않는다. ETF를 쓰지 않으니 종목 차이는 없고 정상 비교(`200`)로 내려준다. ai-server는 "ETF 비중이 0%라서 종목을 바꿔도 결과는 같아요." 한 문장으로 답한다(B팀 후속).
- 종목 이름은 지수 이름(예 "코스피200 추종 ETF")으로만 쓰고 운용사 상품명이나 "추천" 표현은 쓰지 않는다(CP-06).
- 종목을 바꾼 비교도 다른 비교와 같은 기준으로 적용할 수 있다(F-4 `applicable`, F-5가 새 `plan_version`에 종목 저장. #154).

**받는 방식**: Spring은 `memberId`와 `conditionChanges`를 타입 있는 record가 아니라 `JsonNode`(`conditionChanges`는 `Map<String, JsonNode>`)로 받는다. `memberId`는 `JsonNode.isIntegralNumber()`로 정수인지 검사하고, `conditionChanges`는 `plan.ConditionValidator.validate(ChangePath.CHAT, …)`에서 검사한다. 왜: Boot 기본값(`FAIL_ON_UNKNOWN_PROPERTIES=false`, `ACCEPT_FLOAT_AS_INT=true`)으로는 `goalAmount`가 조용히 버려지고 `500000.5`·`memberId: 7.5`가 잘려, 아래 거절 규칙을 지킬 수 없다.

**검사 순서** (앞에서 걸리면 뒤는 보지 않는다):

| 순서 | 검사 | 결과 |
|---|---|---|
| 0 | 내부 키 | 없음·불일치 → `401 INVALID_INTERNAL_KEY` |
| 1 | 본문 JSON 파싱, `memberId`가 정수인지(`isIntegralNumber()`, `7.5`·`"7"`은 실패), `conditionChanges`가 객체인지 | 실패 → `400 MALFORMED_REQUEST` |
| 2 | 값이 `null`인 키를 먼저 지운다 | — |
| 3 | 허용 6개 밖의 키(`heldAssets`, `existingMonthlyContribution`, 오타 키 등) | 하나라도 있으면 **요청 전체 거절** `422 FIELD_NOT_CHANGEABLE`. `errors`에는 허용 밖 키만 담는다 |
| 4 | 허용 키의 값이 JSON 숫자가 아님(문자열·불리언·객체·배열). **예외: `etfProductCode`는 JSON 문자열이 아니면**(숫자·불리언·객체·배열) | `400 MALFORMED_REQUEST` |
| 5 | 남은 키가 0개 | `422 VALIDATION_FAILED`, `errors=[{field:"conditionChanges", reason:"REQUIRED"}]` |
| 6 | 범위(`OUT_OF_RANGE`), 정수 아님(`NOT_INTEGER`, 예 `500000.5`), 소수 자릿수 초과(`TOO_MANY_DECIMALS`). `etfProductCode`가 활성 종목 목록에 없음(`OUT_OF_RANGE`, `min`·`max`·`unit`·`minDisplay`·`maxDisplay` 없음) | `422 VALIDATION_FAILED`. **걸린 키를 모두** `errors`에 담는다 |
| 7 | 그 회원의 `plan` 행 없음 | `409 NO_PLAN` |
| 8 | `current`와 `changed` 모두 이미 목표 초과(보유 자산 ≥ 각 목표 금액, FR-RES-01, D-092) | `409 ALREADY_EXCEEDED` (한쪽만 초과이면 `200` 비대칭 비교) |
| 9 | 계산 | 성공 `200` / 서버 오류 `500 CALC_FAILED` |

- 허용 키와 허용 밖 키가 섞여 와도 일부만 계산하지 않는다. 왜: 사용자가 말한 것과 다른 비교가 뜬다.
- `baselineScenario`는 어느 검사 순서에도 들어가지 않는다(값을 보지 않음).
- 시작월은 요청에 없다. 항상 현재 계획의 값(C2-8, D-013).
- **CP-02 범위 검증은 Bean Validation 애너테이션으로 하지 않는다.** 왜: 범위가 `rules.yml` 한 곳에 있어야 하고(C3-3), 대화·입력·비교 경로가 같은 `ConditionValidator`를 거쳐야 한다.

### C2-3. 응답 최상위

| 필드 | 타입 | 뜻 |
|---|---|---|
| `calcRunId` | integer | 이번 계산의 `calc_run(run_type=CHAT)` id(C2-7). ai-server는 이 값을 `/chat` 응답의 `calcRunId`로 넘긴다. **ai-server가 반드시 쓰는 유일한 식별자** |
| `conditions.current` / `conditions.changed` | object | 대화로 바꿀 수 있는 숫자 조건 5개(`goalAmount` 포함)의 **계산에 실제 쓴 값**(기본금리·기본비중이 채워진 뒤). 요청이 종목을 바꿨을 때만 `etfProductCode`(그쪽 계산에 쓴 종목 코드)가 더해진다(#160). 바꾸지 않았으면 키가 없고 종목은 `current.assumptions.etfProductCode`로 본다 |
| `current` / `changed` | object | C2-4 |
| `diff` | object | C2-5 |
| `display` | object | C2-6 |

- `conditions`가 필요한 이유: `CalcResult`에는 월 저축액·기간이 없다. "월 30만원 → 50만원" 비교와 설명을 응답 하나에서 읽게 한다.
- `conditions.*.savingsRateInputPct`는 기본금리를 썼어도 실제로 쓴 금리(= `Assumptions.savingsRatePct`)다. 기본금리였는지는 `current.assumptions.isDefaultSavingsRate`.
- `basePlanVersionId`, 최상위 `baselineScenario`, `conditionChanges` 에코는 **두지 않는다.** 소비자가 쓰지 않고, 기준 계획 판정(F-4 `applicable`)은 `calc_run.plan_version_id`로 하며, 기준 시나리오는 `current.baselineScenario`에 이미 있다.
- 구현 메모(A팀): 공개 타입 `calc.CalcComparison(current, changed, conditions, diff)`를 calc 루트에 둔다. `monthlyPoints` 제거는 응답 DTO(`CalcResultView`, C2-4)에서, `display`는 공유 `ResultDisplayBuilder`(C2-6)로 만든다. 도메인 타입은 바꾸지 않는다. F-4(#44)와 Should인 FR-CMP-02(#64)가 재사용한다.

전체 예시는 **부록 A-1**(줄임표 없음, fixture용).

### C2-4. `current` / `changed`

`current` = 현재 계획을 그대로 계산한 결과, `changed` = 현재 계획에 `conditionChanges`만 얹어 계산한 결과. 둘 다 `CalcResult`와 같은 모양이고 `scenarios[].monthlyPoints` **키 자체가 없다.**

- 구현: 응답 전용 DTO는 **calc 루트 공개 타입** `calc.CalcResultView`, `calc.ScenarioResultView`다(calc 하위 패키지 `calc.web`이 아니다). 왜: planContext(`PlanContextView`, calc 루트 공개 API)가 같은 DTO를 `result`로 쓰므로 비공개 하위 패키지 타입을 노출하면 chat이 참조하는 순간 `ModularityTests`가 깨진다(planContext 계약 C1a-2). 전역 Jackson 믹스인은 쓰지 않는다. 왜: 그래프 API(FR-RES-04)의 시계열까지 지워진다.

직렬화 규칙 — 목·fixture·REST Docs가 이 표기를 기준으로 삼는다:

| Java 타입 | JSON | 예 |
|---|---|---|
| `long`, `Long` | 정수 | `17120000` |
| `BigDecimal` | 숫자(문자열 아님). **자릿수는 고정하지 않는다**(`3.12`와 `3.120`은 같은 값). 테스트는 숫자로 비교 | `3.12` |
| `YearMonth` | `"YYYY-MM"` | `"2029-11"` |
| `LocalDate` | `"YYYY-MM-DD"` | `"2026-10-31"` |
| enum | 이름 문자열 | `"CALCULATED"` |
| `boolean isAchieved`, `isDefaultSavingsRate` | 키 이름 `isAchieved`, `isDefaultSavingsRate` (DTO에 `@JsonProperty`로 명시) | `"isAchieved": false` |
| `null` | 키를 빼지 않고 `null` | `"goalReachMonth": null` |

필드 뜻:
- `status`: `CALCULATED` 또는 `ALREADY_EXCEEDED`(D-092). 한쪽만 초과하고 다른 쪽은 계산되는 비대칭 비교에서는 초과한 쪽만 `ALREADY_EXCEEDED`(`scenarios: []`)가 온다.
- `scenarios`: **항상 GOOD·AVG·BAD 순서**(엔진이 보장하고 테스트로 고정). ETF 포함이면 3개, **ETF 0%(`savingsRatioPct=100`)면 AVG 1개**. 비중 변경으로 `current`와 `changed`의 시나리오 수가 다를 수 있다.
- `gapToGoal`: `afterTaxAsset - goalAmount`. 0 이상이면 달성(CP-01).
- `goalReachMonth`: 기간 안에 처음 목표 이상이 되는 달, 없으면 `null`.
- `baselineScenario`: `plan.baseline_scenario`. `current`·`changed` 같은 값.
- 소비자가 쓰지 않는 필드(무시해도 됨, **`/internal/calc` 한정**): `status`, `requiredMonthlySaving`(해커톤에서 항상 `null`, D-045), `dataVersions`, `assumptions.excludedItems`. planContext 계약은 `excludedItems`를 쓴다(ai-server가 문구표 문장으로 바꿔 모델에 넣음). 두 계약의 모델을 공유할 때 이 필드를 지우지 않는다.
- **추가(#150)** `assumptions.etfProductCode`·`etfProductName`·`etfGainTaxRatePct`: 계산에 쓴 ETF 종목과 그 매매차익 세율(ETF 0%면 null). 지금은 소비자가 쓰지 않는다(무시해도 됨). 비교 응답 display에는 종목 키를 만들지 않는다. 공유 예시(`docs/api/examples`)에도 들어 있다(#155에서 합의, 547102f). 프론트 목·ai-server 테스트가 같은 파일을 읽으므로, 이 세 칸을 고칠 때는 F-4 목(`frontend/src/mocks/chat/get-comparison.200*.json`)도 같은 PR에서 함께 맞춘다(#222)

### C2-5. `diff`

`changed − current`. Spring이 계산한다.

| 필드 | 타입 | 뜻 |
|---|---|---|
| `baselineScenario` | string | 기준 차이를 볼 시나리오(**포인터만**). **항상 `byScenario`에 있는 시나리오다.** `changed.findBaselineResult()`로 고른 시나리오가 `byScenario`에 없으면 `AVG`(ETF 0%면 `AVG`) |
| `byScenario[]` | array | **양쪽에 모두 있는 시나리오만**, GOOD·AVG·BAD 순서. 한쪽이 `ALREADY_EXCEEDED`면 `[]` |
| `byScenario[].scenario` | string | |
| `byScenario[].afterTaxAssetDelta` | integer(KRW, 부호 있음) | `changed.afterTaxAsset − current.afterTaxAsset` |
| `byScenario[].principalDelta` | integer(KRW, 부호 있음) | `changed.principal − current.principal`. "더 넣은 돈" |
| `byScenario[].afterTaxGainDelta` | integer(KRW, 부호 있음) | `changed.afterTaxGain − current.afterTaxGain`. "더 늘어난 수익". 왜: 없으면 모델이 자산 차이 − 원금 차이를 뺀다 |
| `byScenario[].achievementChange` | `STAYS_ACHIEVED` / `STAYS_MISSED` / `MISS_TO_ACHIEVE` / `ACHIEVE_TO_MISS` | `isAchieved` 전후 |

- 포인터 규칙을 적은 이유: `CalcResult.findBaselineResult()`는 기준 시나리오가 없으면 다른 시나리오로 폴백하므로, 그대로 쓰면 `byScenario`에 없는 이름이 나올 수 있다. #32 테스트로 "포인터는 항상 `byScenario`에 있음"을 고정한다.
- `comparable` 플래그와 `gapToGoalDelta`는 두지 않는다. 목표가 바뀔 때도 각 시나리오의 `gapToGoal`은 각자 자기 목표 기준이다.
- 기간을 바꾸면 서로 다른 달의 자산을 비교한다. 이 값이 맞다(사용자는 "기한을 늘리면 그때 얼마"를 묻는다). 기간은 `display`의 `conditions.*.targetPeriodMonths`·`*.targetMonth`로 말한다.
- **달성 시점의 차이(몇 개월 빨라짐)는 계약에 두지 않는다.** 모델은 `display`의 `current.<S>.goalReachMonth`·`changed.<S>.goalReachMonth`를 각각 인용할 수는 있지만 두 달 사이의 개월 수를 말하지 않는다(프롬프트 규칙). 왜: 한쪽이 `null`인 경우가 많아 뜻이 애매하고, 필요해지면 다음 버전에 필드로 더한다.
- 시나리오 수가 다르면(비중 100 ↔ 그 밖) `byScenario`에는 AVG만 남는다. ai-server는 `changed.scenarios`와 그 `display`로 새 3구간을 설명한다.

### C2-6. `display`

CP-04 형식(`Formatter`)으로 Spring이 만든 문자열의 **평평한 맵**(키는 점으로 경로 표시). **모델은 금액·기간·비율을 말할 때 이 문자열만 그대로 인용한다.**

키 규칙 (응답에 **실제로 있는 시나리오만** 키를 만든다. ETF 0%면 `AVG`만):

| 키 | 형식 | 비고 |
|---|---|---|
| `goalAmount` / `conditions.<side>.goalAmount` | `약 N만원` | 목표가 바뀌면 `conditions.<side>.goalAmount`로 쪽별 분리, 바뀌지 않으면 `goalAmount` |
| `current.targetMonth`, `changed.targetMonth` | `YYYY년 M월` | |
| `<side>.<S>.afterTaxAsset`, `<side>.<S>.principal`, `<side>.<S>.afterTaxGain` | `약 N만원` | `<side>` = `current` \| `changed` |
| `<side>.<S>.gapToGoal` | **[D-055 확정]** CP-04 그대로 `부호 + 약 N만원 + 달성/미달` (`-약 288만원 미달`, `+약 501만원 달성`) | |
| `<side>.<S>.goalReachMonth` | `YYYY년 M월` | 값이 `null`이면 **키를 만들지 않는다**. 모델은 키가 없으면 "기간 안에 도달하지 않아요"처럼 시점 없이 말한다 |
| `diff.<S>.afterTaxAssetDelta`, `diff.<S>.principalDelta`, `diff.<S>.afterTaxGainDelta` | **[D-055 확정]** 방향 단어 `약 N만원 증가` / `약 N만원 감소` | |
| `conditions.<side>.monthlySavingAmount` | `약 N만원` | |
| `conditions.<side>.targetPeriodMonths` | `n년 n개월` | |
| `conditions.<side>.savingsRatioPct` | `적금 a : ETF b` | 비중 표시는 이 형식 하나만 쓴다(CP-04). `errors[].minDisplay`·`maxDisplay`도 같다(C3-1) |
| `conditions.<side>.savingsRateInputPct` | `연 N.NN%` (소수 둘째 자리까지, 끝의 0은 지우지 않음) | CP-04에 없는 형식(금리) |
| `conditions.<side>.etfProductName` | 종목 화면 이름(지수 이름, 예 `코스피200 추종 ETF`) | **요청이 종목을 바꿨을 때만** 키를 만든다(#160). 값은 `assumptions.etfProductName`과 같다. 운용사 상품명·"추천" 표현 금지(CP-06) |

경계 형식 두 표. 첫 표는 차이·목표 대비 경계로 **[D-055]**에서 확정했고, 둘째 표는 일반 금액 경계로 **[D-061]**(음수 줄은 planContext 협상 합의)에서 확정했다. 두 표를 합치지 않는다. 같은 값(예: 0원)이라도 차이와 금액의 형식이 다르다.

차이·목표 대비 경계 [D-055]:

| 경우 | 형식 |
|---|---|
| 정확히 0원(차이) | `변화 없음` |
| 0원이 아니지만 `약 N만원`으로 바꾸면 0만원이 되는 값 | `1만원 미만 증가` / `1만원 미만 감소` (목표 대비는 `-1만원 미만 미달` / `+1만원 미만 달성`) |
| 목표 대비 정확히 0원 | `0원 달성` (CP-01: 0원은 달성) |
| 1억 이상 | 단위를 바꾸지 않고 `약 12,345만원` (CP-04 글자 그대로. 모든 금액에 적용, D-065) |

일반 금액 경계 [D-061, 음수는 합의] (`afterTaxAsset`, `principal`, `afterTaxGain`, `conditions.*.monthlySavingAmount` 등 차이·목표 대비가 아닌 금액):

| 경우 | 형식 |
|---|---|
| 정확히 0원 | `0원` |
| 0원이 아니지만 `약 N만원`으로 바꾸면 0만원이 되는 값 | `1만원 미만` |
| 음수(예: `afterTaxGain` 손실) | `-약 N만원`. 0만원으로 반올림되면 `-1만원 미만` |

- **display 맵은 공유 빌더 `calc.application.ResultDisplayBuilder`(#28) 한 곳에서 만든다.** 결과 화면(#30)·`/internal/calc`(#32)·planContext가 같이 쓴다. 입력은 `CalcInput` + `CalcResult` + 결과 접두어(`""` / `"current."` / `"changed."`) + 조건 접두어(`"conditions."` / `"conditions.current."` / `"conditions.changed."`)다. 왜: 같은 값을 세 곳에서 만들면 "화면 숫자 = AI 숫자 글자까지"(PR-04)를 코드로 보장할 수 없다. 계약 키는 그대로다.
- 반올림 단위는 TBD-16(천 단위 반올림)을 따르고 `Formatter` 한 곳에서 한다. **달성/미달 판정과 라벨은 raw 값(`isAchieved`)을 따른다.** 판정에 반올림 전/후 중 무엇을 쓸지(TBD-16 미결)는 #9(T-001)·#28(T-020)에서 정하며, 표시는 그 판정과 모순되지 않게 한다.
- **델타는 따로 반올림한다.** 그래서 `display` 두 값을 빼면 1만원 어긋날 수 있다. 모델은 두 `display` 값을 나란히 놓고 빼 보이지 않는다(프롬프트 규칙). 차이는 `diff.*` 키만 인용한다.
- 문구 템플릿(`달성`, `미달`, `증가`, `감소`, `변화 없음`, `적금 a : ETF b`)은 문구표 `copy/copy.ko.yml`(CP-05, #18)에서 읽는다.
- 소비자가 말하지 않는 값(display 없음): `annualReturnPct`(과거 연환산 수익률. 수익 약속처럼 들릴 위험), `dataVersions`, `requiredMonthlySaving`. 데이터 기준월 등 가정 설명은 `planContext`(C-1a, #11)에서 다룬다.
- **[전환기]** #28(Formatter)이 머지되기 전에는 Spring이 `display: {}`를 돌려줄 수 있다. ai-server는 `display`가 비었거나, 응답에 있는 시나리오의 `*.afterTaxAsset` 키가 하나라도 없으면 **비교를 내지 않고** `AgentError("CALC_DISPLAY_MISSING")`으로 실패시킨다(→ Spring `FAILED`, FR-AI-04). 왜: display 없이 금액을 말하면 NFR-INT-01을 어긴다. B팀은 그동안 부록 A-1 fixture로 개발한다.

### C2-7. 계산 기록

- 성공한 요청 1건마다 `calc_run` **1행**을 INSERT한다: `plan_version_id` = 현재 계획 버전, `run_type = 'CHAT'`, 데이터 버전 3종과 `engine_version`. 그 `id`가 `calcRunId`.
- `current`와 `changed`는 **같은 데이터 버전·같은 엔진 버전**으로 계산하고 그 버전을 이 행에 남긴다. **예외(#160 합의)**: 두 쪽이 서로 다른 ETF 종목을 쓰면 `changed.dataVersions.sp500VersionId`는 changed 종목의 가격 버전이고, 이 행의 `changed_etf_version_id`에 따로 남긴다(JSON 키는 늘지 않음). 키 이름은 `sp500VersionId`지만 뜻은 "그쪽 계산에 쓴 ETF 가격 버전"이다. 같은 종목이면 `changed_etf_version_id`는 NULL이다. `current`용 행은 따로 만들지 않는다. 왜: 차이가 조건 변경에서만 나오고, F-4(#44)가 이 행 + `chat_message.condition_changes`만으로 같은 결과를 다시 계산할 수 있다(ADR-002 결과 미저장).
- 해커톤에서는 활성 데이터 버전을 쓴다(D-045: D-038 범위 밖).
- **실패하면 기록하지 않는다**: C3의 모든 오류에서 `calc_run`을 만들지 않는다(500은 롤백). 그래서 `calcRunId`가 없으면 `RECALC_RESULT`도 없다(D-033).
- **[D-057]** 성공했지만 ai-server가 그 뒤 실패하거나 `OUT_OF_SCOPE`로 끝나면 `chat_message`가 참조하지 않는 CHAT 행이 남는다. **남기고 정리하지 않는다(D-057).** moeun ADR-002 §2-2 문장을 이에 맞게 고친다(후속 Issue).
- 요청마다 새 행이다(멱등 키 없음).

### C2-8. 기준 계획과 누적 금지

- **기준은 항상 그 회원의 현재 계획**(`plan.current_version_id`)이다. 이전 대화에서 바꾼 조건은 **누적하지 않는다**(D-041). Spring은 요청 한 건의 `conditionChanges`만 현재 계획에 얹는다. calc는 plan 공개 API(`plan.CurrentPlanQuery`)로 읽는다(의존 방향 calc → plan).
  - 예(FR-AI-06 수용 기준): 현재 월 30만원. 1번째 요청 `{monthlySavingAmount: 500000}` → 월 50만원 비교. 2번째 요청 `{targetPeriodMonths: 60}` → **월 30만원 + 60개월** 비교.
  - ai-server 짝 규칙: 도구 인자에는 **이번 발화에서 사용자가 말한 조건만** 넣는다(M-3, #22). 한 발화에서 둘 다 말하면 두 키를 한 요청에 담는 것은 허용.
- **적용된 뒤에는 새 현재 계획이 기준**이다. 이전 비교 결과를 적용할 수 있는지는 A-1·F-4의 몫이며 이 계약 밖이다.
- **시작월은 현재 계획의 `calc_start_month`**(D-013, D-032). `current.calcStartMonth == changed.calcStartMonth`가 항상 성립한다.
- 대화로 바꾸지 않은 조건은 현재 계획 값 그대로다. 금리를 비워 둔 계획이면 `changed`도 기본금리, 비중을 비워 둔 계획이면 기본 비중(5:5).
- **비중을 바꾸지 않은 요청은 현재 계획의 해석된 비중을 그대로 쓴다**: `conditionChanges`에 `savingsRatioPct`가 없으면 `conditions.changed.savingsRatioPct = conditions.current.savingsRatioPct`. 왜: 비중을 기존 납입액에서 산출하는 계획(FR-INP-08)이면 월 저축액만 바꿔도 비중이 바뀌거나 100을 넘어 500이 날 수 있다. 해커톤에서는 FR-INP-08이 Won't(5:5 고정)라 이 경우가 생기지 않으므로 결과는 같고, 방어 규칙으로만 둔다. FR-INP-08을 되살릴 때 다시 본다.
- 새로 필요: `CalcInput.calcStartMonth` 주석 정정(M-1, #25), Spring 통합 테스트 "두 번째 요청에 첫 번째 변경이 섞이지 않음"(#32).

---

## C3. 검증·오류 형식

### C3-1. 오류 본문 형식

Spring 내장 `ProblemDetail`(RFC 9457)에 확장 필드 `code`, `errors`를 더한다. `Content-Type: application/problem+json`. 전역 처리는 `config`의 `@RestControllerAdvice` 하나가 하고, 각 모듈은 자기 루트에 예외를 둔다(`plan.ConditionViolationException`, `calc.CalcRejectedException`). 공용 `common` 모듈은 만들지 않는다. Spring 기본 예외(파싱 실패 등)는 `400 MALFORMED_REQUEST`로 바꾼다.

| 필드 | 타입 | 필수 | 뜻 |
|---|---|---|---|
| `type`, `title`, `status`, `detail`, `instance` | — | `status`만 필수 | `ProblemDetail` 기본 필드. 사람이 읽는 용도. `title`은 코드별 고정 문자열(문구표 연결은 #18 이후). `500`의 `detail`에는 내부 메시지를 넣지 않는다. **기계 판단은 `code`로만** |
| `code` | string | 예 | C3-2 코드 |
| `errors` | array | 예(`VALIDATION_FAILED`·`FIELD_NOT_CHANGEABLE`은 1개 이상, 그 밖은 `[]`) | 필드별 오류. **걸린 필드를 모두** 담는다 |
| `errors[].field` | string | 예 | 요청 `conditionChanges`의 키(camelCase) 또는 `conditionChanges` |
| `errors[].reason` | string | 예 | `OUT_OF_RANGE` / `REQUIRED` / `NOT_INTEGER` / `TOO_MANY_DECIMALS` / `NOT_CHANGEABLE` |
| `errors[].min`, `errors[].max` | number | `OUT_OF_RANGE`일 때(단 `field = etfProductCode`면 없음, #160) | 허용 범위(양끝 포함). 요청 값과 같은 단위 |
| `errors[].unit` | `KRW` / `MONTH` / `PCT` | `min`·`max`가 있을 때 | |
| `errors[].minDisplay`, `errors[].maxDisplay` | string | `OUT_OF_RANGE`일 때. **`/internal/calc`에서만 보장**(프론트 API에는 없음) | `Formatter`가 만든 표시 문자열. 경계값은 정확한 값이므로 `약`을 붙이지 않는다. 금액 `1만원`·`1,000만원`, 기간 `0년 1개월`·`30년 0개월`, 금리 `연 20.00%`, 비중 `적금 0 : ETF 100`·`적금 100 : ETF 0`(C2-6과 같은 형식) |
| `errors[].scale` | integer | `TOO_MANY_DECIMALS`일 때 | 허용 소수 자릿수(금리 2, TBD-09) |
| `errors[].copyKey` | string | 아니오 | 문구표 키(CP-05). 프론트 경로에서 쓸 수 있다. 이 경로에서는 없어도 된다 |

- 요청 값(`rejectedValue`)은 넣지 않는다. 왜: ai-server는 자기가 보낸 값을 알고, 금융 값을 로그·본문에 다시 싣지 않는다.
- **`minDisplay`·`maxDisplay`를 붙이는 곳은 calc다.** plan의 `ConditionValidator`(#17)는 `min`·`max`·`unit`·`scale`만 담은 `plan.ConditionViolationException`을 던진다. `/internal/calc` 컨트롤러가 있는 `calc.web`(#32)이 이 예외를 잡아 `Formatter`로 `minDisplay`·`maxDisplay`를 붙이고 `calc.CalcRejectedException`으로 다시 던진다. 왜: plan은 다른 모듈에 의존하지 않고(`allowedDependencies = {}`) 의존 방향이 calc → plan이라, plan이 calc의 `Formatter`를 부르면 순환 의존이 되어 `ModularityTests`가 실패한다.
- 그래서 `minDisplay`·`maxDisplay`는 이 경로에서만 보장한다. 같은 `ConditionValidator`를 쓰는 프론트 API(#30)의 오류 본문에는 없다(프론트는 `min`·`max`·`unit`과 자기 화면 단위로 표시한다).
- #32는 #28(Formatter)에 의존하므로 `/internal/calc`가 동작하는 시점에는 항상 이 두 필드가 있다. ai-server는 범위를 말할 때 `minDisplay`·`maxDisplay`만 인용한다(원 → 만원 나눗셈 금지). 방어 규칙: 그래도 없으면(계약 위반) 숫자 없이 "허용 범위를 벗어났어요"라고만 말하고 다시 묻는다.
- **`etfProductCode`의 `OUT_OF_RANGE`는 계약 위반이 아니다**(#160). 범위가 아니라 목록이라 `min`·`max`·`minDisplay`·`maxDisplay`가 없다. ai-server는 범위를 말하지 않고 "그 종목은 계산할 수 없어요"처럼 되묻는다(문장은 B팀 후속).

예시는 부록 A-2~A-5.

### C3-2. 코드 표

| HTTP | `code` | 언제 | `calc_run` | ai-server 동작 |
|---|---|---|---|---|
| 422 | `VALIDATION_FAILED` | C2-2 검사 순서 5·6 | 없음 | **모델에 `{code, errors}`만** 넘겨 `CLARIFY`로 되묻는다(D-033). `minDisplay`·`maxDisplay`를 인용한다. **같은 요청 안에서는 `recalculate`를 다시 부르지 않는다**(코드 가드. "1,000만원으로 계산해 볼게요" 같은 자동 보정 금지) |
| 422 | `FIELD_NOT_CHANGEABLE` | 검사 순서 3. `errors[].reason = NOT_CHANGEABLE` | 없음 | **모델에 넘기지 않는다.** ai-server 코드가 분기해 문구표 고정 문장(`chat.unchangeableCondition`)으로 답한다: "목표 종류·기존 납입액은 여기서 바꿀 수 없어요. 보유 자산은 입력 수정에서 바꿀 수 있어요." `messageType` = **[D-056 확정]** `ANSWER` |
| 409 | `NO_PLAN` | 검사 순서 7 | 없음 | 모델에 넘기지 않고 고정 문장 "먼저 목표와 조건을 입력해 계획을 만들어 주세요"(문구표 키 `chat.noPlan`, #18), `ANSWER` |
| 409 | `ALREADY_EXCEEDED` | 검사 순서 8 | 없음 | 모델에 넘기지 않고 고정 문장("이미 가진 자산이 목표 금액을 넘었어요. 대화로 목표 금액을 더 높게 바꿔 볼 수 있어요.", 문구표 키 `chat.alreadyExceeded`, D-092), `ANSWER` |
| 400 | `MALFORMED_REQUEST` | 검사 순서 1·4 | 없음 | `AgentError("CALC_BAD_REQUEST")` → Spring `FAILED`(FR-AI-04). ai-server 버그라 되물어도 고칠 수 없다 |
| 401 | `INVALID_INTERNAL_KEY` | 검사 순서 0 | 없음 | `AgentError("CALC_UNAUTHORIZED")` → `FAILED` |
| 500 | `CALC_FAILED` | 엔진 예외, 기준 데이터 없음 등 | 없음(롤백) | `AgentError("CALC_FAILED")` → `FAILED` |

- `chat.noPlan`·`chat.alreadyExceeded`는 planContext 경로(계획 없음, 초과 계획 EXPLAIN)도 **같은 키**를 쓴다. 두 경로의 사용자 문장이 글자까지 같다.
- 보유 자산·목표 종류·기존 납입액 변경은 두 겹으로 막는다: (1) ai-server 도구 스키마에 허용 키만 있고(#21, 종목 키는 #160 B팀 후속), 프롬프트가 변경 불가 조건 의도에 위 고정 문장으로 답한다. (2) 그래도 들어오면 Spring이 `FIELD_NOT_CHANGEABLE`로 거절한다. **두 경로의 사용자 결과(같은 문장, 같은 `messageType`, 비교 없음)는 같아야 한다.**
- `ALREADY_EXCEEDED`를 409로 둔 이유: 양쪽(`current`·`changed`) 모두 초과이면 비교가 의미 없기 때문이다(D-092). 한쪽만 초과인 비대칭 비교는 `200`으로 내려준다. HTTP 숫자보다 `code`가 계약의 기준이다.
- 이 문서가 확정하는 코드는 위 7개뿐이다.

### C3-3. 범위 수치의 출처 (수치는 임시)

- 허용 범위는 **`backend/src/main/resources/config/rules.yml` › `input.fields.<FIELD>.{required,min,max,unit,scale}`** 한 곳에 두고 plan 모듈 `@ConfigurationProperties`로 읽는다. 대화 경로 허용 키는 `rules.yml › change-paths.CHAT.fields`(숫자 키 5개. `etfProductCode`는 범위 대신 활성 종목 목록으로 검사, #157). 검사는 `ConditionValidator.validate(ChangePath.CHAT, values)`. `InputField` enum ↔ camelCase 키 매핑을 둔다.
- 지금은 Spring 전용이다. `db/moved-to-code.md`의 "두 서버가 함께 읽는 `config/rules.yml`"은 이 위치로 고친다(ai-server는 오류 본문으로 받으므로 읽을 필요가 없다). 필요해지면 repo 루트로 옮긴다.
- 오류 본문의 `min`·`max`·`unit`·`scale`은 **이 설정 값을 그대로** 내보낸다. **ai-server는 범위 수치를 하드코딩하지 않는다.**
- 지금 쓸 값(**임시**, TBD-09·TBD-10 확정 시 `rules.yml`만 바꾼다):

| 키 | min | max | unit | scale | 출처 |
|---|---|---|---|---|---|
| `monthlySavingAmount` | 10000 | 10000000 | KRW | 0 | max: TBD-09 "월저축액 상한 1000만원". min: 임시 1만원(TBD-39 넛지 문구에서 따옴, 미결) |
| `savingsRatioPct` | 0 | 100 | PCT | 0 | `CalcInput`·`schema.sql` `ck_pv_ratio`. 변경 단위(TBD-10)는 대화 경로에 적용하지 않음 |
| `savingsRateInputPct` | 0 | 20 | PCT | 2 | scale: TBD-09 "금리 둘째자리". max: 임시 |
| `targetPeriodMonths` | 1 | 360 | MONTH | 0 | min: `CalcInput`(> 0). max: 임시 30년(TBD-10·TBD-39 미결) |

- 원 단위 정수로 검증한다. 화면 입력 단위(만원)는 프론트에서만 바꾼다.

### C3-4. ai-server 처리 규칙 (`CalcClient`, 도구 결과)

| Spring 응답 | 계약 |
|---|---|
| 200 | `calcRunId` 정수 검사. **[전환기]** `display` 검사(C2-6) 실패면 `AgentError("CALC_DISPLAY_MISSING")`. 모델에 넘기는 도구 결과는 **추린 것**: `display`, `diff`(델타 숫자 포함, 판단용), `conditions`, `current`·`changed`의 `scenarios[].{scenario,isAchieved}`·`baselineScenario`, `assumptions.isDefaultSavingsRate`. raw 금액은 판단용이며 인용하지 않는다(원칙 1) |
| 422, 409 | 본문을 파싱해 `CalcRejected(code, errors)`(구조화 예외)로 올린다. `VALIDATION_FAILED`만 모델에 `{code, errors}`로 넘기고, 나머지 세 코드는 ai-server 코드가 고정 문장으로 끝낸다(C3-2). 본문을 문자열로 자르지 않는다 |
| 400, 401, 5xx | `AgentError(code)`로 요청 실패 → Spring `FAILED`·`FAILURE`(FR-AI-04). 모델에 넘기지 않는다 |
| 타임아웃·연결 실패 | `AgentError("CALC_UNAVAILABLE")` → `FAILED` |

- 되묻기 뒤 사용자가 새 값을 말하면 다음 `/chat` 요청에서 새로 `recalculate`를 부른다. 이때도 기준은 현재 계획이다(C2-8).

### C3-5. 적용 범위

- 같은 오류 형식(C3-1)을 프론트 ↔ Spring API에도 쓴다(전역 `@RestControllerAdvice`가 함께 덮는다). 코드는 각 계약에서 더한다(#12: `PLAN_CHANGED`, `UNAUTHENTICATED` 등).
- 단 `errors[].minDisplay`·`maxDisplay`는 프론트 API에는 없다(C3-1).
- 프론트 API의 401(JWT 만료)은 리소스 서버 `AuthenticationEntryPoint`가 따로 내므로 **#12에서 401 본문 형식을 이 형식에 맞춘다.**

---

## 부록 A. 예시 JSON (fixture)

모두 `docs/api/examples/internal-calc/`에 있다. 줄임표 없는 완전한 JSON이며 숫자는 형식 예시다(엔진 결과 아님). 단 **서로 맞도록** 만들었다: `afterTaxAsset = principal + afterTaxGain`, `gapToGoal = afterTaxAsset − goalAmount`, `diff = changed − current`. 계획: 목표 2,000만원, 보유 자산 500만원, 월 30만원, 36개월, 적금 금리 미입력(기본 3.12%), 비중 미입력(5:5), 시작월 2026-11.

| 번호 | 상황 | 요청 | 응답 |
|---|---|---|---|
| A-1 | 200 성공 — 월 저축액 30만원 → 50만원 | `success-request.json` | `success-response.json` |
| A-2 | 422 `VALIDATION_FAILED` — 월 저축액 상한 초과 | `validation-failed-out-of-range-request.json` | `validation-failed-out-of-range-response.json` |
| A-3 | 422 `VALIDATION_FAILED` — 여러 필드(모두 나열) | `validation-failed-multi-request.json` | `validation-failed-multi-response.json` |
| A-4 | 422 `FIELD_NOT_CHANGEABLE` — 목표 금액(허용 키가 섞여도 전체 거절) | `field-not-changeable-request.json` | `field-not-changeable-response.json` |
| A-5 | 409 `NO_PLAN` | — | `no-plan-response.json` |
| A-5 | 409 `ALREADY_EXCEEDED` | — | `already-exceeded-response.json` |
| A-5 | 400 `MALFORMED_REQUEST` | — | `malformed-request-response.json` |
| A-6 | 422 `VALIDATION_FAILED` — 없는 ETF 종목 코드(#160) | `validation-failed-unknown-etf-request.json` | `validation-failed-unknown-etf-response.json` |
| A-7 | 400 `MALFORMED_REQUEST` — 종목 코드가 문자열이 아님(#160) | `malformed-etf-code-request.json` | `malformed-request-response.json` |
| A-5 | 401 `INVALID_INTERNAL_KEY` (필터가 쓰므로 `instance` 없음) | — | `invalid-internal-key-response.json` |
| A-5 | 500 `CALC_FAILED` | — | `calc-failed-response.json` |

- A-1에서 `current.*.goalReachMonth`가 모두 `null`이라 그 `display` 키는 없다.
- 오류 `title` 문구는 임시이며 #18 이후 문구표로 옮긴다. ai-server는 `title`을 쓰지 않는다.
- fixture를 고칠 때는 위 산술 관계와 C2-6 키 규칙(응답에 있는 시나리오만, `goalReachMonth`가 `null`이면 키 없음)이 유지되는지 확인한다.
