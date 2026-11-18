# 백엔드 팀 계약 정렬 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A-2 백엔드를 팀 API 계약(C12a)에 맞춰, `calc.web`을 맡은 팀원이 바로 이어받을 수 있는 상태로 만든다.

**Architecture:** 도메인을 계약 크기로 줄이고(CF-08·'모르겠어요' 분기 제거), 오류 본문을 `ProblemDetail + code + errors[]` 하나로 통일한 뒤, `plan` 모듈 루트에 공개 서비스를 올리고 계약이 `calc.web`에 둔 P-3·P-4 컨트롤러를 걷어낸다.

**Tech Stack:** Java 21 · Spring Boot 4.1 · Spring Modulith 2.1 · PostgreSQL 16 · JUnit 5 · Testcontainers · REST Docs

**Spec:** [2026-11-18-contract-alignment-design.md](../specs/2026-11-18-contract-alignment-design.md) · 계약 원문 [docs/api/frontend-api.md](../../api/frontend-api.md) §2(C12a)

## Global Constraints

- 코드 스타일: 네이버 컨벤션, 스페이스 4칸, 줄 120자. `./gradlew checkstyleMain checkstyleTest`가 경고 0이어야 한다.
- `ddl-auto=validate`다. 엔티티와 `src/main/resources/db/schema.sql`이 다르면 기동에 실패한다. 테이블을 바꾸면 `db/` 문서도 같이 고친다.
- 모듈 경계는 `plan → {}`, `calc → {plan, refdata}`, `auth → {}`. `ModularityTests.verifyModules`를 깨지 않는다.
- 모듈 루트 패키지만 공개 API다. 다른 모듈이 쓸 타입은 하위 패키지에 두지 않는다.
- 오류 본문은 C3-1 하나: `ProblemDetail` + `code` + `errors[]`. 기계 판단은 `code`로만 한다. 403은 쓰지 않는다.
- `errors[].reason`은 `REQUIRED`·`OUT_OF_RANGE`·`NOT_INTEGER`·`TOO_MANY_DECIMALS`(변경 경로에 `NOT_CHANGEABLE`)뿐이다.
- 새 라이브러리·새 테이블·새 컬럼을 만들지 않는다.
- 이 계획이 다루지 않은 계약 차이를 발견하면 멈추고 물어본다. 임의로 정하지 않는다.

## Review Focus

- 소수 금액(`300000.5`)이 `ACCEPT_FLOAT_AS_INT`로 잘려 저장되는 것 → `NOT_INTEGER`로 거절해야 한다. Task 2에서 테스트한다.
- 만료·손상된 쿠키가 남은 브라우저에서 로그인이 401로 막히는 것 → 리졸버가 로그인 경로에서 `null`을 돌려줘야 한다. Task 4에서 테스트한다.
- 401을 내면서 쿠키를 지우지 않아 깨진 쿠키가 계속 남는 것 → 401 응답에 `Max-Age=0` 쿠키가 실려야 한다. Task 3에서 테스트한다.
- 되돌릴 게 없는 경우와 계획이 없는 경우와 revision 충돌이 같은 409로 뭉쳐 프론트가 구분하지 못하는 것 → `NOTHING_TO_UNDO`·`NO_PLAN`·`PLAN_CHANGED`. Task 7에서 테스트한다.
- 저장된 `goal_type_code`가 enum에서 사라져 옛 행을 읽을 때 터지는 것 → Task 1에서 데모 DB 초기화를 함께 적는다.

---

### Task 0: `origin/develop`에서 브랜치를 따고 A-2를 머지한다

**Files:** 없음(저장소 작업)

develop의 백엔드에는 `calc` 타입들과 `refdata` 모듈, `config/copy/CopyBook`, 골든 픽스처까지 70개가 넘는 파일이 이미 있다.
그래서 `backend/src`를 복사해 오면 안 된다. git 3-way 머지가 백엔드를 충돌 없이 합친다(`application.yml`·`schema.sql`·`SecurityConfig`·`build.gradle` 모두 자동 머지됨을 `git merge-tree`로 확인했다).

- [ ] **Step 1: 받아 둔 계약 문서를 치운다**

