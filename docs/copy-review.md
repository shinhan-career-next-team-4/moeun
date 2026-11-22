# 문구·추천 금지·면책·과거 수익률 고지 점검표

Issue #59 (T-051) · 요구사항 CP-05, CP-06, NFR-USA-01, NFR-USA-02, FR-RES-07, D-028(CF-07 이후), D-029

상태: **점검 완료, 일부 기준은 충족하지 못함**(점검표 7·8·9행, 아래 "열린 항목"). 새 기능은 넣지 않았다. 문구표(`copy/copy.ko.yml`)는 이번 점검에서 고칠 곳이 없었다. 수용 기준 3(하드코딩된 고정 문구가 남아 있지 않다)은 **충족하지 못했고**, 남은 것을 인벤토리와 열린 항목에 적었다.

## 점검 범위와 해석

- "모든 결과 상태 화면"은 **결과가 나온 상태**로 본다: 정상(3구간), ETF 0%(평균 1개), 이미 목표 초과, 대화 적용 뒤, 되돌린 뒤. 불러오기 실패·로딩 화면은 결과 숫자가 없어 면책·과거 수익률 고지 대상이 아니라고 해석했다(팀 확인 필요).
- 수용 기준 2는 CF-07(D-028 개정, #168) 이후 정책으로 읽는다: **개별 주식 종목명·종목 추천 표현은 금지, ETF 상품명은 허용**, 계획 종목은 계획의 표시값(`assumptions.etfProductName`, 없으면 'S&P500 추종 ETF')과 일치.
- 선행 #56(3서버 통합)은 연동된 화면 전체의 마지막 확인에만 필요하다. 그 확인은 아래 "#56 이후 재확인"에 남겼다. 이 점검은 문구표·컴포넌트·테스트·코드 정적 확인이다.

## 점검표

| # | 항목 | 결과 | 근거 |
|---|---|---|---|
| 1 | 결과가 나온 모든 상태에서 맨 아래 면책 문구 | 통과 | `ResultPage.tsx`는 `<Disclaimer />`를 본문 컨테이너 마지막에 한 번 그린다. 5개 상태 모두 확인: `features/result/__tests__/notices.test.tsx`(새 파일) |
| 2 | 면책에 다섯 가지 내용이 모두 있음(D-066) | 통과(정상 상태) | `result.test.tsx`의 "(W-19) 면책 문구에 다섯 가지가 모두 들어 있다". 정상 상태 목 하나만 확인한다. 같은 컴포넌트·같은 상수라 다른 상태도 같지만 따로 검증하지는 않는다 |
| 3 | 대화창 고지가 결과 화면 면책과 같은 키 하나 | 통과 | 문구표 키 `disclaimer` 하나다. `ChatWindow.tsx`는 `features/chat/copy.ts`의 미러 상수를 쓰고(`shared/copy/copy.ts`와 별개), 화면 문장이 문구표 파일과 같음은 `chat/__tests__/mockFlow.test.tsx`·`chatWindow.test.tsx`·`exceeded.test.tsx`가 확인한다 |
| 4 | 3구간 카드 아래 과거 수익률 고지 | 통과 | `ScenarioCards.tsx`의 마지막 `<p>`가 `result.scenarioNotice`. 카드가 있는 4개 상태(정상·대화 적용 뒤·되돌린 뒤는 3구간, ETF 0%는 평균 1개)에서 카드보다 뒤에 있음을 확인(`notices.test.tsx`). 이미 초과한 계획은 카드가 없어 고지도 없다(의도) |
| 5 | 문체는 해요체, 격식체는 면책만 | 통과 | 문구표 157개 중 격식체 어미가 있는 문구는 `disclaimer` 하나. 회귀 테스트: `ai-server/tests/copybook/test_copy_rules.py` |
| 6 | 금지 표현은 개별 주식 추천뿐(CF-07) | 통과 | 문구표 값 157개 중 '추천'이 있는 것은 `alt.header` 하나이고 부정문("서비스는 하나를 추천하지 않아요")이다. 면책은 '추천'이 아니라 '권유'를 쓴다("가입을 권유하지 않으며"). 막는 것은 `(종목\|주식)을/를 추천` 패턴이다: `ai-server/tests/test_copy.py`의 `test_copy_has_no_stock_recommendation`, 백엔드 `CopyBookTest`(같은 패턴). 부정문인지까지는 확인하지 않는다 |
| 7 | AI 대화 본문에 개별 주식 종목명·종목 추천이 없음 | **부분 통과** | 서버가 막는 것은 **추천 낱말이 있는 본문의 종목 추천**이다(`assemble.check_stock_recommendation`은 `_RECOMMENDATION_WORDS`가 있을 때만 판정 모델을 부르고, `test_guardrails.py`가 확인). **종목명 단독 언급**은 서버 규칙이 없다(`assemble.py` 주석: 종목명은 열린 집합). 실제 LLM 샘플 실행에서 위반 0건(`docs/demo-scenarios.md`, #57)이지만 샘플이다 |
| 8 | AI 대화 본문의 계획 종목 표기가 계획의 표시값과 일치 | **부분 통과** | 보장되는 것은 **허용 ETF 표현 세 개(코스피200·S&P500·나스닥 추종 ETF) 사이의 바꿔치기**뿐이다(`assemble.check_plan_etf_claims`, 계획을 가리키는 낱말이 있는 문장만). 계획과 다른 **브랜드명은 정책상 그대로 통과**한다(`test_etf_name_correction.py`의 `test_brand_product_name_passes_unchanged_in_a_plan_answer`). ETF 0% 계획은 별도 규칙(`test_etf0_clarify.py`, `test_etf_out_of_scope.py`)이다 |
| 9 | 문구표 밖에 고정 문구가 하드코딩돼 있지 않음 | **미충족** | ai-server·백엔드·프론트 모두에 사용자에게 보이는 고정 문구가 남아 있다. 아래 "하드코딩 인벤토리" |
| 10 | 프론트가 문구표를 옮긴 상수가 문구표와 같음 | 통과 | 상수를 파일과 비교하는 테스트: `shared/copy/__tests__/copy.test.ts`, `chat/comparison/__tests__/copy.test.ts`, `chat/suggestions/__tests__/copy.test.ts`. 화면 문장 비교: `mockFlow`·`chatWindow` 테스트. (`chat/__tests__/sources.test.ts`는 키 존재와 옛 키 미사용만 확인한다) |

## 하드코딩 인벤토리

스캔 방법은 맨 아래 "다시 점검하려면". 주석·Javadoc에 같은 문장이 있는 경우는 제외했다. 아래 문장 중 문구표 값과 **글자까지 같은 것은 드물고**, 대부분 비슷하지만 다른 문장이다(마침표, 어미, 낱말 차이).

### ai-server

| 위치 | 내용 | 사용자 노출 | 판단 |
|---|---|---|---|
| `app/llm/assemble.py:143` `out_of_scope_content` | 범위 밖 안내 뒤에 붙는 `" 대화로는 {조건 이름}을 바꿔 볼 수 있어요."` | **예**(OUT_OF_SCOPE 응답 본문) | 앞부분 `outOfScope`는 문구표인데 이 조각은 문구표에 키가 없다. 조건 이름 다섯 개는 문구표 키(`condition.*`)에서 가져온다 |
| `app/llm/assemble.py` `_UNCHANGEABLE_ANCHOR` | `chat.unchangeableCondition`의 마지막 문장을 그대로 적은 감지 앵커 | 아니오 | 감지용. 문구표를 고치면 어긋나므로 `tests/copybook/test_copy_rules.py`가 감지 |
| `app/llm/prompts.py` 42·72줄 | 모델에게 주는 지시문 안의 예시 문장 | 아니오 | 모델용. 문구표를 고치면 예시가 옛 문장이 된다 |

### 백엔드

| 위치 | 내용 | 사용자 노출 | 판단 |
|---|---|---|---|
| `calc/web/InternalCalcExceptionHandler.java:43-46` `TITLES` | 거절 코드별 제목 4개 | 아니오(소비자가 `title`을 쓰지 않음) | 문구표 `error.title.*`와 **문장이 다르다**(예: `입력 범위를 벗어났어요` / 문구표 `입력값을 확인해 주세요`, `계획이 없어요` / `아직 계획이 없어요`) |
| `calc/web/InternalCalcExceptionHandler.java:91,99`, `PlanResultExceptionHandler.java:25·33`, `GlobalExceptionHandler.java:36·44·70`, `ConditionViolationException.java:18`, `InternalApiKeyFilter.java:24`, `AuthController.java:32`, `UnauthenticatedEntryPoint`, `NoPlanException`·`NothingToUndoException`·`PlanChangedException`·`StaleBaseVersionException`·`MalformedUndoRequestException` | `problem+json`의 `title`/`detail` 문장 | 아니오(위와 같음) | 문구표 문장과 비슷하지만 다르다(예: `입력값을 확인해 주세요.` 마침표 차이, `아이디 또는 비밀번호를 확인해 주세요.` / 문구표 `...맞지 않아요`, `내부 인증에 실패했어요` / `내부 호출 인증에 실패했어요`) |
| `calc/application/Formatter.java:34·81·85·89·130` | 표시 문자열 조각(`1만원 미만`, `만원`, `약 N만원`, `N년 N개월`, `N년 N월`) | **예**(`display` 값) | 문구표에 `unit.manwon`·`unit.month`가 이미 있다. 표시 형식(D-055)은 별도 계약이라 문구표로 옮길지 정해야 한다 |

### 프론트 — `features/**/*.tsx`의 문구표 밖 한글 49곳(테스트 제외)

| 종류 | 수 | 예 | 판단 |
|---|---|---|---|
| `aria-label` | 11 | `aria-label="내 계획 조건"` | 접근성 라벨. 화면 텍스트와 같은 말이면 같이 옮기는 것이 좋다 |
| 플레이스홀더 | 5 | `placeholder="아이디를 입력해 주세요"` | 안내 문장 |
| 입력 도움말 | 5 | `필수 · 1만 원 ~ 100억 원`, `필수 · 1개월 ~ 30년` | 안내 문장이면서 **범위 숫자를 문장에 적어 둔 것**이라 검증 규칙(`validation.*`, 백엔드)과 어긋날 수 있다 |
| 안내 문장·토스트 | 4 | `UndoToast`의 `조건을 적용했어요.`, `LoginPage`의 `모은 목표 저축 시뮬레이션을 시작해요.`, `PlanInputPage`의 `목표를 먼저 정하면, 필요한 정보만 이어서 물어볼게요.`·`목표는 1개만 선택할 수 있어요` | 사용자에게 보이는 고정 문장 |
| 제목·라벨 | 24 | `FormCard title="목표"`, `label="현재 조건"` | 화면 라벨. 문구표 범위(`_FIXED_KEYS`)에 없다 |

스캔 범위 밖에서 추가로 확인된 것:

- `app/App.tsx:33·36·40·42`: `모은`, ` 님`, `로그아웃`(헤더 텍스트)
- `features/alternative/demoAlternatives.ts:11-31`: 대안 카드의 데모 문장과 숫자(`월 120만원 → 121만원`, `약 5,017만원` 등). 목 모드에서만 쓴다고 파일 주석에 적혀 있다
- `shared/units/units.ts:80·84·97·103`: 문구표 키가 없을 때 쓰는 대체 문장
- 사용자에게 보이지 않는 것: `features/input/formState.ts:88`의 `throw new Error("금액 형식이 올바르지 않습니다")`는 개발자용 예외다(검증 단계에서 이미 `COPY["input.invalidAmountFormat"]`로 막힌다). `features/chat/copy.ts`, `chat/comparison/copy.ts`, `chat/suggestions/copy.ts`는 문구표를 옮긴 미러 상수이고 테스트가 비교한다

## 열린 항목(팀 결정 필요)

1. **AI 본문의 종목명 단독 언급**: 서버 규칙이 없고 샘플 실행으로만 확인했다(점검표 7행). 종목명 목록을 쓰는 규칙은 열린 집합이라 어렵다. 지금 정책(추천 낱말이 있을 때만 판정)을 유지할지 정한다.
2. **계획 종목과 다른 브랜드명**: 정책(#168)상 통과한다(점검표 8행). 수용 기준 2의 "계획 종목은 표시값과 일치"를 허용 ETF 표현 사이의 바꿔치기로 한정해도 되는지 정한다.
3. **프론트 안내 문장·도움말·플레이스홀더 14곳(도움말 5 + 플레이스홀더 5 + 안내 문장 4)을 문구표로 옮길지.** 문구표는 Spring·ai-server·프론트가 같은 파일을 읽고 Spring과 ai-server는 필수 키 목록(`REQUIRED_KEYS`)으로 검증하므로, 키를 더하는 일은 세 곳의 로더·테스트를 함께 확인해야 한다. 제안: 문장형만 옮기고 `aria-label`·제목·라벨은 문구표 범위 밖으로 둔다는 기준을 먼저 정한다.
4. **ai-server `out_of_scope_content`의 고정 조각**(`대화로는 … 바꿔 볼 수 있어요.`)을 문구표 키로 만들지. 사용자에게 보이는 문장이다.
5. **백엔드 `title`/`detail`을 문구표에서 읽게 할지**, 아니면 "소비자가 쓰지 않는 값"이라 두고 문구표 `error.title.*`와 문장을 맞출지. `Formatter`의 표시 조각(`만원`, `N년 N개월`)을 `unit.*`로 옮길지는 표시 형식 계약(D-055)과 함께 정한다.
6. **`UNCHANGEABLE_ANCHOR`와 프롬프트 예시 문장을 문구표에서 가져오게 할지.** 지금은 감지 테스트만 있다. 앵커를 없애면 그 테스트도 함께 바꿔야 한다.
7. **면책·고지의 대상 화면**: 불러오기 실패·로딩 화면은 제외하는 해석(위 "점검 범위")이 맞는지.

## #56 이후 재확인

실제 Spring 응답으로 연동된 화면에서 아래를 다시 눈으로 확인한다.

- [ ] 결과 화면 정상·ETF 0%·이미 초과 상태에서 맨 아래 면책 문구
- [ ] 3구간 카드 아래 과거 수익률 고지(세 구간 모두 표시되는 계획)
- [ ] 대화 응답 본문(상품명·종목 표기, 세금 단서, 면책 고지)
- [ ] 문구표 문장이 Spring이 만든 `display`·오류 응답과 어긋나지 않는지

## 다시 점검하려면

```bash
# 프론트: 면책·과거 수익률 고지 회귀 테스트와 전체 테스트
cd frontend && npx vitest run src/features/result/__tests__/notices.test.tsx && npx vitest run

# ai-server: 문구표 말투·앵커 테스트와 전체 테스트
cd ai-server && uv run pytest tests/copybook && uv run pytest

# 하드코딩 후보(프론트, 테스트 제외). 주석·aria-label은 직접 거른다
grep -rnE ">[^<>{}]*[가-힣]{2,}[^<>{}]*<|(placeholder|title|alt|aria-label|label|help)=\"[^\"]*[가-힣]" \
  frontend/src/features --include='*.tsx' | grep -vE "__tests__|\.test\."
```
