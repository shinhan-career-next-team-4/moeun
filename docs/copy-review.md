# 문구·추천 금지·면책·과거 수익률 고지 점검표

Issue #59 (T-051) · 요구사항 CP-05, CP-06, NFR-USA-01, NFR-USA-02, FR-RES-07, D-028(CF-07 이후), D-029

상태: **점검 완료, 확인이 필요한 항목 있음**(아래 "열린 항목"). 새 기능은 넣지 않았다. 문구표(`copy/copy.ko.yml`)는 이번 점검에서 고칠 곳이 없었다.

## 점검 범위와 해석

- "모든 결과 상태 화면"은 **결과가 나온 상태**로 본다: 정상(3구간), ETF 0%(평균 1개), 이미 목표 초과, 대화 적용 뒤, 되돌린 뒤. 불러오기 실패·로딩 화면은 결과 숫자가 없어 면책·과거 수익률 고지 대상이 아니라고 해석했다(팀 확인 필요).
- 수용 기준 2는 CF-07(D-028 개정, #168) 이후 정책으로 읽는다: **개별 주식 종목명·종목 추천 표현은 금지, ETF 상품명은 허용**, 계획 종목은 계획의 표시값(`assumptions.etfProductName`, 없으면 'S&P500 추종 ETF')과 일치.
- 선행 #56(3서버 통합)은 연동된 화면 전체의 마지막 확인에만 필요하다. 그 확인은 아래 "#56 이후 재확인"에 남겼다. 이 점검은 문구표·컴포넌트·테스트·코드 정적 확인이다.

## 점검표

| # | 항목 | 결과 | 근거 |
|---|---|---|---|
| 1 | 결과가 나온 모든 상태에서 맨 아래 면책 문구 | 통과 | `ResultPage.tsx`는 `<Disclaimer />`를 본문 컨테이너 마지막에 한 번 그린다. 5개 상태 모두 확인: `features/result/__tests__/notices.test.tsx`(새 파일) |
| 2 | 면책에 다섯 가지 내용이 모두 있음(D-066) | 통과 | `result.test.tsx`의 "(W-19) 면책 문구에 다섯 가지가 모두 들어 있다" |
| 3 | 대화창 고지가 결과 화면 면책과 같은 키 하나 | 통과 | `ChatWindow.tsx`의 `chat-disclaimer`가 `COPY.disclaimer`를 쓴다. `chat/__tests__/mockFlow.test.tsx`, `exceeded.test.tsx` |
| 4 | 3구간 카드 아래 과거 수익률 고지 | 통과 | `ScenarioCards.tsx`의 마지막 `<p>`가 `result.scenarioNotice`. 3구간이 있는 4개 상태에서 카드보다 뒤에 있음을 확인(`notices.test.tsx`). 이미 초과한 계획은 3구간이 없어 고지도 없다(의도) |
| 5 | 문체는 해요체, 격식체는 면책만 | 통과 | 문구표 157개 중 격식체 어미가 있는 문구는 `disclaimer` 하나. 회귀 테스트: `ai-server/tests/copybook/test_copy_rules.py` |
| 6 | 금지 표현은 개별 주식 추천뿐(CF-07) | 통과 | `ai-server/tests/test_copy.py`("개별 주식 종목을 추천하는 표현만 금지"), 백엔드 `CopyBookTest`(같은 규칙). 문구표의 '추천'은 `alt.header`("서비스는 하나를 추천하지 않아요")와 면책("가입을 권유하지 않으며")뿐이고 둘 다 부정 표현 |
| 7 | AI 대화 본문에 개별 주식 종목명·종목 추천이 없음 | 통과 | 서버 검사 `assemble.check_stock_recommendation`(판정 모델)과 `test_guardrails.py`, 실제 LLM 시나리오 점검(`docs/demo-scenarios.md`, #57) |
| 8 | AI 대화 본문의 계획 종목 표기가 계획의 표시값과 일치 | 통과 | `assemble.check_plan_etf_claims`와 `test_etf_name_correction.py`, `test_etf_plan_name_claims.py` |
| 9 | 문구표 밖에 고정 문구가 하드코딩돼 있지 않음 | **일부 해당** | 사용자에게 직접 보이는 고정 문구는 백엔드·ai-server에서 발견되지 않았다. 프론트의 안내 문장·도움말과 몇몇 중복 문자열이 남아 있다. 아래 "하드코딩 인벤토리" |
| 10 | 프론트가 문구표를 옮긴 상수가 문구표와 같음 | 통과 | `shared/copy`, `chat/comparison`, `chat/suggestions`, `chat/__tests__/sources.test.ts`, `mocks.test.mjs`가 파일과 비교한다 |

## 하드코딩 인벤토리

스캔 방법은 맨 아래 "다시 점검하려면". 주석·Javadoc에 같은 문장이 있는 경우는 제외했다.

### ai-server — 사용자에게 보이는 고정 문구 없음

| 위치 | 내용 | 판단 |
|---|---|---|
| `app/llm/assemble.py` `_UNCHANGEABLE_ANCHOR` | 문구표 `chat.unchangeableCondition`의 마지막 문장을 그대로 적은 감지 앵커 | 출력이 아니라 감지용. 문구표를 고치면 어긋나므로 `tests/copybook/test_copy_rules.py`가 감지(`... still appears ...`) |
| `app/llm/prompts.py` 42·72줄 | 모델에게 주는 지시문 안의 예시 문장(`월 저축액을 바꿔 보고 싶어요`, `대화로 목표 금액을 더 높게 바꿔 볼 수 있어요`) | 모델용 지시문. 문구표 값과 같은 문장이라 문구표를 고치면 예시가 옛 문장이 된다 |

### 백엔드 — 응답 `title`·`detail`

`problem+json`의 `title`/`detail`에 문구표 `error.title.*`와 같은 문장이 5곳 하드코딩돼 있다(`InternalCalcExceptionHandler.java:99`, `PlanResultExceptionHandler.java:25·33`, `GlobalExceptionHandler.java:44`, `ConditionViolationException.java:18`). 소비자는 `title`을 쓰지 않고 `code`로 판단하므로(`ai-server/README.md` §오류 코드) 사용자 화면에는 나오지 않는다. 어긋나도 눈에 띄지 않아 혼란만 준다.

### 프론트 — 문구표 밖 한글 49곳(`features/**/*.tsx`, 테스트 제외)

| 종류 | 수 | 예 | 판단 |
|---|---|---|---|
| `aria-label` | 11 | `aria-label="내 계획 조건"` | 접근성 라벨. 화면 텍스트와 같은 말이면 같이 옮기는 것이 좋다 |
| 제목·라벨 | 25 | `FormCard title="목표"`, `label="현재 조건"` | 화면 라벨. 문구표 범위(`_FIXED_KEYS`)에 없다 |
| 입력 도움말(`help`) | 4 | `필수 · 1만 원 ~ 100억 원` | 안내 문장이면서 **범위 숫자를 문장에 적어 둔 것**이라 검증 규칙(`validation.*`, 백엔드)과 어긋날 수 있다 |
| 플레이스홀더 | 5 | `placeholder="아이디를 입력해 주세요"` | 안내 문장 |
| 안내 문장·토스트 | 4 | `UndoToast`의 `조건을 적용했어요.`, `LoginPage`의 `모은 목표 저축 시뮬레이션을 시작해요.`, `PlanInputPage`의 `목표를 먼저 정하면, 필요한 정보만 이어서 물어볼게요.`·`목표는 1개만 선택할 수 있어요` | 사용자에게 보이는 고정 문장 |

사용자에게 보이지 않는 것: `features/input/formState.ts:88`의 `throw new Error("금액 형식이 올바르지 않습니다")`는 개발자용 예외다(검증 단계에서 이미 `COPY["input.invalidAmountFormat"]`로 막힌다).

## 열린 항목(팀 결정 필요)

1. **프론트 "안내 문장·도움말·플레이스홀더"(도움말 4 + 플레이스홀더 5 + 안내 문장 4 = 13곳)를 문구표로 옮길지.** 문구표는 Spring·ai-server·프론트가 같은 파일을 읽고 Spring과 ai-server는 필수 키 목록(`REQUIRED_KEYS`)으로 검증하므로, 키를 더하는 일은 세 곳의 로더·테스트를 함께 확인해야 한다. 제안: 문장형만 옮기고 `aria-label`·제목·라벨은 문구표 범위 밖으로 둔다는 기준을 먼저 정한다.
2. **백엔드 `title`/`detail` 5곳을 문구표에서 읽게 할지**, 아니면 "소비자가 쓰지 않는 값"이라 두고 문구표 키 이름을 맞출지.
3. **`UNCHANGEABLE_ANCHOR`와 프롬프트 예시 문장을 문구표에서 가져오게 할지.** 지금은 감지 테스트만 있다.
4. **면책·고지의 대상 화면**: 불러오기 실패·로딩 화면은 제외하는 해석(위 "점검 범위")이 맞는지.

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