지금 작업 트리에 스테이징된 `docs/api`·`copy`·`docs/calc-spec.md`·`docs/rag-sources.md`는 develop 것과 같은 파일이다. 지우고 브랜치를 바꾸면 develop 버전이 제자리에 온다.

```bash
git rm -r --cached -q docs/api docs/calc-spec.md docs/rag-sources.md copy
rm -rf docs/api docs/calc-spec.md docs/rag-sources.md copy
```

- [ ] **Step 2: 브랜치를 딴다**

```bash
git fetch origin && git switch -c feat/contract-alignment origin/develop
```

`feat/a2-plan-auth`는 지우지 않는다. `docs/superpowers/`의 설계·계획 문서는 미추적이라 그대로 따라온다.

- [ ] **Step 3: A-2 브랜치를 머지한다**

```bash
git merge feat/a2-plan-auth
```

Expected: 충돌 7개 — `frontend/.gitignore`, `frontend/README.md`, `frontend/package.json`, `frontend/package-lock.json`, `frontend/tsconfig.json`, `README.md`, `ai-server/tests/test_units.py`.

- [ ] **Step 4: 충돌을 develop 쪽으로 해소한다**

프론트는 설계대로 develop이 기준이고, `ai-server`는 우리 영역이 아니며, 루트 `README.md`의 우리 표는 Next.js 기준이라 곧 틀린다. 일곱 개 모두 develop 것을 취한다.

```bash
git checkout --ours frontend/.gitignore frontend/README.md frontend/package.json \
  frontend/package-lock.json frontend/tsconfig.json README.md ai-server/tests/test_units.py
git add frontend/.gitignore frontend/README.md frontend/package.json \
  frontend/package-lock.json frontend/tsconfig.json README.md ai-server/tests/test_units.py
```

머지 중에는 `--ours`가 현재 브랜치(= develop 기준)다.

- [ ] **Step 5: Next 전용 프론트 파일을 지운다**

머지는 경로가 겹치지 않는 파일을 그대로 가져온다. Next 쪽 진입점이 Vite 라우트 폴더에 섞여 있으면 빌드가 깨진다.

```bash
git rm -r -q --ignore-unmatch frontend/next.config.ts frontend/next-env.d.ts \
  frontend/vitest.config.mts frontend/vitest.setup.ts frontend/eslint.config.mjs \
  frontend/src/app frontend/src/components frontend/src/features frontend/src/copy frontend/src/lib
```

프론트 이식은 별도 계획이 맡는다. 이 PR의 프론트는 develop 상태 그대로다.

- [ ] **Step 6: 머지를 커밋한다**

```bash
git commit --no-edit
```

- [ ] **Step 7: `.gitattributes`에 락파일 규칙을 넣는다**

```bash
echo 'frontend/package-lock.json -diff merge=binary' >> .gitattributes
git add .gitattributes && git commit -m "chore: package-lock 충돌을 파일 단위로 만든다"
```

- [ ] **Step 8: 기준선을 확인한다**

Run: `cd backend && ./gradlew checkstyleMain checkstyleTest test`
Expected: PASS. 실패하면 Task 1로 넘어가기 전에 원인을 ledger에 적는다. 데모 DB에 옛 `goal_type_code` 행이 있으면 Task 1 뒤 초기화가 필요하다.

### Task 1: 도메인을 계약 크기로 줄인다 (CF-08·분기·목표 종류)

**Files:**
- Modify: `backend/src/main/java/com/moeun/backend/plan/GoalType.java`
- Delete: `backend/src/main/java/com/moeun/backend/plan/RatioChoice.java`
- Modify: `backend/src/main/java/com/moeun/backend/plan/InputField.java`
- Modify: `backend/src/main/java/com/moeun/backend/plan/application/PlanInput.java`
- Modify: `backend/src/main/java/com/moeun/backend/plan/domain/PlanVersion.java`
- Modify: `backend/src/main/resources/db/schema.sql`
- Modify: `backend/src/main/resources/config/rules.yml`
- Modify: `db/tables.md`, `db/erd.md`
- Test: `backend/src/test/java/com/moeun/backend/plan/application/PlanInputValidatorTest.java`, `plan/application/PlanInputBuilder.java`, `plan/PlanServiceTest.java`

