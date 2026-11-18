# 팀 API 계약에 맞추기 + 프론트 Vite 이식 — 설계

작성일 2026-11-18. 기준은 develop의 `docs/api/frontend-api.md` 2절(C12a, 2026-11-16 최종본, moeun #12)이다.
[A-2 설계](2026-11-16-a2-plan-auth-design.md)와 계약이 다른 곳을 새로 발견하면 **임의로 정하지 않고 물어본다.** 이 문서에 적힌 차이는 이미 확인을 받은 것이다.

## 왜

`feat/a2-plan-auth`는 2026-11-16에 우리 설계 문서만 보고 만들어졌다. 같은 날 팀은 기획 repo에서 프론트↔Spring 계약을 확정했고 11-17에 develop으로 들어왔다. 두 결과물이 서로를 모른 채 자랐다.

1. **프론트 스택이 다르다.** develop은 Vite + React Router, 우리는 Next App Router.
2. **API 계약이 다르다.** 메서드·쿠키·오류 본문·모듈 배치·검증 코드·범위 수치·목표 종류가 모두 어긋난다.
3. **입력 화면의 기능 범위가 다르다.** 우리가 만든 기존 저축·비중 선택과 '모르겠어요' 분기가 계약에 없다.

그냥 머지하면 겹치는 경로가 5개뿐이라 git은 충돌 5건만 보고하고 나머지 185개 파일을 한 폴더에 섞는다. Vite 진입점이 Next 라우트 폴더 안에 들어가 빌드도 라우팅도 깨진다.

## 목표 / 비목표 / 안티목표

- **목표** — 우리 몫(P-1 로그인, P-2 로그아웃, P-5 되돌리기, 오류 본문, 쿠키·토큰 연장, 검증)을 계약에 맞춘다.
- **목표** — 로그인 화면과 입력 화면을 develop의 Vite 뼈대 위에 계약대로 올린다.
- **목표** — `plan` 모듈의 저장·조회 서비스를 계약이 규정한 시그니처로 공개해 `calc.web` 담당자를 푼다.
- **비목표** — P-3 `PUT /api/plan`·P-4 `GET /api/plan` 컨트롤러. 계약상 `calc.web`이고 계산 엔진이 필요한데 A팀 다른 팀원 몫이다.
- **비목표** — `CalcEngine`·`ResultDisplayBuilder`·`calc_run`.
- **비목표** — **결과 화면(#49·#50)과 입력 수정 채우기(#51)는 다음 PR**(사용자 결정).
- **비목표** — chat·ai-server.
- **안티목표** — 두 프론트를 한 폴더에 섞어 두는 것.
- **안티목표** — 계약과 다른 걸 알면서 조용히 두는 것. 못 맞추는 건 PR에 적는다.

## 제약

- 계약 문서가 구현 기준이다. 다만 이 문서가 다루지 않은 차이가 나오면 멈추고 물어본다.
- 모듈 경계(`plan → {}`, `calc → {plan, refdata}`, `auth → {}`)는 이미 계약과 같다. `ModularityTests`를 깨지 않는다.
- Modulith 규칙상 모듈 루트만 공개 API다. 지금 `PlanService`는 `plan.application`에 있어 `calc.web`이 못 부른다.
- develop의 `frontend/src/api/client.ts`가 화면과 Spring 사이의 유일한 통로다. 화면은 `fetch`를 직접 부르지 않는다.
- 프론트는 절대 URL 없이 `/api/...` 상대 경로만 쓰고 Vite 프록시로 넘긴다(같은 사이트 전제).
- 문구는 저장소 루트 `copy/copy.ko.yml` 하나다. 백엔드 `CopyBook`이 없는 키면 기동에 실패한다.
- `plan_version.goal_type_code`에 코드가 저장된다. 목표 종류 변경은 #19 머지 전이 가장 싸다.

## 검토한 방법

1. **우리 Next 프론트로 통일** — develop의 112파일(chat 포함)을 되돌린다. 이미 머지되어 위에 PR이 여러 건 쌓였다. 탈락.
2. **프론트만 Vite로 옮기고 계약 차이는 나중에** — `code`가 없어 401 리다이렉트가 조용히 죽는다. 합쳐도 안 도는 상태가 남는다. 탈락.
3. **프론트 이식 + 우리 몫 계약 정렬** ← **채택.** P-3·P-4는 소유자에게 넘긴다.

## 정한 것

### 1. 브랜치

`origin/develop`에서 **새 브랜치**를 딴다. `feat/a2-plan-auth`는 지우지 않고 그대로 둔다(PR을 열지 않았다). 살릴 것만 골라 옮긴다.
`.gitattributes`에 `frontend/package-lock.json -diff merge=binary`를 넣는다. 락파일은 손으로 합치지 않는다.

### 2. 오류 본문 (전 모듈 공통)

계약 C3-1 하나로 통일한다. `ProblemDetail` + `code` + `errors[]`, 기계 판단은 `code`로만. 403은 쓰지 않는다.

| 상황 | HTTP | `code` |
|---|---|---|
| 쿠키 없음·서명 불일치·만료 | 401 | `UNAUTHENTICATED` |
| 로그인 아이디·비밀번호 틀림 | 401 | `INVALID_CREDENTIALS` |
| 입력 검증 실패 | **422** | `VALIDATION_FAILED` |
| JSON 파싱 실패·본문이 객체 아님 | 400 | `MALFORMED_REQUEST` |
| 계획 없음 | **409** | `NO_PLAN` |
| 되돌릴 것 없음 | 409 | `NOTHING_TO_UNDO` |
| revision 충돌 | 409 | `PLAN_CHANGED` |
| 그 밖 | 500 | `INTERNAL_ERROR` |

**`errors[]`를 다시 만든다.** `reason`은 `REQUIRED` · `OUT_OF_RANGE` · `NOT_INTEGER` · `TOO_MANY_DECIMALS` 넷뿐이고(변경 경로에 `NOT_CHANGEABLE`), `min`·`max`·`unit`·`scale`을 같이 싣는다. 지금의 `NOT_UNIT`·`INVALID_SCALE`·`TOO_LONG`·`BLANK`·`ONE_OF_PERIOD_OR_SAVING`·`EXISTING_EXCEEDS_SAVING`·`RATIO_CHOICE_REQUIRED`는 없앤다. 필드 이름은 요청 키(camelCase).
요청 본문은 `Map<String, JsonNode>`로 받는다. 키 없음과 `null`을 구분해야 하고 `300000.5`가 잘리면 안 된다.

### 3. `rules.yml` 수치를 계약 C12a-5-5로 교체

| 필드 | min | max | unit | scale |
|---|---|---|---|---|
| `monthlySavingAmount` | 10000 | 10000000 | KRW | 0 |
| `savingsRateInputPct` | 0 | **20** | **PCT** | 2 |
| `targetPeriodMonths` | 1 | **360** | MONTH | 0 |
| `goalAmount` | 10000 | **10000000000** | KRW | 0 |
| `heldAssets` | 0 | **10000000000** | KRW | 0 |
| `goalCustomName` | 1 | 100 | CHAR | — |

**금액 `step`을 없앤다.** 계약이 "`rules.yml`에 금액 step은 두지 않는다. 서버는 원 정수면 받는다"로 못박았다. `base-savings-ratio-pct`와 `goal-defaults`도 지운다(아래 5·6).

### 4. 인증 (P-1, P-2)

- 쿠키 이름 `MOEUN_TOKEN` → **`moeun_at`**. `Max-Age` 1800, `Path=/`, `HttpOnly`, `SameSite=Lax`.
- `Secure` **기본 `true`**, `local` 프로필만 `false`(지금은 반대).
- 쿠키만 읽는 `BearerTokenResolver`. `Authorization` 헤더는 받지 않는다. **`POST /api/auth/login`에서는 `null`을 돌려준다** — 깨진 쿠키가 남은 브라우저가 로그인조차 못 하는 것을 막는다.
- 401 엔트리 포인트가 본문과 함께 `moeun_at` 만료 쿠키(`Max-Age=0`)를 내린다.
- 로그인 응답 `{loginId, hasPlan}` → **`{}`**. 로그인 실패는 `401 INVALID_CREDENTIALS`(화면 유지).
- 연장: 로그인·로그아웃은 `@SkipRenewal`, 그 밖 인증된 `/api/**`는 인터셉터.
- **`GET /api/auth/me`를 없앤다.** 계약이 명시적으로 두지 않기로 했다. 앱 시작·새로고침은 P-4 하나로 401 / 409 / 200이 갈린다.
- **`MemberLoggedIn` / `MemberLoggedOut`을 발행한다**(쿠키 전, 동기 리스너). chat이 이전 대화를 지운다. 지금은 아무것도 발행하지 않는다.

### 5. 입력 범위 (CF-08 제거)

**기존 저축과 비중 선택을 입력 화면에서 뺀다.** 계약 C12a-5-1: 요청에 `existingMonthlyContribution`은 항상 `NULL`, `savingsRatioPct`는 현재 버전 복사(첫 저장이면 `NULL` = 5:5). 비중을 바꾸는 건 대안·비교 **적용** 경로다.

없애는 것: `ExistingSavingsSection`, `savingsHelpers.keepCurrentRatio`, `formState`의 `existingOn`·`existingSavings`·`existingEtf`·`ratioChoice`, `validateForm.validateExisting`, `PlanInput`의 기존 저축 두 필드와 `ratioChoice`, `PlanInputValidator.checkExisting`·`resolveSavingsRatioPct`, `RatioChoice` enum, DB의 `existing_savings_monthly`·`existing_etf_monthly`(develop의 `existing_monthly_contribution` 한 칸으로 되돌림), 그리고 [2026-11-17 CF-08 재정의 설계](2026-11-17-keep-current-savings-design.md).
`goal_custom_name` 100자(D-074)는 유지한다.

**`periodKnown`·`savingKnown` 분기도 없앤다.** 계약 D-045로 기간과 월 저축액이 둘 다 필수다. `ONE_OF_PERIOD_OR_SAVING`도 사라진다.

### 6. 목표 종류와 기본값

`GoalType`을 계약 D-075의 7개로 바꾼다: `HOUSE, CAR, WEDDING, EDUCATION, TRAVEL, EMERGENCY_FUND, CUSTOM`(선언 순서 = 드롭다운 순서). 표시명은 `copy/copy.ko.yml › goal.<CODE>`에 이미 있다. `CUSTOM`은 FR-INP-02 미구현 동안 화면에서 숨긴다(코드는 둔다).

**목표를 골라도 금액·기간을 자동으로 채우지 않는다**(사용자 결정). 빈 칸으로 시작한다. 계약에 목표별 기본값이 없고 프론트가 지어낼 숫자가 아니다.
없애는 것: `rules.yml › goal-defaults`, `applyGoalChoice`의 기본값 적용, `autoFillMonthlySaving`, `initialMonthlySavingManwon`, `monthlySavingTouched`, `InputRulesView`의 `goalTypes` 기본값, 관련 테스트.

### 7. 모듈 배치와 공개 서비스

- `plan.web`의 `GET`·`POST /api/plan` 컨트롤러를 **걷어낸다**(계약상 `calc.web` 몫).
- `plan.web`에는 P-5 `POST /api/plan/undo`만 남긴다. 응답은 **`{revision}`**.
- **P-5를 D-068 방식으로 바꾼다**: 직전 버전을 복사 INSERT + `previous_version_id = NULL` + revision 검사 UPDATE. 지금의 포인터 교체(`restorePrevious`)를 버린다. `NO_PLAN`·`NOTHING_TO_UNDO`·`PLAN_CHANGED`를 각각 다른 `code`로 내려보낸다(지금은 셋을 409 하나로 뭉갠다).
- P-6 `POST /api/plan/apply`는 계약이 경로만 예약했다(#68 미확정). 구현을 남기되 주석과 PR에 적는다.
- **`plan` 루트에 계약 시그니처대로 공개 서비스를 올린다**: `PlanSaveService.save(long memberId, PlanInput, Long expectedRevision) → PlanSaveResult(planVersionId, revision)`, 현재 계획 조회(`currentVersionId` 포함), `PlanApplyService`. 인자·반환에 `calc`·`chat` 타입을 두지 않는다.
- 저장 의미도 계약대로 바꾼다: 입력 저장에도 `expectedRevision`을 검사하고(지금은 `overwriteCurrent`가 무시한다), 첫 저장은 네이티브 `INSERT … ON CONFLICT (member_id) DO NOTHING`, 수정은 revision 검사 UPDATE에 0행이면 `PlanChangedException`, 네이티브 쓰기 뒤 `@Modifying(flushAutomatically, clearAutomatically)`.

### 8. 프론트 이식

- `frontend/`는 **develop 것이 기준**이다. 우리 `next.config.ts`·`src/app/**` 라우트·`package.json`·`tsconfig.json`은 버린다.
- `src/lib/api/client.ts`·`endpoints.ts`를 **버리고** develop의 `api.request(method, path, body)`를 쓴다. 분기를 `kind`/`code` 기준으로 다시 쓴다.
- **`src/lib/format.ts`를 지운다.** 계약 원칙 1: 화면의 모든 숫자 문자열은 Spring `display`를 그대로 쓴다. 프론트가 하는 변환은 입력칸 만원↔원, 'n년 n개월'↔개월, 422의 `min`/`max`/`unit`을 안내 문구로 바꾸기뿐이고 **util 파일 하나**에 둔다.
- **`src/copy/ko.ts`를 지우고** `copy/copy.ko.yml`을 쓴다. chat이 하듯 미러 상수 + yml과 비교하는 테스트를 둔다.
- `"use client"` 7개 지시문 삭제, `next/navigation` 8개 → `react-router-dom`.
- **범위 수치를 프론트에 두지 않는다.** 계약 C12a-5-4: "프론트 사전 검사는 '비어 있음'과 입력 형식만. 범위 수치는 하드코딩하지 않고 422의 `min`·`max`로 안내한다." `getInputRules()`·`InputRules`·`validateForm`의 범위 검사를 걷어낸다.
- 인증 게이트와 첫 진입을 `me` 기준에서 **`planStore.loadPlan()`(P-4) 기준**으로 다시 짠다. 401 → 로그인, `409 NO_PLAN` → 빈 입력 화면, 200 → 결과.
- Tailwind 4와 `@theme` 토큰을 Vite로 옮긴다. develop에 Tailwind가 없어 의존성이 는다 — **팀에 알린다.**
- 화면 범위: **로그인(#47)과 입력(#48, 새 목표 세우기)까지.** 결과(#49·#50)와 입력 수정 채우기(#51)는 다음 PR.

## 남는 공백 (PR에 적는다)

- **실서버 모드로는 저장·조회가 안 된다.** P-3·P-4가 `calc.web` 몫이라 아직 없다. 프론트는 계약대로 부르고, 그쪽이 들어오면 붙는다.
- 그동안 **`VITE_API_MODE=mock`으로는 전부 동작한다.** develop에 목 JSON이 이미 있다.
- P-6 적용은 계약 미확정(#68). P-5는 이번 PR에서 화면 진입점이 없다(적용 UI가 결과 화면과 함께 다음 PR).
- 팀원 3명이 기능별로 프론트를 나눠 맡는 구조다. 이 PR은 **우리가 맡은 인증·계획 입력의 화면**만 건드린다. #49~#51이 다른 사람 몫이면 그대로 비워 둔다.

## 구현자 재량

- 공개 plan 서비스의 패키지·클래스 이름(시그니처는 계약이 정했다).
- `code`를 `ProblemDetail`에 싣는 방법(공통 `@RestControllerAdvice` 대 모듈별).
- 입력 변환 util의 위치. 단 `features/input/**` 밖에 두어 #48과 #51이 서로의 파일을 고치지 않게 한다(계약 요구).
- Tailwind를 Vite에 붙이는 방식.
- 오류 본문을 검증하는 테스트 헬퍼.