**Interfaces:**
- Produces: `GoalType` 상수 7개 `HOUSE, CAR, WEDDING, EDUCATION, TRAVEL, EMERGENCY_FUND, CUSTOM`(선언 순서 = 화면 순서). `PlanInput(GoalType goalType, String goalCustomName, Long goalAmount, Integer targetPeriodMonths, Long heldAssets, Long monthlySavingAmount, BigDecimal savingsRateInputPct)` — 7개 필드.

- [ ] **Step 1: `PlanInputValidatorTest`를 계약 모양으로 고쳐 실패시킨다**

`existing`·`ratioChoice`를 쓰는 테스트를 지우고 다음을 남긴다. `PlanInputBuilder`도 7필드로 줄인다.
이 시점의 `FieldError`는 아직 `(field, code)` 두 필드다. Task 2가 여섯 필드로 넓히면서 이 테스트도 함께 고친다.

```java
@Test
void periodAndMonthlySavingAreBothRequired() {
    assertThat(validator.validate(valid().targetPeriodMonths(null).build()))
            .containsExactly(new FieldError("targetPeriodMonths", "REQUIRED"));
    assertThat(validator.validate(valid().monthlySavingAmount(null).build()))
            .containsExactly(new FieldError("monthlySavingAmount", "REQUIRED"));
}
```

- [ ] **Step 2: 컴파일이 깨지는 것을 확인한다**

Run: `./gradlew :compileTestJava`
Expected: FAIL — `RatioChoice`, `existingSavingsMonthly` 등을 찾을 수 없다

- [ ] **Step 3: 도메인을 줄인다**

`GoalType`을 위 7개로 바꾼다. `RatioChoice.java`를 지운다. `InputField`에서 `EXISTING_SAVINGS_MONTHLY`·`EXISTING_ETF_MONTHLY`를 지우고 `MONTHLY_SAVING` → `MONTHLY_SAVING_AMOUNT`, `SAVINGS_RATE_PCT` → `SAVINGS_RATE_INPUT_PCT`로 바꾼다(`apiName()`이 계약의 요청 키와 같아야 한다). `PlanInput`을 Interfaces의 7필드로 줄이고 `existingOn()`·`existingSum()`을 지운다. `PlanVersion`에서 기존 저축 두 필드를 지우고 `existingMonthlyContribution`(nullable, 항상 `null`) 하나로 되돌린다.

- [ ] **Step 4: `schema.sql`과 `db/` 문서를 되돌린다**

`plan_version`의 `existing_savings_monthly`·`existing_etf_monthly` 두 줄을 `existing_monthly_contribution BIGINT, -- 항상 NULL(계약 C12a-5-1)` 한 줄로 바꾸고 `ck_pv_amounts` CHECK도 맞춘다. `goal_custom_name`은 100자를 유지한다(D-074). `db/tables.md`·`db/erd.md`의 해당 줄을 같이 고친다.

- [ ] **Step 5: `rules.yml`에서 쓰지 않게 된 설정을 지운다**

`base-savings-ratio-pct`, `goal-defaults`, `EXISTING_SAVINGS_MONTHLY`, `EXISTING_ETF_MONTHLY` 항목을 지운다. 범위 수치는 Task 2에서 바꾼다.

- [ ] **Step 6: 테스트가 지나는 것을 확인한다**

Run: `./gradlew test --tests '*PlanInputValidatorTest' --tests '*PlanServiceTest'`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add backend/src db/
git commit -m "refactor(backend): 입력 도메인을 계약 C12a-5-1 크기로 줄인다"
```

데모 DB에 옛 `goal_type_code` 행이 있으면 enum 읽기가 깨진다. PR 본문에 초기화가 필요하다고 적는다.

---

### Task 2: 검증 reason 4종과 계약 범위 수치

**Files:**
- Modify: `backend/src/main/java/com/moeun/backend/plan/FieldError.java`
- Modify: `backend/src/main/java/com/moeun/backend/plan/application/PlanInputValidator.java`
- Modify: `backend/src/main/java/com/moeun/backend/plan/ConditionValidator.java`
- Modify: `backend/src/main/java/com/moeun/backend/plan/application/PlanRules.java`
- Modify: `backend/src/main/resources/config/rules.yml`
- Test: `plan/application/PlanInputValidatorTest.java`, `plan/ConditionValidatorTest.java`, `plan/PlanRulesFixture.java`

**Interfaces:**
- Consumes: Task 1의 `PlanInput`, `InputField`
- Produces: `record FieldError(String field, String reason, Long min, Long max, String unit, Integer scale)` — 범위 위반이 아니면 뒤 네 개는 `null`. 정적 팩터리 `FieldError.required(String field)`, `FieldError.outOfRange(InputField, FieldRule)`, `FieldError.notInteger(String field)`, `FieldError.tooManyDecimals(String field, int scale)`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
@Test
void outOfRangeCarriesMinMaxUnit() {
    assertThat(validator.validate(valid().monthlySavingAmount(9_000L).build()))
            .containsExactly(new FieldError("monthlySavingAmount", "OUT_OF_RANGE", 10_000L, 10_000_000L, "KRW", null));
}

@Test
void decimalAmountIsNotInteger() {
    assertThat(validator.validate(valid().goalAmountRaw(new BigDecimal("300000.5")).build()))
            .containsExactly(new FieldError("goalAmount", "NOT_INTEGER", null, null, null, null));
}

@Test
void rateWithThreeDecimalsIsTooManyDecimals() {
    assertThat(validator.validate(valid().savingsRateInputPct(new BigDecimal("3.123")).build()))
            .containsExactly(new FieldError("savingsRateInputPct", "TOO_MANY_DECIMALS", null, null, null, 2));
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*PlanInputValidatorTest'`
Expected: FAIL

- [ ] **Step 3: `rules.yml` 수치를 계약 C12a-5-5로 바꾼다**

`MONTHLY_SAVING_AMOUNT` 10000~10000000 KRW scale 0 / `SAVINGS_RATE_INPUT_PCT` 0~20 PCT scale 2 / `TARGET_PERIOD_MONTHS` 1~360 MONTH / `GOAL_AMOUNT` 10000~10000000000 KRW / `HELD_ASSETS` 0~10000000000 KRW / `GOAL_CUSTOM_NAME` 1~100 CHAR. `rules.yml` 머리말의 삭제된 `GET /api/input-rules` 언급과 `period-presets`도 지운다(Task 1에서 넘어온 건이다). **모든 금액 필드에서 `step`을 지운다**(계약: "`rules.yml`에 금액 step은 두지 않는다"). `required`를 기간·월 저축액 모두 `true`로 둔다(D-045). `PlanRules`에서 `step` 처리를 걷어낸다.

- [ ] **Step 4: `FieldError`와 검증기를 고친다**

`FieldError`를 Interfaces의 모양으로 바꾸고, `PlanInputValidator`·`ConditionValidator`가 내던 `NOT_UNIT`·`INVALID_SCALE`·`TOO_LONG`·`BLANK`·`ONE_OF_PERIOD_OR_SAVING`·`EXISTING_EXCEEDS_SAVING`·`RATIO_CHOICE_REQUIRED`를 없앤다. `goalCustomName` 길이 초과는 `OUT_OF_RANGE` + `min:1, max:100, unit:"CHAR"`다. `TOO_MANY_CHANGES`는 `NOT_CHANGEABLE`과 함께 변경 경로에만 남긴다.

- [ ] **Step 5: 테스트가 지나는 것을 확인한다**

Run: `./gradlew test --tests '*PlanInputValidatorTest' --tests '*ConditionValidatorTest'`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add backend/src
git commit -m "feat(backend): 검증 오류를 계약 reason 4종과 범위 수치로 맞춘다"
```

---

### Task 3: 오류 본문에 `code`를 싣고 401에 만료 쿠키를 동봉한다

**Files:**
- Create: `backend/src/main/java/com/moeun/backend/config/ProblemCode.java`
- Create: `backend/src/main/java/com/moeun/backend/config/GlobalExceptionHandler.java`
- Modify: `backend/src/main/java/com/moeun/backend/plan/web/PlanExceptionHandler.java`
- Modify: `backend/src/main/java/com/moeun/backend/config/SecurityConfig.java`
- Test: `backend/src/test/java/com/moeun/backend/auth/TokenCookieAuthenticationTest.java`, `plan/PlanApiTest.java`

**Interfaces:**
- Produces: 모든 `ProblemDetail`에 `problem.setProperty("code", …)`와 `problem.setProperty("errors", …)`를 싣는다(`errors`는 비어도 `[]`). 코드 문자열은 `UNAUTHENTICATED, INVALID_CREDENTIALS, VALIDATION_FAILED, MALFORMED_REQUEST, NO_PLAN, NOTHING_TO_UNDO, PLAN_CHANGED, INTERNAL_ERROR`.
- **모듈 경계**: `plan`과 `auth`는 `allowedDependencies = {}`라 `config`의 타입을 import할 수 없다. 그래서 `ProblemCode` enum은 `config`에 두되 `GlobalExceptionHandler`가 자기 두 코드(`MALFORMED_REQUEST`·`INTERNAL_ERROR`)에만 쓰고, `plan`·`auth`의 예외는 각자 `String code`를 들고 자기 모듈의 핸들러가 그 문자열을 싣는다. `ModularityTests.verifyModules`가 이것을 검사한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
@Test
void unauthenticatedBodyCarriesCodeAndExpiringCookie() throws Exception {
    mockMvc.perform(get("/api/plan"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.errors").isArray())
            .andExpect(cookie().maxAge(JwtTokenService.COOKIE_NAME, 0));
}

@Test
void validationFailureIs422WithCode() throws Exception {
    // 본문이 비면 422 VALIDATION_FAILED
}

@Test
void malformedJsonIs400WithCode() throws Exception {
    // 깨진 JSON이면 400 MALFORMED_REQUEST
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*TokenCookieAuthenticationTest' --tests '*PlanApiTest'`
Expected: FAIL

- [ ] **Step 3: `ProblemCode`와 공통 처리기를 만든다**

`GlobalExceptionHandler`(`@RestControllerAdvice`)가 `HttpMessageNotReadableException` → 400 `MALFORMED_REQUEST`, 그 밖의 예외 → 500 `INTERNAL_ERROR`를 낸다. `PlanExceptionHandler`는 `PlanValidationException` → **422** `VALIDATION_FAILED` + `errors`, `PlanConflictException` → 409로 바꾼다(코드는 Task 7에서 셋으로 나눈다).

- [ ] **Step 4: `source` 오류의 reason을 정한다 (Task 2에서 넘어온 건)**

`PlanController`가 `ApplyRequest.source`가 비거나 알 수 없을 때 `FieldError.of(field, "OUT_OF_RANGE")`를 쓰는데, `min`·`max`·`unit` 없이 `OUT_OF_RANGE`를 내는 것은 계약 규칙을 어긴다. P-6의 요청 모양은 계약이 #68로 미뤄 뒀으므로, 범위가 아니라 "그 값을 쓸 수 없다"는 뜻의 reason을 쓴다: 없으면 `REQUIRED`, 알 수 없는 값이면 `NOT_CHANGEABLE`. `FieldError.of`가 아무 문자열이나 받는 것도 이 참에 좁힌다.

- [ ] **Step 5: 401 엔트리 포인트를 고친다**

`SecurityConfig`의 인증 실패 처리에서 C3-1 본문(`code: UNAUTHENTICATED`, `errors: []`)과 함께 `JwtTokenService.expiredCookie()`를 응답에 싣는다.

- [ ] **Step 6: 테스트가 지나는 것을 확인한다**

Run: `./gradlew test --tests '*TokenCookieAuthenticationTest' --tests '*PlanApiTest'`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add backend/src
git commit -m "feat(backend): 오류 본문을 계약 C3-1 형식으로 통일한다"
```

---

### Task 4: 쿠키 이름·Secure·토큰 리졸버

**Files:**
- Modify: `backend/src/main/java/com/moeun/backend/auth/application/JwtTokenService.java`
- Modify: `backend/src/main/java/com/moeun/backend/auth/application/CookieBearerTokenResolver.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `auth/application/JwtTokenServiceTest.java`, `auth/TokenCookieAuthenticationTest.java`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
@Test
void tokenCookieIsNamedMoeunAtSecureHttpOnlyLaxRootPath() {
    ResponseCookie cookie = service.tokenCookie("jwt");
    assertThat(cookie.getName()).isEqualTo("moeun_at");
    assertThat(cookie.isSecure()).isTrue();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getSameSite()).isEqualTo("Lax");
    assertThat(cookie.getPath()).isEqualTo("/");
    assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofSeconds(1800));
}

@Test
void authorizationHeaderIsIgnored() throws Exception {
    mockMvc.perform(get("/api/plan").header("Authorization", "Bearer " + validToken))
            .andExpect(status().isUnauthorized());
}

@Test
void loginSucceedsWithBrokenCookieLeftOver() throws Exception {
    mockMvc.perform(post("/api/auth/login").cookie(new Cookie("moeun_at", "not-a-jwt"))
                    .contentType(APPLICATION_JSON).content(validCredentials))
            .andExpect(status().isOk());
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*JwtTokenServiceTest' --tests '*TokenCookieAuthenticationTest'`
Expected: FAIL

- [ ] **Step 3: 쿠키와 리졸버를 고친다**

`COOKIE_NAME`을 `"moeun_at"`으로 바꾼다. `CookieBearerTokenResolver`에서 `DefaultBearerTokenResolver` 폴백을 없앤다(쿠키만 읽는다). 로그인 경로에서 `null`을 돌려주는 동작은 그대로 둔다.

- [ ] **Step 4: `application.yml`의 기본값을 뒤집는다**

`cookie-secure: ${JWT_COOKIE_SECURE:true}`로 바꾸고, `local` 프로필에서만 `false`가 되도록 프로필 블록을 둔다.

- [ ] **Step 5: 테스트가 지나는 것을 확인한다**

Run: `./gradlew test --tests '*auth*'`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add backend/src
git commit -m "feat(backend): 인증 쿠키를 moeun_at·Secure 기본값으로 맞춘다"
```

---

### Task 5: 로그인·로그아웃 응답과 회원 이벤트, `me` 제거

**Files:**
- Modify: `backend/src/main/java/com/moeun/backend/auth/web/AuthController.java`
- Delete: `backend/src/main/java/com/moeun/backend/auth/web/MeResponse.java`
- Create: `backend/src/main/java/com/moeun/backend/auth/MemberLoggedIn.java`, `MemberLoggedOut.java`
- Modify: `backend/src/main/java/com/moeun/backend/auth/application/AuthService.java`
- Test: `auth/AuthApiTest.java`, `docs/AuthDocsTest.java`

**Interfaces:**
- Produces: `record MemberLoggedIn(long memberId)`, `record MemberLoggedOut(long memberId)` — `auth` 루트. 쿠키를 내리기 **전에** 발행한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
@Test
void loginReturnsEmptyBodyAndSetsCookie() throws Exception {
    mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(validCredentials))
            .andExpect(status().isOk())
            .andExpect(content().json("{}"))
            .andExpect(cookie().exists("moeun_at"));
}

@Test
void wrongPasswordIs401InvalidCredentials() throws Exception {
    // code = INVALID_CREDENTIALS, errors = []
}

@Test
void meEndpointIsGone() throws Exception {
    mockMvc.perform(get("/api/auth/me")).andExpect(status().isNotFound());
}

@Test
void loginPublishesMemberLoggedIn() { /* ApplicationEvents로 확인 */ }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*AuthApiTest'`
Expected: FAIL

- [ ] **Step 3: 컨트롤러와 서비스를 고친다**

로그인 응답을 `{}`로, 실패를 `401 INVALID_CREDENTIALS`로 바꾼다. `GET /api/auth/me`와 `MeResponse`를 지운다. `AuthService`가 쿠키 발급 전에 `MemberLoggedIn`/`MemberLoggedOut`을 발행한다.

- [ ] **Step 4: 토큰 연장 제외를 확인한다**

계약 C12a-2-2: 로그인·로그아웃은 핸들러가 쿠키를 직접 내리므로 `@SkipRenewal`이다. 두 핸들러에 붙어 있는지 보고, 없으면 붙인다. 응답에 `Set-Cookie`가 둘 실리지 않는 것을 테스트로 고정한다.

```java
@Test
void loginResponseHasExactlyOneSetCookie() throws Exception {
    // Set-Cookie 헤더가 1개
}
```

- [ ] **Step 5: REST Docs를 맞춘다**

`AuthDocsTest`에서 `me` 문서를 지우고 로그인 응답 필드를 비운다.

- [ ] **Step 6: 테스트가 지나는 것을 확인한다**

Run: `./gradlew test --tests '*AuthApiTest' --tests '*AuthDocsTest'`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add backend/src
git commit -m "feat(backend): 로그인·로그아웃을 계약 P-1·P-2에 맞추고 me를 없앤다"
```

---

### Task 6: `plan` 공개 API 승격과 P-3·P-4·input-rules 컨트롤러 제거

**Files:**
- Move: `plan/application/PlanInput.java`, `PlanView.java` → `plan/`
- Create: `backend/src/main/java/com/moeun/backend/plan/PlanSaveService.java`, `PlanSaveResult.java`, `PlanApplyService.java`
- Modify: `plan/web/PlanController.java`
- Delete: `plan/web/InputRulesController.java`, `plan/web/InputRulesView.java`
- Modify: `plan/package-info.java`
- Test: `plan/PlanApiTest.java`, `plan/PlanServiceTest.java`, `docs/PlanDocsTest.java`, `ModularityTests`

**Interfaces:**
- Produces (공개, `com.moeun.backend.plan`):
  - `PlanSaveService.save(long memberId, PlanInput input, Long expectedRevision) → PlanSaveResult`. `PlanInput`은 금액을 `Long`/`Integer`로 들지 말고 계약대로 `goalType`·`goalCustomName`과 `Map<String, JsonNode> fields`를 든다 — Jackson이 `300000.5`를 잘라 버려 `NOT_INTEGER`가 영영 발화하지 못하기 때문이다(Task 2에서 넘어온 건이다)
  - `record PlanSaveResult(long planVersionId, long revision)`
  - `PlanSaveService.findCurrent(long memberId) → Optional<PlanView>` — `PlanView`에 `currentVersionId`를 담고, 필드 이름을 계약 C12a-6-1의 `plan` 객체에 맞춘다: `monthlySaving` → `monthlySavingAmount`, `savingsRatePct` → `savingsRateInputPct`. `ConditionOverride`의 같은 두 키도 함께 맞춘다(Task 1에서 넘어온 건이다)
  - `PlanApplyService.apply(long memberId, ChangePath path, ConditionOverride changes, long expectedRevision) → PlanSaveResult`
  - 인자·반환에 `calc`·`chat` 타입을 두지 않는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
@Test
void saveChecksExpectedRevision() {
    PlanSaveResult first = saveService.save(memberId, input, null);
    assertThatThrownBy(() -> saveService.save(memberId, input, first.revision() - 1))
            .isInstanceOf(PlanConflictException.class);
}

@Test
void planEndpointsForSaveAndReadAreGone() throws Exception {
    mockMvc.perform(get("/api/plan").cookie(valid)).andExpect(status().isNotFound());
    mockMvc.perform(put("/api/plan").cookie(valid)).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/input-rules")).andExpect(status().isNotFound());
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*PlanServiceTest' --tests '*PlanApiTest'`
Expected: FAIL

- [ ] **Step 3: 타입을 루트로 올리고 공개 서비스를 만든다**

`PlanInput`·`PlanView`를 `plan` 루트로 옮긴다. `PlanService`의 저장·조회·적용을 Interfaces의 시그니처로 감싼 `PlanSaveService`·`PlanApplyService`를 루트에 둔다. 저장에 `expectedRevision` 검사를 넣는다 — 지금 `PlanRepository.overwriteCurrent`가 무시하고 있으니, 계획이 있는데 `null`이거나 `revision`이 다르면 `PlanConflictException`을 던진다. 첫 저장은 네이티브 `INSERT INTO plan … ON CONFLICT (member_id) DO NOTHING`으로 바꾸고 0행이면 충돌로 본다.

- [ ] **Step 4: 컨트롤러를 걷어낸다**

`PlanController`에서 `@GetMapping`·`@PostMapping`(저장)을 지우고 `/apply`·`/undo`만 남긴다. `InputRulesController`·`InputRulesView`를 지운다. 남기는 `POST /api/plan/apply`에는 "계약 P-6은 경로만 예약돼 있고 요청 모양·오류는 #68이 확정한다"는 주석을 단다.

- [ ] **Step 5: `package-info.java`와 문서를 맞춘다**

`plan` 모듈 설명의 주요 클래스 목록에 공개 서비스를 넣는다. `db/moved-to-code.md`의 클래스 이름 표를 같이 고친다.

- [ ] **Step 6: 테스트와 모듈 경계를 확인한다**

Run: `./gradlew test --tests '*PlanServiceTest' --tests '*PlanApiTest' --tests '*PlanDocsTest' --tests 'ModularityTests'`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add backend/src db/
git commit -m "feat(backend): plan 저장·조회를 공개 서비스로 올리고 calc.web 몫 컨트롤러를 뺀다"
```

---

### Task 7: P-5 되돌리기 — D-068 방식과 409 세 코드

**Files:**
- Modify: `backend/src/main/java/com/moeun/backend/plan/application/PlanService.java`
- Modify: `backend/src/main/java/com/moeun/backend/plan/domain/PlanRepository.java`
- Modify: `backend/src/main/java/com/moeun/backend/plan/application/PlanConflictException.java`
- Modify: `plan/web/PlanController.java`, `plan/web/PlanExceptionHandler.java`
- Test: `plan/PlanServiceTest.java`, `plan/PlanApiTest.java`, `docs/PlanDocsTest.java`

**Interfaces:**
- Consumes: Task 3의 `ProblemCode`, Task 6의 `PlanSaveResult`
- Produces: `POST /api/plan/undo` → `200 {"revision": <long>}`. `PlanConflictException`이 `ProblemCode`를 들고 다닌다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
@Test
void undoCopiesPreviousVersionAsNewVersionAndClearsPrevious() {
    // 되돌린 뒤 plan.current 가 새 plan_version 이고, previous_version_id 는 null,
    // 새 버전의 조건이 직전 버전과 같다
}

@Test
void undoWithoutPlanIsNoPlan() { /* code = NO_PLAN */ }

@Test
void secondUndoIsNothingToUndo() { /* code = NOTHING_TO_UNDO */ }

@Test
void undoWithStaleRevisionIsPlanChanged() { /* code = PLAN_CHANGED */ }

@Test
void undoResponseIsRevisionOnly() throws Exception {
    mockMvc.perform(post("/api/plan/undo").cookie(valid).content("{\"expectedRevision\":2}"))
            .andExpect(jsonPath("$.revision").exists())
            .andExpect(jsonPath("$.plan").doesNotExist());
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests '*PlanServiceTest' --tests '*PlanApiTest'`
Expected: FAIL

- [ ] **Step 3: 되돌리기를 복사 INSERT로 바꾼다**

`PlanRepository.restorePrevious`(포인터 교체)를 버리고, 직전 버전의 조건을 새 `plan_version`으로 복사 INSERT한 뒤 `current`를 그 버전으로, `previous_version_id`를 `null`로 두는 revision 검사 UPDATE를 쓴다. 0행이면 `PLAN_CHANGED`다.

- [ ] **Step 4: 세 경우를 다른 코드로 나눈다**

`PlanConflictException`에 `ProblemCode`를 들려 계획 없음 `NO_PLAN`, 되돌릴 것 없음 `NOTHING_TO_UNDO`, revision 불일치 `PLAN_CHANGED`로 던진다. `PlanExceptionHandler`가 그 코드를 응답에 싣는다.

- [ ] **Step 5: 응답을 `{revision}`으로 바꾼다**

`PlanController.undo`가 `PlanView` 대신 `{"revision": …}`을 돌려준다. `PlanDocsTest`를 맞춘다.

- [ ] **Step 6: 전체 테스트와 스타일을 확인한다**

Run: `./gradlew checkstyleMain checkstyleTest test`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add backend/src
git commit -m "feat(backend): 되돌리기를 D-068 복사 방식과 409 세 코드로 바꾼다"
```
