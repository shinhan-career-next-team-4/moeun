# 프론트 ↔ Spring API 계약 (로그인·입력·결과·대화·적용)

> 최종본 (2026-11-16, moeun #12). 요구사항 FR-AI-01·FR-AI-07·FR-PLAN-03·FR-PLAN-04·FR-AUTH-01·FR-AUTH-03·FR-INP-10·FR-INP-11·FR-DATA-03(절 4), 결정 D-046·D-054·D-062·D-063·D-064·D-066·D-068·D-069·D-070·D-074·D-075·D-076.
> 기획 repo project-workspace `docs/design/contracts/`의 최종본 두 개를 그대로 옮겼다. 절 2는 `frontend-api-plan.md`(#12-a), 절 3은 `frontend-api-chat.md`(#12-b)다. 협상 기록·후속 작업 표·코드 차이 표(본문 괄호의 `X-n`·`Y-n`·`H-n`·`J-n`)는 원문에 있다.
> 오류 본문 형식 C3-1과 `/internal/calc`는 [`internal-calc.md`](internal-calc.md), planContext(C1a-x)는 기획 repo `plan-context.md`다.
> 이 문서가 구현 기준이고 `frontend/src/mocks/`의 목 JSON 69개(대화 38 + 로그인·계획 30 + 기준 데이터 1)와 같은 값이다. 각 절 머리말의 "최종본 보충"이 본문보다 우선한다.

## 1. 한눈에 보기

### 1-1. 엔드포인트

| ID | 메서드·경로 | 성공 | 컨트롤러 위치(팀) | 토큰 연장 | 상세 |
|---|---|---|---|---|---|
| P-1 | `POST /api/auth/login` | `200 {}` + 쿠키 | `auth.web`(A) | 아니오(`@SkipRenewal`) | C12a-3 |
| P-2 | `POST /api/auth/logout` | `200 {}` + 만료 쿠키 | `auth.web`(A) | 아니오(`@SkipRenewal`) | C12a-4 |
| P-3 | `PUT /api/plan` | `200 PlanView` | `calc.web`(A) | 예 | C12a-5 |
| P-4 | `GET /api/plan` | `200 PlanView` | `calc.web`(A) | 예 | C12a-6 |
| P-5 | `POST /api/plan/undo` | `200 {revision}` | `plan.web`(A) | 예 | C12a-8 |
| P-6 | `POST /api/plan/apply` | `200 {revision}` | `plan.web`(A) | 예 | C12a-9 (#68 확정) |
| A-1 | `POST /api/alternatives` | `200 {hasAlternatives, alternatives}` | `chat.web.alternative`(B) | 예 | 5절 A-1 (#66, #258) |
| F-1 | `POST /api/chat/messages` | `202 {userMessageId, assistantMessageId}` | `chat`(B) | 예 | C12b-4 |
| F-2 | `GET /api/chat/messages?afterId={id}` | `200 {messages, hasPending, answerPending}` | `chat`(B) | 아니오(`@SkipRenewal`) | C12b-5 |
| F-3 | `POST /api/chat/explanations` | `202 {}` | `chat`(B) | 예 | C12b-3a |
| F-4 | `GET /api/chat/messages/{messageId}/comparison` | `200` (`messageId`·`applicable`·`changedKeys` 포함) | `chat`(B) | 예 | C12b-6 |
| F-5 | `POST /api/chat/messages/{messageId}/apply` | `200 {revision}` | `chat`(B) → `plan`(A) 공개 서비스 | 예 | C12b-7 |
| R-1 | `GET /api/etf-products` | `200 {products}` | `refdata.web`(A) | 예 | 절 4 (#220) |

- 대화 기록 삭제용 프론트 엔드포인트는 없다. `auth.MemberLoggedIn`/`MemberLoggedOut`을 chat이 동기 `@EventListener`로 받아 지운다(C12b-8).

### 1-2. 함께 정한 것

| 항목 | 결정 | 근거 |
|---|---|---|
| Spring 모듈 담당 | 대화(F-1~F-5)는 `chat`(B팀). 로그인·로그아웃 `auth.web`, 입력 저장·조회 `calc.web`, 되돌리기·대안 적용 `plan.web`(A팀) | C12b-1, C12a-1 |
| 적용 API 위치 | chatMessageId 적용은 chat 컨트롤러(F-5). plan은 조건값을 받는 서비스만 갖고 chat을 모른다 | C12b-10-1 (안 A) |
| 되돌리기 방식 | 새 `plan_version` 복사 INSERT + 포인터 이동, 1단계(포인터 교체 아님) | D-068, C12b-7-4, C12a-8 |
| 폴링 간격·시간 초과 | 폴링 1초, Spring → ai-server 연결 2초·응답 30초, 오래된 PENDING 60초. TBD-33 실측 전 임시값 | C12b-11-1 |
| 오류 본문 | `ProblemDetail` + `code` + `errors[]`(C3-1). 401은 `code=UNAUTHENTICATED`, `errors=[]`. 프론트 API에는 `minDisplay`·`maxDisplay`가 없다(C3-5) | C12a-10, C12b-9 |

### 1-3. 모듈 의존과 호출 방향

| 모듈 | 의존 | 컨트롤러 | 바뀌는 것 |
|---|---|---|---|
| `auth` | `{}` | P-1·P-2 `auth.web` | 없음. 이벤트를 발행만 한다 |
| `plan` | `{}` | P-5·P-6 `plan.web` | 없음. 대화를 모른다 |
| `calc` | `{plan, refdata}` | P-3·P-4 `calc.web` | 없음 |
| `refdata` | `{}` | R-1 `refdata.web` | 웹 계층만 새로 생김(#220). 의존 변경 없음 |
| `chat` | `{calc, rag, plan, auth}` | F-1~F-5 | `{calc, rag}`에서 `plan`·`auth` 추가(#41) |

```
chat ──▶ calc ──▶ plan
  │        └────▶ refdata
  ├──────▶ plan      (F-5 적용: PlanApplyService)
  ├──────▶ rag
  └──────▶ auth      (MemberLoggedIn/MemberLoggedOut 수신, @SkipRenewal)
```

- **plan → chat 의존은 생기지 않는다.** 적용 요청의 방향은 "대화를 아는 쪽(chat)이 계획을 아는 쪽(plan)의 공개 서비스를 부른다" 하나뿐이다. plan 서비스의 인자·반환에 chat·calc 타입이 없고 기준 버전은 `Long`, 변경은 `Map<String, JsonNode>`다.
- 의존이 바뀌는 곳은 `chat`뿐이라 `ModularityTests`를 깨지 않는다(`auth`·`plan`·`calc`는 변경 없음).
- 인증·배포 전제(쿠키 `SameSite=Lax` 이상, 프론트와 Spring은 같은 사이트·Vite 프록시 `/api`, 토큰 연장 제외는 F-2뿐이고 로그인·로그아웃은 `@SkipRenewal`)는 C12b-2와 C12a-2에 있다.

### 1-4. #12-a 상태

로그인·로그아웃·입력 저장·현재 계획·되돌리기(`NOTHING_TO_UNDO` 포함)와 쿠키 이름(`moeun_at`)은 **계약 완료**다(최종본 `frontend-api-plan.md`, 결정 D-074~076). P-6 `POST /api/plan/apply`는 #68에서 요청 모양·오류·목을 확정했다(C12a-9). 쿠키 `Secure`·JWT 서명 키·허용 출처는 설정값이다(D-076, 임시: 원격 배포로 바뀔 수 있음). 쿠키 `Secure`는 기본 true이고 `local` 프로필만 false다.

---

## 2. 계획·인증 절 (#12-a, 최종본 `frontend-api-plan.md`)

> **이 절의 머리말 (원문)** 최종본(2026-11-16). 쟁점 결정 D-074·D-075·D-076을 반영했다. 본문의 "쟁점 X-n이 다르면 …" 문장은 기록용이고 결정이 우선한다.
>
> | 계획 절 쟁점 | 내용 | 결정 |
> |---|---|---|
> | X-1 (Q-061) | 직접 입력 목표명 길이 | D-074: 100자 |
> | X-2 (Q-062) | 목표 종류(`GoalType`) 목록 | D-075: 7개 |
> | X-3 (Q-063) | 데모를 로컬로 할지 원격으로 할지 | D-076: 로컬 데모. 임시이며 원격 배포로 바뀔 수 있어 쿠키 `Secure`·서명 키·허용 출처는 설정값 |
>
> **번호는 절마다 따로 센다.** 이 표의 쟁점 X-1~X-3은 3절(대화 절)의 쟁점 X-1(되돌리기 방식)·X-2(버튼 비활성 범위)와 다른 것이다. 같은 번호가 두 뜻이므로 "계획 절 쟁점 X-1"처럼 절을 붙여 읽는다. 본문 괄호의 코드 차이 `Y-n`, 후속 작업 `J-n`은 기획 repo 원문의 표를 가리킨다.
>
> **표기 범례 (원문)**
> - **[v3]**: v2에서 바뀐 줄.
> - **⟵ 쟁점 X-n**: 원래 사람이 정하는 항목이었고 본문에는 기획 추천안을 임시로 적었다. 위 표대로 결정됐으므로 이 문서의 해당 줄은 결정을 반영한 값이다.
> - 항목 ID(C12a-x, P-x, R-x, Y-x, J-x)는 v1·v2와 같다.

> **최종본 보충 (2026-11-16, D-077~082 임시, 본문보다 우선)**
> - **D-077** P-3 응답 유실(네트워크·502·비JSON): 입력값 유지 + `common.networkError` + `loadPlan()`으로 `revision`만 맞춤(C12a-6-5 "부르는 때" 표에 추가). → #48, #13
> - **D-078** 로그인·로그아웃 성공 시 `BroadcastChannel("moeun-auth")`로 알림 → 받은 탭은 store 비우고 `loadPlan()`부터. → #13, #47, #58(시연 절차)
> - **D-079** 허용 출처 = CORS 허용 목록(로컬 기본 비움). JWT 서명 키 없으면 기동 실패, `local` 프로필만 고정 개발 키. → #16, #58
> - **D-080** 정수 금액 필드가 `long` 범위 밖(큰 정수) → `OUT_OF_RANGE`. 비정수 숫자는 본문대로 422 `NOT_INTEGER`, 문자열은 400 `MALFORMED_REQUEST`(Q-070 정정). 프론트는 `Number` 변환 없이 십진 문자열 결과를 JSON 숫자로. → #17, #48
> - **D-081** 목표명: 빈 값·공백뿐 `REQUIRED`, 100자 초과 `OUT_OF_RANGE`, trim 저장, UTF-16, `CUSTOM` 아니면 `null` 저장. → #17, #19
> - **D-082** 보유 자산 칸은 `0`으로 시작, 지우고 저장하면 `REQUIRED`. → #48

### C12a-0. 이 계약의 원칙

`frontend-api-chat.md` §0(원칙 1~8)을 그대로 따른다. 이 계약에서 특히 중요한 것:

1. **프론트는 숫자를 만들지 않는다.** 결과 화면의 모든 금액·기간·시점·비율 **문자열**은 Spring `display`를 그대로 쓴다. 왜: CP-04 형식이 Spring `Formatter` 한 곳에만 있어야 "화면 숫자 = AI 숫자 글자까지"(PR-04)가 성립한다. 프론트가 하는 일은 다음뿐이다.
   - (a) `display` 키 찾기, (b) 문구표(CP-05) 키 찾기, (c) 그래프 점 위치 그리기(raw 숫자, 문자열 없음)
   - (d) 입력칸 금액 만원 ↔ 원 변환, (e) 입력칸 'n년 n개월' ↔ 개월 변환, (f) 422 `min`·`max`·`unit`을 입력칸 단위의 안내 문구로 바꾸기(C12a-5-6)
   - (d)(e)(f)는 **프론트 util 파일 하나**에만 둔다(단위 테스트 포함, #48 소유, 공용 폴더 [v3]). 왜: 화면마다 따로 만들면 CP-04가 어긋난다.
2. **회원은 JWT subject로만 정한다.** 본문·쿼리에 `memberId`가 없다.
3. **`revision`은 내 계획 상태의 유일한 버전 표시다.** 모든 쓰기는 `expectedRevision`을 받고 새 `revision`을 돌려준다. 프론트 공유 상태 규칙은 C12a-6-7.
4. **오류 본문은 C3-1 형식 하나**(`ProblemDetail` + `code` + `errors[]`), 기계 판단은 `code`로만. 프론트 API에는 `minDisplay`·`maxDisplay`가 없다(C3-5).
5. **새 라이브러리·새 테이블·새 컬럼 없음.** (계획 절 쟁점 X-1이 B면 컬럼 **길이** 변경 1줄. 쟁점 X-3이 B면 배포 인프라가 생긴다. 결정됨: X-1은 D-074(100자), X-3은 D-076(로컬 데모, 임시)이라 배포 인프라는 만들지 않는다.)

---

### C12a-1. 범위와 엔드포인트 목록

| ID | 메서드·경로 | 하는 일 | 성공 | 컨트롤러 위치(팀) | 토큰 연장 | 우선순위 |
|---|---|---|---|---|---|---|
| P-1 | `POST /api/auth/login` | 로그인, 쿠키 발급, 이전 대화 삭제 | `200 {}` | `auth.web`(A) | 아니오(`@SkipRenewal`, 핸들러가 발급) | Must |
| P-2 | `POST /api/auth/logout` | 로그아웃, 쿠키 만료, 이전 대화 삭제 | `200 {}` | `auth.web`(A) | 아니오(`@SkipRenewal`) | Must |
| P-3 | `PUT /api/plan` | 입력 저장(새 목표 세우기·입력 수정): 검증 → 저장 → 계산 → 결과, **한 트랜잭션** | `200 PlanView` | `calc.web`(A) | 예 | Must |
| P-4 | `GET /api/plan` | 현재 계획·결과 조회 | `200 PlanView` | `calc.web`(A) | 예 | Must |
| P-5 | `POST /api/plan/undo` | 되돌리기(직전 1단계) | `200 {revision}` | `plan.web`(A) | 예 | Must |
| P-6 | `POST /api/plan/apply` | 대안·비교 결과 적용 | `200 {revision}` | `plan.web`(A) | 예 | Should(#68) |

- 대화 비교 적용은 F-5(#12-b)다. P-6은 대화와 무관하다.
- `/api/auth/me`는 두지 않는다. 왜: 앱 시작·새로고침 때 P-4 하나로 "로그인 여부(401) / 계획 없음(409) / 결과(200)"가 갈린다.
- 입력 저장 = 새 목표 세우기 = 입력 수정, 같은 엔드포인트(R-1). 계획이 있으면 시작월을 유지한다(D-013).
- P-3·P-4가 `calc.web`인 이유: 결과 계산이 필요하고 의존 방향은 calc → plan뿐이다. P-3·P-4 컨트롤러는 `/internal/calc` 컨트롤러와 **다른 클래스**다(C12a-11 (h)).

### C12a-2. 인증·쿠키·공통 헤더

#12-b **C12b-2를 그대로 따른다**(JWT HttpOnly 쿠키, `SameSite=Lax` 이상, 같은 사이트·Vite 프록시 `/api`, `credentials: "include"`, 성공 `application/json`, 오류 `application/problem+json`). 이 절은 C12b-2가 #12-a로 미룬 것만 정한다.

#### C12a-2-1. 쿠키

| 속성 | 값 | 왜 |
|---|---|---|
| 이름 | `moeun_at` | 서비스 접두어 + access token |
| 값 | JWT(`sub` = memberId, `exp` = 발급 + 30분) | ADR-002, D-052 |
| `HttpOnly` | 항상 | JS가 토큰을 읽지 못하게 |
| `Secure` | 기본 `true`. `auth.cookie.secure=false`는 `local` 프로필에서만 | 로컬 http 탈출구. **데모 접속 주소가 HTTPS가 아니면 `Secure` 쿠키가 저장되지 않아 로그인이 안 될 수 있다** → 데모 방식은 쟁점 X-3(결정됨, D-076: 로컬 데모, 임시), 확인은 #58(J-16) [D-076] |
| `SameSite` | `Lax` | CSRF를 끈 상태의 교차 사이트 방어 |
| `Path` | `/` | 로그아웃 만료 쿠키와 경로가 어긋나지 않게 |
| `Max-Age` | `1800`. 로그아웃·401 때 `0` | FR-AUTH-02 |

- 토큰은 쿠키 `moeun_at`에서만 읽는 `BearerTokenResolver`(#16). `Authorization` 헤더는 받지 않는다.
- 리졸버는 **`POST /api/auth/login`에서 `null`을 돌려준다**. 왜: 리소스 서버 필터는 `permitAll` 경로에서도 토큰을 검증해 실패하면 401을 낸다. 깨진 쿠키가 남은 브라우저가 로그인을 못 하게 된다.
- **JWT 서명 키는 설정값(환경 변수)으로 고정한다.** 왜: 기동마다 새로 만들면 재시작마다 모든 쿠키가 깨진다.
- 401 엔트리 포인트는 C3-1 본문과 함께 `moeun_at` 만료 쿠키(`Max-Age=0`, 같은 속성)를 내린다. 왜: 깨진 쿠키가 남지 않는다.
- `/internal/**`은 다른 필터 체인(API 키), 쿠키를 보지 않는다.

#### C12a-2-2. 토큰 연장(D-052)

| 요청 | 새 쿠키 | 왜 |
|---|---|---|
| P-1 로그인 | `@SkipRenewal`. 핸들러가 새 쿠키를 직접 발급 | 다시 로그인할 때 연장 쿠키(옛 회원)와 핸들러 쿠키가 한 응답에 둘 실리지 않게 |
| P-2 로그아웃 | `@SkipRenewal`. 핸들러가 `Max-Age=0`만 | 만료 쿠키 보호 |
| F-2 폴링 | `@SkipRenewal`(C12b-2) | D-052 |
| 그 밖의 인증된 `/api/**` | 연장(인터셉터 `preHandle`, #34) | FR-AUTH-02 |

- 인증이 통과한 요청의 4xx·5xx에도 연장한다. 필터에서 나는 401에는 붙지 않는다.
- Issue #12 수용 기준 문장(R-12): "사용자 조작 요청 중 연장 제외는 F-2뿐이다. 로그인·로그아웃은 핸들러가 쿠키를 직접 내리므로 `@SkipRenewal`을 붙인다."
- 프론트에는 만료 타이머를 두지 않는다(FR-AUTH-02 비고).

#### C12a-2-3. 401 처리와 래퍼 규칙

- 보호된 엔드포인트의 쿠키 없음·서명 불일치·만료 → `401 UNAUTHENTICATED`, `errors: []`, `application/problem+json`(+ 만료 쿠키).
- **프론트 래퍼(#13) 규칙**
  1. 분기는 HTTP 상태가 아니라 `code`로만 한다. `code === "UNAUTHENTICATED"`면 로그인 화면. P-1의 `401 INVALID_CREDENTIALS`는 로그인 화면에 머문다. 왜: 상태로 분기하면 로그인 실패 안내 대신 화면 이동이 된다.
  2. 본문이 `application/problem+json`이 아니거나 JSON 파싱에 실패하면(프록시 502, HTML 등) `code`가 없는 것으로 보고 **네트워크 오류 경로**(문구표 `common.networkError`)로 보낸다.
  3. `UNAUTHENTICATED`를 받으면 공유 상태(C12a-6-7)를 비우고 폴링을 멈춘 뒤 로그인 화면으로. P-3 중이었다면 입력값은 잃는다(데모 범위에서 수용).
  4. **[v3]** 이미 로그인 화면에 있으면 화면을 다시 이동하지 않는다(상태 비우기만).
- 403은 쓰지 않는다.

---

### C12a-3. P-1 로그인 `POST /api/auth/login`

FR-AUTH-01, NFR-SEC-01, D-051, D-052.

요청 `{ "loginId": "demo01", "password": "demo-pass-01" }` → 응답:
```http
HTTP/1.1 200 OK
Content-Type: application/json
Set-Cookie: moeun_at=<JWT>; Max-Age=1800; Path=/; Secure; HttpOnly; SameSite=Lax

{}
```

- 본문이 `{}`인 이유: auth는 의존이 `{}`라 계획 유무를 모른다. 프론트는 성공 뒤 `loadPlan()`(P-4)으로 진입을 가른다(#51, C12a-11-2).
- 요청 본문은 단순 record. Jackson의 문자열 강제 변환을 허용한다. `400 MALFORMED_REQUEST`는 **JSON 파싱 실패·본문이 객체가 아님**일 때만. 왜: 로그인에서 400/422를 엄격히 가를 이익이 없다.
- 회원가입 없음. 데모 계정은 시드(#16).

서버 동작: (1) 빈 값 검사(trim 하지 않음) → (2) `member` 조회 + 해시 비교, 실패면 `401 INVALID_CREDENTIALS`(아이디 없음·비밀번호 틀림 구분 없음) → (3) `auth.MemberLoggedIn{memberId}` 발행 → chat 동기 리스너가 이전 대화 삭제(쿠키 전) → (4) JWT 발급, `Set-Cookie`, `200 {}` → (5) 로그 `auth.login`.
- 이미 로그인한 상태의 재로그인도 같은 동작. 삭제 실패면 로그인도 `500`. 시도 횟수 제한 없음.

| HTTP | `code` | 언제 | `errors` | 프론트 동작(#47) |
|---|---|---|---|---|
| 400 | `MALFORMED_REQUEST` | JSON 파싱 실패, 본문이 객체 아님 | `[]` | `auth.loginFailed` |
| 422 | `VALIDATION_FAILED` | `loginId`·`password` 없음·빈 문자열 | `[{field, reason:"REQUIRED"}]` | 입력칸 아래 `validation.REQUIRED` |
| 401 | `INVALID_CREDENTIALS` | 아이디 없음, 비밀번호 틀림, `login_id` 50자 초과 | `[]` | `auth.invalidCredentials`, 로그인 화면 그대로, 비밀번호 칸만 비움 |
| 500 | `INTERNAL_ERROR` | DB 오류, 대화 삭제 실패 | `[]` | `auth.loginFailed` |

### C12a-4. P-2 로그아웃 `POST /api/auth/logout`

FR-AUTH-03, D-051, D-052, NFR-INT-03. 요청 본문 없음(또는 `{}`).

서버 동작: (1) 인증 필수. 만료·없는 토큰이면 `401 UNAUTHENTICATED`(대화 삭제는 다음 로그인 때) → (2) `auth.MemberLoggedOut{memberId}` 발행 → chat 동기 삭제 → (3) 만료 쿠키 + `200 {}`, `@SkipRenewal`.
- 왜 POST: 상태 변경이고 `SameSite=Lax`가 교차 사이트 POST에 쿠키를 싣지 않는다.
- 로그아웃 뒤에도 기존 토큰은 남은 시간 동안 서버에서 유효하다(ADR-002 §3, 데모 범위 수용).

| HTTP | `code` | 프론트 동작 |
|---|---|---|
| 200 | — | 공유 상태 비우기·폴링 중지 → 로그인 화면 |
| 401 | `UNAUTHENTICATED` | 같음(이미 로그아웃된 것과 같다) |
| 500·400·405·415 | 해당 코드 | `auth.logoutFailed` 토스트, 화면 유지 |

---

### C12a-5. P-3 입력 저장 `PUT /api/plan`

FR-INP-01~07·09·10·11, FR-RES-01, CP-02, D-013, D-032, D-035, D-045.

#### C12a-5-1. 요청

```json
{
  "expectedRevision": 11,
  "goalType": "HOUSE",
  "goalCustomName": null,
  "goalAmount": 20000000,
  "targetPeriodMonths": 36,
  "heldAssets": 5000000,
  "monthlySavingAmount": 300000,
  "savingsRateInputPct": null,
  "savingsRatioPct": null
}
```

| 필드 | JSON 타입 | 필수 | 뜻 |
|---|---|---|---|
| `expectedRevision` | integer \| null | 키 필수 | 지금 가진 `revision`. 계획이 없다고 믿으면 `null` |
| `goalType` | string | 예 | `GoalType` 코드. 임시 목록은 아래 [D-075] |
| `goalCustomName` | string \| null | `CUSTOM`일 때만 | FR-INP-02(Should). trim 뒤 **1~100자**(UTF-16) [D-074]. CUSTOM이 아니면 무시하고 `null` 저장 |
| `goalAmount` | integer(KRW) | 예 | FR-INP-03 |
| `targetPeriodMonths` | integer(MONTH) | 예(D-045) | FR-INP-04 |
| `heldAssets` | integer(KRW) | 예 | FR-INP-05. 0 허용 |
| `monthlySavingAmount` | integer(KRW) | 예(D-045) | FR-INP-06 |
| `savingsRateInputPct` | number(PCT) \| null | 키 필수 | FR-INP-07. `null` = 기본금리(D-012·D-034) |
| `savingsRatioPct` | integer(PCT) \| null | 키 필수 | **[v4]** FR-INP-08. 적금 비중(%), 0~100 정수. **`null` = 사용자가 정한 비중 없음** → `plan_version.savings_ratio_pct`에 `NULL`을 저장하고 계산은 기준 데이터의 기본 비중을 쓴다. **현재 버전 값을 복사하지 않는다**(R-9의 INPUT 경로 철회) [D-085, D-086] |

- **`goalType` 임시 목록**(enum 선언 순서 = 드롭다운 순서, 표시명 문구표 `goal.<CODE>`) [D-075]:
  `HOUSE`(내 집 마련), `CAR`(자동차), `WEDDING`(결혼), `EDUCATION`(교육), `TRAVEL`(여행), `EMERGENCY_FUND`(비상금), `CUSTOM`(직접 입력). 괄호 안은 문구표 임시 문장.
  - **[v3]** `CUSTOM`은 FR-INP-02(Should)를 구현하지 않은 동안 #48이 드롭다운에서 숨긴다(코드는 둔다). 숨김 조건은 #48 수용 기준에 적는다.
  - **[v3]** 프론트는 문구표에 없는 `goal.<CODE>`를 만나면 **코드 문자열을 그대로 표시**한다(화면이 깨지지 않게). 왜: 서버가 코드를 먼저 더하고 문구가 늦을 수 있다. #18의 "없는 키는 로드 때 실패" 규칙과는 별개로, 표시 helper 한 곳(#48 util 옆)에서만 대체한다.
  - **[v3] 주의(제공자)**: 코드는 `plan_version.goal_type_code`에 저장된다. 데이터가 생긴 뒤 코드를 지우거나 이름을 바꾸면 옛 행을 enum으로 읽을 때 실패한다. 목록 변경은 #19 머지 전이 가장 싸고, 그 뒤면 #58 초기화와 함께 한다.
- **[v4] 요청에 없는 것**: `etfProductCode`(현재 버전 값 복사, 첫 저장이면 대표 상품 `SP500`. 입력 화면에는 종목 선택이 없다, #154), `existingMonthlyContribution`(항상 `NULL`), 시작월(첫 저장이면 요청일이 속한 달 D-032, 아니면 현재 버전 값 복사 D-013), 기준 시나리오(첫 저장이면 `AVG`).
- 본문 전체를 `JsonNode`(또는 `Map<String, JsonNode>`)로 받아 `ConditionValidator`에 넘긴다. 왜: 키 없음과 `null`을 구분해야 하고, Boot 기본 `ACCEPT_FLOAT_AS_INT`로 `300000.5`가 잘리지 않게.

#### C12a-5-2. 서버 동작과 검사 순서

**`calc.application`의 입력 저장 유스케이스 하나가 `@Transactional`을 연다.** 2~5가 모두 그 안에서 돈다.

| 순서 | 어디서 | 검사·동작 | 결과 |
|---|---|---|---|
| 0 | 필터 | 토큰 | `401 UNAUTHENTICATED` |
| 1 | calc.web | JSON 파싱, 숫자 필드가 JSON 숫자인지, `goalType`이 알려진 코드인지(`plan.GoalType`은 plan 루트 enum), `expectedRevision`이 정수 또는 `null` | `400 MALFORMED_REQUEST` |
| 2 | plan `PlanSaveService`(`REQUIRED` 합류) | `PlanInputValidator` + `ConditionValidator.validate(INPUT_EDIT, …)`: `REQUIRED`·`OUT_OF_RANGE`·`NOT_INTEGER`·`TOO_MANY_DECIMALS`·CUSTOM 이름. 걸린 필드 모두. `INPUT_EDIT`에는 CP-07 개수 제한을 걸지 않는다 | `422 VALIDATION_FAILED` |
| 3 | plan | 계획 있음 + `expectedRevision=null` / 계획 없음 + `≠null` / `plan.revision ≠ expectedRevision` | `409 PLAN_CHANGED` |
| 4 | plan | `plan_version` INSERT(`source='INPUT'`, 시작월·비중 복사) → 첫 저장: 네이티브 `INSERT INTO plan … ON CONFLICT (member_id) DO NOTHING` / 수정: `UPDATE plan SET previous=current, current=:v, revision=revision+1 WHERE member_id=? AND revision=?` → 영향 0행이면 `PlanChangedException` → `PlanChanged(INPUT)` 발행(커밋 뒤 전달) | 0행 → 전체 롤백 `409 PLAN_CHANGED` |
| 5 | calc 결과 조회 서비스(#29) | 현재 계획 스냅샷을 읽고(4에서 바꾼 값), 그 버전의 PLAN `calc_run`이 없으면 계산·기록(회원 잠금, C12a-6-6) → 결과·display | 성공 `200 PlanView` / 실패면 1~4까지 롤백, `500 INTERNAL_ERROR` |

- 왜 한 트랜잭션인가: 부르는 쪽이 calc라 의존 방향(calc → plan)을 그대로 두고 묶을 수 있다. 저장을 먼저 커밋하면 "계산할 수 없는 계획"이 저장되어 P-4 500 → 재저장 409 루프에 갇힌다. ERD Q4-a도 원래 한 트랜잭션이다.
- 왜 네이티브 쿼리인가: `plan.member_id`는 할당 PK라 Spring Data `save()`가 merge를 해 두 탭 경쟁에서 조용히 덮어쓴다. `ON CONFLICT DO NOTHING`은 오류 없이 0행을 돌려준다.
- **[v3] 네이티브 쓰기 뒤 영속성 컨텍스트를 비운다**: `@Modifying(flushAutomatically = true, clearAutomatically = true)`(또는 순서 3의 읽기를 엔티티가 아닌 프로젝션으로, A팀 재량). 왜: 순서 3에서 JPA로 읽은 `Plan` 엔티티가 1차 캐시에 남으면 순서 5의 현재 계획 조회가 **옛 `revision`·`currentVersionId`**를 돌려줘 옛 버전으로 계산하고 PlanView를 만든다(C12a-6-3 4번을 깸). #19 테스트: "저장 직후 같은 트랜잭션의 현재 계획 조회가 새 `revision`·`currentVersionId`를 돌려준다".
- `PlanChanged(INPUT)` 리스너는 커밋 뒤 돌고, 그 버전의 `calc_run(PLAN)`이 이미 있으므로 기록을 건너뛴다(C12a-6-6).
- 보유 자산 ≥ 목표는 검증 오류가 아니다. 저장하고 `200`, `result.status = ALREADY_EXCEEDED`(FR-RES-01, CF-03).
- 입력 수정에도 `expectedRevision`을 검사한다(Y-3, PR-08, TBD-37 "버전 확인"). 왜: 다른 탭에서 적용한 계획을 입력 화면의 오래된 값으로 덮어쓰지 않게.

#### C12a-5-3. 응답 `200`

P-4와 같은 `PlanView`(C12a-6). 프론트는 이 응답으로 결과 화면을 그리고 P-4를 부르지 않는다. 왜: 한 번의 왕복, 결과 화면 코드 하나.

#### C12a-5-4. 오류와 프론트 동작 (#48 입력 화면)

| HTTP | `code` | `errors` | 프론트 동작 |
|---|---|---|---|
| 400 | `MALFORMED_REQUEST` | `[]` | 개발 오류. `input.saveFailed` 토스트, 입력값 유지 |
| 422 | `VALIDATION_FAILED` | 1개 이상 | 각 `field` 입력칸 아래 안내. 문구는 `validation.<reason>` + C12a-5-6 (f) |
| 409 | `PLAN_CHANGED` | `[]` | `plan.changedElsewhere`(취지: "계획이 다른 곳에서 바뀌었어요. 다시 저장하면 입력한 값으로 바뀌어요"). 입력 화면에 머물고 값을 지우지 않는다. `loadPlan()`으로 공유 상태를 새 값으로(`NO_PLAN`이면 C12a-6-7 규칙 2) → 다시 누르면 새 `revision`으로 저장. 다른 탭의 변경을 덮어쓰는 것은 의도된 동작 |
| 401 | `UNAUTHENTICATED` | `[]` | 로그인 화면(C12a-2-3) |
| 500 | `INTERNAL_ERROR` | `[]` | `input.saveFailed` 토스트, 입력값 유지, **P-4를 부르지 않는다**(500이면 계획은 바뀌지 않았다) |

- **[v3] 요청 중에는 저장 버튼을 끈다**(응답이 오면 다시 켬). 왜: 같은 PUT이 두 번 나가면 두 번째가 `409 PLAN_CHANGED`("다른 곳에서 바뀌었어요")가 되어 사용자를 헷갈리게 한다.
- 422 `errors[].field`는 요청 키 이름(camelCase). `goalCustomName` 길이 초과는 `OUT_OF_RANGE`, `min:1, max:100, unit:"CHAR"` [D-074].
- 프론트 사전 검사는 "비어 있음"과 입력 형식(C12a-5-6 숫자 형식)만. 범위 수치는 하드코딩하지 않고 422의 `min`·`max`로 안내한다. 왜: 수치는 `rules.yml` 한 곳.

422 예시 1(범위 + 누락):
```json
HTTP/1.1 422 Unprocessable Content
Content-Type: application/problem+json

{
  "type": "about:blank",
  "title": "입력 범위를 벗어났어요",
  "status": 422,
  "detail": "monthlySavingAmount: OUT_OF_RANGE, targetPeriodMonths: REQUIRED",
  "instance": "/api/plan",
  "code": "VALIDATION_FAILED",
  "errors": [
    { "field": "monthlySavingAmount", "reason": "OUT_OF_RANGE", "min": 10000, "max": 10000000, "unit": "KRW" },
    { "field": "targetPeriodMonths", "reason": "REQUIRED" }
  ]
}
```

422 예시 2(소수):
```json
HTTP/1.1 422 Unprocessable Content
Content-Type: application/problem+json

{
  "type": "about:blank",
  "title": "입력 범위를 벗어났어요",
  "status": 422,
  "detail": "monthlySavingAmount: NOT_INTEGER, savingsRateInputPct: TOO_MANY_DECIMALS",
  "instance": "/api/plan",
  "code": "VALIDATION_FAILED",
  "errors": [
    { "field": "monthlySavingAmount", "reason": "NOT_INTEGER" },
    { "field": "savingsRateInputPct", "reason": "TOO_MANY_DECIMALS", "scale": 2 }
  ]
}
```

#### C12a-5-5. 범위 수치 (`rules.yml › input.fields`, 임시)

| 필드 | min | max | unit | scale | 출처 |
|---|---|---|---|---|---|
| `monthlySavingAmount` | 10000 | 10000000 | KRW | 0 | #17·TBD-09·D-043(확정) |
| `savingsRateInputPct` | 0 | 20 | PCT | 2 | internal-calc C3-3 |
| `targetPeriodMonths` | 1 | 360 | MONTH | 0 | internal-calc C3-3 |
| `goalAmount` | 10000 | 10000000000 | KRW | 0 | 임시(R-4). `rules.yml` 주석 "임시" |
| `heldAssets` | 0 | 10000000000 | KRW | 0 | 임시(R-4) |
| `goalCustomName` | 1 | 100 | CHAR | — | TBD-09 [D-074] (쟁점 X-1 A면 50이었으나 D-074로 100자 결정됨) |

- 같은 키는 경로와 무관하게 같은 범위(CP-02). 경로마다 다른 것은 바꿀 수 있는 키 목록뿐.
- `rules.yml`에 금액 `step`은 두지 않는다. 서버는 원 정수면 받는다.

#### C12a-5-6. 프론트 변환 규칙 (util 하나, #48)

| 규칙 | 내용 | 예 |
|---|---|---|
| (d) 만원 → 원(보낼 때) | 입력 문자열이 `^\d+(\.\d{1,4})?$`이면 **소수점을 오른쪽으로 네 칸 옮기는 문자열 연산**으로 원 정수를 만든다. 부동소수 곱셈 금지. 소수 5자리 이상·형식 오류는 프론트가 막는다(`input.invalidAmountFormat`) | `"30"` → 300000, `"34.5"` → 345000, `"0.0001"` → 1 |
| (d) 원 → 만원(채울 때) | 원 정수 문자열을 왼쪽으로 네 칸 옮기고 끝의 0과 소수점을 지운다. 반올림·내림 없음 | 300000 → `"30"`, 345000 → `"34.5"`, 1 → `"0.0001"` |
| (e) 기간 | 보낼 때 `년 × 12 + 월`, 채울 때 `년 = floor(m / 12)`, `월 = m % 12` | 36 → 3년 0개월 |
| (f) 422 범위 문구 | `KRW`: **[v3] 0이면 `unit.won` 틀로 "0원"(D-061)**, 그 밖은 (d)로 만원 문자열 + 천 단위 쉼표를 `unit.manwon` 틀로("약" 없음, 정확한 값). `MONTH`: `unit.month` "{y}년 {m}개월"(1 → "0년 1개월", 360 → "30년 0개월"). `PCT`: `unit.pct` "연 {값}%"(JSON 숫자 그대로). `CHAR`: `unit.char` "{n}자" | `10000000` → "1,000만원", `0` → "0원", `10000000000` → "1,000,000만원" |
| 금리 칸 | `savingsRateInputPct`는 JSON 숫자 그대로 칸에 넣고, 보낼 때 칸 문자열을 JSON 숫자로. 소수 3자리 이상은 서버 `TOO_MANY_DECIMALS` | |

- 단위 테스트(#48): `34.5 ↔ 345000`, `0.0001 ↔ 1`, `30 ↔ 300000`, `12.34567` 거절, `1.1 → 11000`(부동소수 오류 없음), 360 ↔ 30년 0개월, **[v3] KRW 0 → "0원"**.
- **[v3] util 위치**: `frontend/src/features/input/**` **밖** 공용 폴더(예: `frontend/src/shared/units/**`, 이름 재량). #48이 만들고 #51(입력 채우기)이 가져다 쓴다. 왜: 두 Issue의 paths가 달라 한쪽 폴더에 두면 다른 쪽이 paths 밖을 고친다. `goal.<CODE>` 표시 helper(미지 코드 대체)도 같은 폴더.
- 왜 소수 허용인가: 만원 칸(TBD-09)을 유지하면서 대화로 적용한 원 단위 값(345,000원 등)을 잃지 않고 보여 준다. 내림은 값이 조용히 바뀌고, 원 단위 칸은 TBD-09와 다르다.

---

### C12a-6. P-4 현재 계획·결과 조회 `GET /api/plan`

FR-PLAN-05·06, FR-RES-01~07, FR-INP-11, NFR-INT-02, D-010, D-062, D-072.

#### C12a-6-1. 응답 `200` — `PlanView`

```json
{
  "revision": 12,
  "calcRunId": 540,
  "baselineResultScenario": "AVG",
  "undoAvailable": false,
  "plan": {
    "goalType": "HOUSE",
    "goalCustomName": null,
    "goalAmount": 20000000,
    "targetPeriodMonths": 36,
    "heldAssets": 5000000,
    "monthlySavingAmount": 300000,
    "savingsRateInputPct": null,
    "savingsRatioPct": null,
    "etfProductCode": "SP500"
  },
  "result": {
    "status": "CALCULATED",
    "goalAmount": 20000000,
    "calcStartMonth": "2026-11",
    "targetMonth": "2029-11",
    "scenarios": [ { "...": "C1a-4 ScenarioResult 그대로(annualReturnPct 없음) + monthlyPoints" } ],
    "assumptions": { "...": "C1a-4 그대로" }
  },
  "display": { "...": "C1a-6 키·형식 그대로" }
}
```

| 필드 | 타입 | 뜻 | 누가 쓰나 |
|---|---|---|---|
| `revision` | integer | `plan.revision` | 공유 상태 `revision`·`view`(C12a-6-7) |
| `calcRunId` | integer | 이 결과의 `calc_run(PLAN)` id. 같은 `revision` 동안 같다(단서 C12a-6-6) | `view` → #54 F-3 |
| `baselineResultScenario` | `GOOD`\|`AVG`\|`BAD`\|null | 결과 요약에 쓸 시나리오(`findBaselineResult()`, 대체 규칙 포함). 초과면 `null` | #49 |
| `undoAvailable` | boolean | 되돌릴 직전 계획이 있는지(`plan.previous_version_id` 유무). 첫 저장 직후·되돌린 직후 `false`, 입력 수정 저장·적용 뒤 `true` | #263 '직전 계획으로 되돌리기' 버튼(D-095) |
| `plan` | object | 사용자가 입력한 원래 값(원 단위, nullable 그대로). `calcStartMonth`·`baselineScenario` 없음 | #51 입력 채우기 |
| `result` | object | `status`, `goalAmount`, `calcStartMonth`, `targetMonth`, `scenarios[]`(+`monthlyPoints`), `assumptions`만 | 판단·그래프 점 |
| `display` | object | C1a-6 키·형식(접두어 `""`, 조건 `"conditions."`) | 화면의 모든 숫자 문자열 |

- 내보내지 않는 것: `conditions`(raw), `result.annualReturnPct`(수익 약속처럼 읽힐 위험), `result.requiredMonthlySaving`(항상 null), `result.dataVersions`, `result.baselineScenario`·`plan.baselineScenario`, `plan.calcStartMonth`(D-035 안내는 `display["calcStartMonth"]`), `currentVersionId`·`previousVersionId`·`memberId`. 왜: 화면이 쓰지 않는 필드는 화면 전용 View에서 뺀다. `undoAvailable`은 D-095(#263)로 화면이 쓰게 되어 내보낸다. 공개 `CalcResultView`·planContext는 그대로다.
- 응답에 문구 키 목록을 넣지 않는다. 프론트가 C12a-7대로 고른다(#30 `tax.note`·#31 D-035 "문구 키" 수용 기준 삭제, Y-13).
- `display`는 planContext와 같은 `ResultDisplayBuilder`(#28)가 같은 접두어로 만든다. `<S>.goalReachMonth`는 `isAchieved=true`일 때만 있다. 화면 전용으로 `<S>.gapBadge`(#224)가 더 붙는다. 이 키는 planContext·ai-server 계약에는 없고, 목 JSON·plan-context fixture와 글자까지 같다는 비교에서도 제외한다.

#### C12a-6-2. `monthlyPoints` [v3]

| 필드 | 이 계약 |
|---|---|
| `scenarios[].monthlyPoints` | 있다(planContext·`/internal/calc`에는 키 자체가 없음) |
| `[].month` | `"YYYY-MM"` |
| `[].afterTaxAsset` | integer(KRW). 그 달의 세후 예상 자산 |

- **[v3] `principal`은 내보내지 않는다.** 왜: FR-RES-04·#50은 자산선과 목표선만 그린다. 점당 숫자가 반이 된다(최대 361점 × 3). 엔진 `MonthlyPoint`의 `principal`은 그대로 두고 View가 옮기지 않을 뿐이다. 원금선이 필요해지면 키 하나를 다시 더한다.
- **규칙**(k = 0 … `targetPeriodMonths`):
  - k번째 점 `month` = `result.calcStartMonth` + k개월. 점 수 = `targetPeriodMonths + 1`, 오름차순.
  - **첫 점(k=0)**: `month = result.calcStartMonth`, `afterTaxAsset = plan.heldAssets`(납입 전 출발점, 보유 자산은 운용 수익 없음 CP-03).
  - **마지막 점(k=N)**: `month = result.targetMonth`, `afterTaxAsset` = 그 시나리오의 `afterTaxAsset`.
  - 왜: 가로축 양 끝 라벨(`display["calcStartMonth"]`·`display["targetMonth"]`)과 점이 맞아야 프론트가 축을 다시 만들지 않는다.
- **[v3] 이 규칙을 지키는 곳은 엔진이다.** #26(시계열 골격): 첫 점·점 수·월 증가. #27: 마지막 점 = 시나리오 값(테스트). #9 계산 명세에 한 줄: "k번째 점 = k번째 납입 뒤의 값, 월 = 시작월 + k(k=0은 납입 전)". **화면 View(#30·#31)는 엔진 목록을 복사만 하고 점을 붙이거나 빼지 않는다**(View 테스트는 복사 확인). 왜: 시계열 정의는 엔진 것이다(`ScenarioResult.monthlyPoints` Javadoc "계산 시작월부터의 월별 예상 자산"). 납입 시점(TBD-04·05)이 #9에서 정해지므로 View가 엔진 관례를 모르고 붙이면 같은 달이 두 번 나올 수 있다.
- 응답 DTO는 `calc.web` 화면 전용 View. 공개 `CalcResultView`(monthlyPoints 없음)는 바꾸지 않는다.

#### C12a-6-3. 서버 동작

1. 필터 → `401`.
2. calc 결과 조회 서비스(#29, ERD Q1)가 **plan 공개 현재 계획 조회를 한 번** 부른다. 행이 없으면 `409 NO_PLAN`.
3. 그 스냅샷(`revision`, `currentVersionId`, 입력값, `baselineScenario`)의 버전으로 PLAN `calc_run`을 찾고 없으면 계산·기록(C12a-6-6). 결과 + `ResultDisplayBuilder`.
4. 컨트롤러는 #29가 돌려준 **스냅샷 + 결과 하나**로 `PlanView`를 만든다. 계획을 따로 다시 읽지 않는다. 왜: 두 번 읽으면 사이에 다른 탭의 적용이 끼어 `revision`과 결과가 다른 버전을 가리킨다.

- GET이 `calc_run`을 INSERT할 수 있어 **읽기 전용 트랜잭션이 아니다**(`readOnly=true` 금지, C12a-6-6 조건 3). 재로그인 시 최신 데이터로 다시 계산하지 않는다(D-010).

#### C12a-6-4. 오류와 프론트 동작

| HTTP | `code` | 프론트 동작 |
|---|---|---|
| 409 | `NO_PLAN` | C12a-6-7 규칙 2로 처리 → 수용하면 빈 입력 화면(FR-PLAN-06) |
| 401 | `UNAUTHENTICATED` | 로그인 화면 |
| 500 | `INTERNAL_ERROR` | 결과 화면 자리에 `result.loadFailed` + 다시 시도 버튼(`loadPlan()`). 대화창은 그대로 |

- `409 NO_PLAN`(R-10). 왜: `200 {plan:null}`이면 `PlanView`가 nullable이 되어 모든 소비자 분기가 늘어난다.

#### C12a-6-5. 언제, 누가 부르나 [v3]

- **[v3] `GET /api/plan`은 공유 store(#13)의 `loadPlan()` 하나만 부른다.** 화면·훅은 `fetch`를 직접 하지 않는다.
- **single-flight**: `loadPlan()`이 불렸을 때 진행 중인 요청이 있고 **그 요청이 지금과 같은 `revision` 값에서 시작됐으면** 같은 약속을 돌려준다. 그 사이 `revision`이 바뀌었으면(F-5·P-5 성공 직후 등) 합류하지 않고 새로 보낸다. 왜: 쓰기 전에 출발한 GET에 합류하면 옛 계획을 받는다. 응답 순서가 뒤바뀌어도 규칙 1(작은 값 무시)이 정리한다. 개발 모드 이중 effect·여러 화면의 동시 마운트가 요청 하나로 합쳐져 목 시퀀스 순서도 밀리지 않는다.
- 부르는 때와 부르는 쪽:

| 때 | 부르는 Issue |
|---|---|
| 앱 시작·새로고침(라우팅 가드) | #47 |
| 로그인 성공 직후 진입 분기 | #51 |
| 결과 화면 500 다시 시도 | #49 |
| P-3 `PLAN_CHANGED` 뒤 | #48 |
| P-5 성공·`NOTHING_TO_UNDO`·`PLAN_CHANGED` 뒤 | #51 |
| F-5 성공·해당 오류 뒤 | #53 |
| F-3 `404` 뒤(C12b-3a) | #54 |
| (P-6 성공 뒤) | #68 |

- P-3 성공 뒤·P-3 500 뒤에는 부르지 않는다.

#### C12a-6-6. 같은 계획 버전 = 같은 `calcRunId`

**계약 문장 [v3]**: **기준 데이터 활성 버전과 `engine_version`이 그대로인 동안**, 같은 `revision`(같은 `plan.current_version_id`)에 대해 P-3·P-4가 돌려주는 `calcRunId`는 항상 같다.
- 왜: #54는 "`calcRunId`가 바뀌고 `CALCULATED`면 F-3"(C12b-3a)이라, 같은 계획에서 id가 바뀌면 설명이 한 번 더 생긴다.
- 단서의 뜻: 기준 데이터를 다시 적재하거나 엔진 버전이 바뀌면 ERD Q1-(c)·#29 G-18("활성 버전과 같은 최신 PLAN 기록이 있으면 다시 기록하지 않음")에 따라 같은 계획 버전에 새 행이 생긴다. 숫자가 달라졌다는 뜻이라 #54가 F-3을 다시 부르는 것이 맞다. 해커톤에서는 재배포 때만 생긴다(D-010, D-045).

| 경로 | 같은 버전의 PLAN 기록을 만드는 쪽 | 한 트랜잭션(V-1)만으로 |
|---|---|---|
| P-3 입력 저장 | 유스케이스(트랜잭션 안) → 커밋 뒤 리스너는 기록을 보고 건너뜀 | 풀림 |
| F-5 대화 적용, P-5 되돌리기, (P-6) | 커밋 뒤 리스너 **와** 프론트의 P-4 재조회가 거의 동시에 "없으면 기록" | 안 풀림 → 아래 잠금 |

**구현(#29)**: "현재 버전의 PLAN 기록 확인 → 없으면 계산·기록"을 한 메서드로 두고 확인 전에 `SELECT pg_advisory_xact_lock(...)`로 회원 단위 잠금을 잡는다(PostgreSQL 내장, 새 라이브러리·스키마 변경 없음). `PlanChanged` 리스너도 **같은 메서드**로 기록한다. 늦은 쪽은 앞쪽이 커밋한 행을 보고 건너뛴다.

**[v3] 지켜야 할 조건 4개** (하나라도 어기면 잠금이 무력해져 2행이 생기거나 INSERT가 실패한다)
1. **기록 메서드는 호출자 트랜잭션 안에서만 돈다**: `@Transactional(propagation = MANDATORY)`. 왜: `pg_advisory_xact_lock`은 트랜잭션 끝에 풀린다. 트랜잭션 밖(autocommit)이면 그 문장이 끝나자마자 풀린다. `REQUIRES_NEW`도 안 된다(P-3에서 아직 커밋 안 된 `plan_version`을 못 봐 FK 위반).
2. **격리 수준은 PostgreSQL 기본 READ COMMITTED, 잠금 획득 뒤에 `findLatest`를 실행한다.** 잠금 전에 같은 조회를 해 둔 JPA 결과를 재사용하지 않는다. 왜: REPEATABLE READ면 스냅샷이 잠금 대기 전에 잡혀 앞쪽이 커밋한 행을 못 본다.
3. **호출 트랜잭션은 `readOnly=true`가 아니다.** P-4, planContext `findForCurrentPlan`(#78) 모두. 왜: PostgreSQL이 "read-only transaction"으로 INSERT를 거절한다. #78 본문에 "`findForCurrentPlan`은 readOnly 아님" 한 줄.
4. **리스너는 `@ApplicationModuleListener`**(커밋 뒤, 새 트랜잭션)로 받아 같은 메서드를 부른다. 왜: 같은 트랜잭션의 `@EventListener`면 커밋 전에 돌고 이미 잡은 잠금을 재진입해 의미가 없다.
- (A팀 재량) 잠금 키는 두 인자형 `pg_advisory_xact_lock(<상수 네임스페이스>, memberId)` 권장, `bigint` 하나도 허용. "잠금 없이 조회 → 없으면 잠금 → 다시 조회 → 기록"(이중 확인)도 같은 보장이다.
- 교착 없음: 잠금 순서는 "plan 행 → advisory"(P-3) 하나뿐이고 P-4·리스너·QUESTION은 plan 행을 잠그지 않는다. F-5·P-5는 advisory를 잡지 않는다.
- 기각한 대안: "가장 작은 id 선택" — 경쟁 당사자에게 안정적이지 않고(늦은 쪽은 앞쪽의 미커밋 행을 못 봄), Q1-c 분기에서 옛 행을 계속 고른다. 유일 제약 — 스키마 변경이고 #12-b C12b-7-3이 두지 않기로 했다. `plan` 행 `FOR UPDATE` — 잠금 의미가 모듈 경계를 넘고 F-5가 계산 시간만큼 기다린다.
- 테스트(#29, Testcontainers): 같은 버전에 대해 리스너와 결과 조회를 동시에 실행 → `calc_run(PLAN)` 1행, 두 호출의 `calcRunId` 같음.
- 이로써 #12-b C12b-7-3·plan-context C1a-10의 "2행이 생길 수 있으나 무해"는 "(단서 안에서) 생기지 않는다"로 강해진다(Y-17, J-15).

#### C12a-6-7. 프론트 공유 계획 상태 [v3]

공유 store(**#13**이 만드는 것 하나, 팀 공용)는 **두 칸 + 파생값 하나**다.

| 칸 | 값 | 누가 갱신 | 누가 읽나 |
|---|---|---|---|
| `revision` | integer \| null | P-3·P-4 `200`의 `revision`, F-5·P-5·(P-6) `200`의 `revision` | #53 적용 버튼 비활성(D-069), #51 되돌리기 토스트 닫기, 모든 쓰기의 `expectedRevision` |
| `view` | `{revision, calcRunId, status}` + 그 `PlanView` 본문 \| null | P-3·P-4 `200`의 `PlanView`만 | #49·#50 결과 화면, #54 F-3 |
| **`settledStatus`** [v3] (파생) | `CALCULATED`\|`ALREADY_EXCEEDED`\|null | store가 계산: `view.revision === revision`이면 `view.status`, 아니면 **직전 값 유지**. 둘 다 `null`이면 `null` | #52 D-072 초과 문장·버튼 숨김 |

**갱신 규칙**
1. **작은 값으로 덮어쓰지 않는다.** `revision`은 새 값이 지금 값보다 클 때만, `view`는 새 `PlanView.revision`이 지금 `view.revision` 이상일 때만 바꾼다(같으면 `calcRunId`도 같다, C12a-6-6). 왜: 서버 `revision`은 모든 변경에서 1씩만 오르므로 작은 값은 늦게 도착한 옛 응답이다(TBD-37 "최신 응답만 반영").
2. **[v3] `null`로 되돌리는 경우**: 로그아웃 성공, `UNAUTHENTICATED`(래퍼), **`409 NO_PLAN`을 수용할 때**. `NO_PLAN` 수용 규칙은 store 함수 하나(`acceptNoPlan(sentRevision)`, 이름 재량):
   - `sentRevision` = 그 요청을 보낸 시점의 `revision` 칸 값. P-4는 `loadPlan()`이 기록하고, P-5·F-5는 보낸 `expectedRevision`이다(항상 값이 있음).
   - **`sentRevision === null`이었고 지금 `revision`이 값이 있으면 무시한다**(그 사이 P-3이 성공했으니 늦게 온 옛 응답이다).
   - 그 밖에는 **두 칸을 비우고 입력 화면으로 간다**. F-5(C12b-7-2)·P-5(C12a-8-2)의 `NO_PLAN` → 입력 화면 동작은 그대로다.
   - 왜 이 조건뿐인가: 계획을 지우는 API는 없으므로 계획이 있는 동안 보낸 요청이 `NO_PLAN`을 받는 정상 경로는 없다. **예외는 데모 초기화(#58)**다. 이때는 `NO_PLAN`을 받아들여 입력 화면으로 가야 화면이 멈추지 않는다. 초기화 절차에는 "열린 브라우저는 새로고침 또는 다시 로그인"을 적는다(#58, J-16).
3. 최초 로드(`null` → N)는 "바뀜"으로 보지 않는다(#12-b C12b-7-4).

**구독 규칙** (구독 지점은 store 한 곳, 읽는 쪽 셋)
- **D-069(#53)**: `revision` 칸이 바뀌면(최초 로드 제외) 그때 가진 모든 적용 버튼을 끈다. #12-b C12b-7-4 그대로. `revision`이 바뀌기 전에 보낸 F-4 응답이 늦게 `applicable=true`로 와도 비활성 유지.
- **[v3] D-072(#52)**: `settledStatus`를 읽어 초과 문장 표시·지움, 적용 버튼 숨김을 그린다. #52는 #53의 훅을 쓰지 않는다. 왜: #53의 선행이 #52라 반대 방향 의존이 생긴다. F-5·P-5 응답은 `{revision}`뿐이라 `revision`만 먼저 바뀌고 `status`는 P-4가 와야 안다. 바뀐 순간에 판단하면 옛 `status`로 틀린다. "`revision`이 바뀔 때마다 현재 초과 여부로"(D-072)를 정확한 값으로 지키는 해석이며 결정 문장은 그대로다.
- **#54 F-3**: `view.calcRunId`가 바뀌고 `view.status === "CALCULATED"`이면 한 번 부른다(C12b-3a, 초과면 안 부름 D-062).
- **#51 되돌리기 토스트**: 토스트가 들고 있는 값(직전 적용 응답의 `revision`)과 `revision` 칸이 달라지면 닫는다.
- **결과 화면(#49·#50)**: `view`의 `PlanView` 본문만 그린다. 초과 안내는 `view.status`(그 본문 자신의 상태).
- A팀 코드에서 B팀 함수를 부르지 않는다(D-069 취지). 모두 store를 읽기만 한다.

목 흐름(C12a-12)에서 PUT 뒤의 GET이 작은 `revision`을 줘도 규칙 1로 무시된다.

### C12a-7. 결과 화면이 계산하지 않기 위한 대응표

표기는 모두 `display["키"]`(키는 점 포함 평면 문자열). 이 표에 없는 숫자 문자열을 프론트가 만들면 계약 위반이다.

| 영역(SRS, Issue) | 문자열 출처 | 판단에 쓰는 raw |
|---|---|---|
| 조건 바(FR-RES-05, #49) | 목표명: 문구표 `goal.<goalType>`(없으면 코드 문자열) 또는 `plan.goalCustomName`(길어도 줄바꿈·말줄임으로 깨지지 않게). `display["goalAmount"]`, `["conditions.monthlySavingAmount"]`, `["conditions.targetPeriodMonths"]`, `["conditions.savingsRatioPct"]`, `["conditions.savingsRateInputPct"]`, `["conditions.heldAssets"]`, 기본 금리 표시 `result.defaultSavingsRate` | `result.assumptions.isDefaultSavingsRate` |
| 결과 요약(FR-RES-02, #49) | `S = baselineResultScenario`일 때 `display["S.afterTaxAsset"]`, `["S.gapToGoal"]`, `["targetMonth"]`. 상단 배지(#224)는 `display["S.gapBadge"]`(`달성 · 목표 +150만 원`/`미달 · 목표 −20만 원`, "약" 없음, 이 화면 응답에만 있고 planContext에는 없다), 문구표 `scenario.<S>` | 그 시나리오 `isAchieved` |
| 3구간(FR-RES-03, #50) | `["GOOD","AVG","BAD"]` 고정 순서로 돌며 `result.scenarios`에 있는 것만 그린다. 칸마다 `display["<S>.afterTaxAsset"]`, `["<S>.gapToGoal"]`, 도달 시점은 `isAchieved`면 `display["<S>.goalReachMonth"]`, 아니면 문구표 `result.notReachedInPeriod`. 아래 고지 한 줄은 `result.scenarioNotice`(D-029) | 시나리오 수(1이면 ETF 0%), `isAchieved` |
| 그래프(FR-RES-04, #50) | 목표선 라벨 `display["goalAmount"]`, 가로축 양 끝 `display["calcStartMonth"]`·`display["targetMonth"]`, 시나리오 최종값 라벨 `display["S.afterTaxAsset"]`. 세로축 눈금 숫자·툴팁·점별 월 라벨은 없다(R-5) | `monthlyPoints[].afterTaxAsset`(점 위치), `result.goalAmount`(목표선 높이) |
| 초과 안내(FR-RES-01, #49) | **문구표 `result.alreadyExceeded`**(결과 화면용) 안에 `display["goalAmount"]`, `display["conditions.heldAssets"]`. 초과 금액 없음 | `view.status === "ALREADY_EXCEEDED"` → 요약·3구간·그래프·대안 숨김, F-3 안 부름(D-062) |
| 가정 보기(FR-RES-06, #49) | `display["assumptions.taxRatePct"]` + 문구표 `tax.note`(D-067), **추가(#150, 프론트 합의 대기)** `display["assumptions.etfProductName"]`·`["assumptions.etfGainTaxRatePct"]`(ETF가 있을 때만), `display["conditions.savingsRateInputPct"]`, `display["assumptions.savingsRateBaseMonth"]`·`["assumptions.sp500BaseDate"]`(없으면 줄 생략), 문구표 `excluded.<CODE>`, `scenarioMethod.<CODE>` | `result.assumptions.excludedItems`, `scenarioMethod`, `isDefaultSavingsRate` |
| 면책(FR-RES-07, #49) | 문구표 `disclaimer` | — |
| 입력 수정 채우기(FR-INP-11, #51) | 입력칸 값: `plan.*` → C12a-5-6 (d)(e). 현재 비중 읽기 전용 한 줄: `input.currentRatioLabel` + `display["conditions.savingsRatioPct"]`. 재로그인 보유 자산 안내: `input.heldAssetsRecheck`(D-035) + `display["calcStartMonth"]` | `plan.savingsRateInputPct === null`이면 금리 칸 비움 |

- 대안 영역(Should)은 이 표 밖.

#### C12a-7-1. 문구표 키 전체 목록 (#18) [v3, 신설]

이 계약에서 프론트가 쓰는 키 전부. 이름은 #18이 바꿀 수 있지만 **바꾸면 이 표와 #47~#54 본문을 함께 고친다**. 문장은 #18이 정하고 아래 "취지"만 계약이다. 왜: 키가 빠지면 프론트가 문구를 하드코딩해 CP-05를 어긴다.

| 묶음 | 키 | 쓰는 곳 | 취지 |
|---|---|---|---|
| 공통 | `common.networkError` | #13 래퍼 | 비JSON·네트워크 오류. #12-b에 같은 뜻의 키가 있으면 그것을 쓴다 |
| 로그인 | `auth.invalidCredentials` | #47 | 아이디 또는 비밀번호가 맞지 않음 |
| | `auth.loginFailed` | #47 | 로그인하지 못함(400·500) |
| | `auth.logoutFailed` | #47 | 로그아웃하지 못함 |
| 입력 | `input.saveFailed` | #48 | 저장하지 못함(400·500) |
| | `input.invalidAmountFormat` | #48 | 만원 칸 형식 오류·소수 5자리 이상 |
| | `input.currentRatioLabel` | #51 | 현재 비중 읽기 전용 줄의 라벨 |
| | `input.heldAssetsRecheck` | #51 | D-035 재로그인 보유 자산 확인 안내(`{calcStartMonth}` 자리) |
| 422 안내 틀 | `validation.REQUIRED` | #47·#48 | 값을 입력해 주세요 |
| | `validation.OUT_OF_RANGE` | #48 | `{min}`~`{max}` 사이(자리에 아래 단위 틀 결과) |
| | `validation.NOT_INTEGER` | #48 | 정수로 입력 |
| | `validation.TOO_MANY_DECIMALS` | #48 | 소수 `{scale}`자리까지 |
| 단위 틀 | `unit.manwon` | #48 | `{v}만원` |
| | `unit.won` | #48 | `{v}원`(KRW 0 → "0원", D-061) |
| | `unit.month` | #48 | `{y}년 {m}개월` |
| | `unit.pct` | #48 | `연 {v}%` |
| | `unit.char` | #48 | `{n}자` |
| 계획 | `plan.changedElsewhere` | #48·#51 | 계획이 다른 곳에서 바뀌었음. 다시 저장하면 입력한 값으로 바뀜 |
| 되돌리기 | `undo.nothingToUndo` | #51 | 이미 되돌렸음 |
| | `undo.failed` | #51 | 되돌리지 못함 |
| 결과 | `result.loadFailed` | #49 | 결과를 불러오지 못함 + 다시 시도 |
| | **`result.alreadyExceeded`** | #49 | **결과 화면** 초과 안내(FR-RES-01, `{goalAmount}`·`{heldAssets}` 자리) + 목표 수정 안내 |
| | `result.notReachedInPeriod` | #50 | 기간 안에 도달하지 않음(planContext C1a-11 ai 문장과 같은 뜻) |
| | `result.scenarioNotice` | #50 | 3구간 고지 한 줄(D-029) |
| | `result.defaultSavingsRate` | #49 | 기본 금리 표시 |
| 이름 | `goal.<CODE>` | #48·#49 | 7개(쟁점 X-2 목록, 결정됨 D-075) |
| | `scenario.GOOD`·`scenario.AVG`·`scenario.BAD` | #49·#50 | 시나리오 이름 |
| planContext 공유 | `tax.note`, `excluded.<CODE>`, `scenarioMethod.<CODE>` | #49 | ai-server와 같은 키(CP-05, D-067) |
| 면책 | **`disclaimer`** | #49, #52 | **키 하나로 확정.** 결과 화면과 대화창이 같은 키를 쓴다. #18 본문에 `chat.disclaimer`가 있으면 `disclaimer`로 바꾼다 |

- **`result.alreadyExceeded`와 `chat.alreadyExceeded`는 다른 키다.** `result.*`는 결과 화면(#49, FR-RES-01), `chat.*`는 대화창(#52, D-072 문장)이다. #18에는 지금 `chat.alreadyExceeded`만 있으므로 `result.alreadyExceeded`를 더한다(Y-18). 둘을 하나로 합치지 않는 이유: 놓이는 화면과 자리 표시(결과 화면은 금액 두 개)가 다르다.
- #12-b H-15의 키(폴링 실패, F-1·F-5 토스트, F-4 카드 실패, `achievementChange`, 조건 라벨)는 그대로 둔다.

---

### C12a-8. P-5 되돌리기 `POST /api/plan/undo`

FR-PLAN-04, D-013, D-041, D-044, D-068, C12b-7-4.

요청 `{ "expectedRevision": 13 }` → 응답 `200 { "revision": 14 }`. 왜 `revision` 하나: 프론트가 다음에 하는 일은 공유 상태 갱신과 `loadPlan()`뿐이다.

- `PlanUndoService`는 **plan 내부(`plan.application`)**. 호출자가 `plan.web`뿐이다(`modulith.md` "루트에는 다른 모듈이 쓰는 것만"). 응답·서비스 반환 모두 `undoAvailable`이 없다(Y-14).

| 순서 | 검사·동작 | 결과 |
|---|---|---|
| 0 | 토큰 | `401 UNAUTHENTICATED` |
| 1 | 본문 파싱, `expectedRevision` 정수 | `400 MALFORMED_REQUEST` |
| 2 | `plan` 읽기. 행 없음 | `409 NO_PLAN` |
| 3 | `previous_version_id IS NULL` | `409 NOTHING_TO_UNDO` |
| 4 | `plan.revision ≠ expectedRevision` | `409 PLAN_CHANGED` |
| 5 | 직전 버전 행을 `source`·`calc_start_month`까지 복사해 새 `plan_version` INSERT(D-068) → `UPDATE plan SET current=:new, previous=NULL, revision=revision+1 WHERE member_id=? AND revision=?` → 0행이면 롤백(`409 PLAN_CHANGED`) → `PlanChanged(UNDO)` | `200 {revision}` |

- 되돌리기는 1단계. 복사 INSERT 덕분에 되돌리기 전 대화 비교는 모두 `applicable=false`·`STALE_COMPARISON`이다(D-068). 시작월은 복사한 값 그대로(D-013). 서버에는 10초 제한이 없다(토스트 시간, D-041).
- 새 버전의 `calc_run(PLAN)`은 리스너 또는 P-4가 기록하며 C12a-6-6 잠금으로 1행이다.

| `code` | 프론트 동작(#51 토스트) |
|---|---|
| (성공) | `revision` 갱신 → 토스트 닫기 → `loadPlan()`(F-3은 #54) |
| `NOTHING_TO_UNDO` | 토스트 닫기 + `undo.nothingToUndo` + `loadPlan()` |
| `PLAN_CHANGED` | 토스트 닫기 + `plan.changedElsewhere` + `loadPlan()` |
| `NO_PLAN` | 토스트 닫기 + `acceptNoPlan(expectedRevision)` → 입력 화면 |
| `UNAUTHENTICATED` | 로그인 화면 |
| `INTERNAL_ERROR` / 네트워크 / `MALFORMED_REQUEST` | `undo.failed` 토스트. 되돌리기 토스트는 남은 시간 동안 그대로 |

- 토스트가 들고 있는 값은 직전 적용 응답(F-5, P-6)의 `revision`. 되돌리기 토스트는 적용 직후에만 띄운다(P-3 뒤에는 없음).
- **[D-095, #263] 상시 되돌리기**: 토스트가 사라진 뒤에도 결과 화면 '내 계획 조건' 바의 '직전 계획으로 되돌리기' 버튼으로 같은 P-5를 부른다. 버튼은 P-4·P-3 응답의 `undoAvailable`이 `true`일 때만 보이고, `expectedRevision`은 공유 상태의 `revision`이다. 응답별 동작은 위 표와 같다(토스트 닫기 대신 버튼만 다시 그림). 직전 계획은 입력 수정 저장(P-3)·적용(F-5·P-6) 모두에서 생기므로 버튼은 가장 최근 변경 하나를 되돌린다(1단계, D-068).

### C12a-9. P-6 대안·비교 적용 `POST /api/plan/apply` — Should, #68 확정

FR-PLAN-01·02, FR-ALT-04, FR-CMP-02, D-016, D-041, D-046, CP-07.

결과 화면의 비교 영역(`POST /api/compare`, #64)이나 대안 카드에서 본 조건을 확인창을 거쳐 내 계획에 적용한다. 대화 적용은 F-5뿐이고 P-6과 무관하다(확인창 없음, D-016).

요청:
```json
{ "path": "COMPARE", "expectedRevision": 13, "conditionChanges": { "monthlySavingAmount": 1500000 } }
```

응답 `200`:
```json
{ "revision": 14 }
```

| 필드 | 뜻 |
|---|---|
| `path` | `COMPARE`(비교 영역) 또는 `ALTERNATIVE`(대안 카드). 경로마다 바꿀 수 있는 조건과 개수가 다르고(`rules.yml › change-paths`, CP-07), 새 `plan_version.source`에 그대로 남는다. `CHAT`·`INPUT_EDIT`은 받지 않는다 |
| `expectedRevision` | 비교를 만든 시점에 프론트가 들고 있던 `revision`(공유 계획 상태, C12a-6-7) |
| `conditionChanges` | 바꾼 조건. camelCase 키(`goalAmount`·`monthlySavingAmount`·`savingsRatioPct`·`savingsRateInputPct`·`targetPeriodMonths`), 원 단위 정수·퍼센트. `COMPARE`만 `etfProductCode`(문자열, 적재된 종목, C12a-9-3)도 받는다. `POST /api/compare`에 보낸 객체와 같다. 값이 `null`인 키는 바꾸지 않음 |
| `revision` | 새 revision. 공유 상태 갱신과 되돌리기 토스트(P-5)가 쓴다. F-5·P-5와 같은 모양 |

- 서비스는 `PlanApplyService.apply(memberId, path, conditionChanges, expectedRevision, expectedBaseVersionId=null)`를 재사용한다. 컨트롤러는 `plan.web`이고 plan → chat 의존은 없다.
- **기준 버전 검사(`STALE_COMPARISON`)는 없다.** 비교 결과를 저장하지 않으므로(#64) 비교 뒤 계획이 바뀌었는지는 `expectedRevision`으로만 본다.

#### C12a-9-1. 서버 검사 순서

| 순서 | 검사 | 결과 |
|---|---|---|
| 0 | 토큰 | `401 UNAUTHENTICATED` |
| 1 | 본문이 객체가 아님, `expectedRevision`이 정수가 아님, `path`가 `COMPARE`·`ALTERNATIVE`가 아님, `conditionChanges`가 객체가 아님 | `400 MALFORMED_REQUEST` |
| 2 | `plan` 행 없음 | `409 NO_PLAN` |
| 3 | `ConditionValidator.validateChanges(path, …)` 한 번. 검증기는 (a) 키마다 알 수 없는 키(`NOT_CHANGEABLE`)·숫자가 아닌 값(`NOT_INTEGER`, `etfProductCode` 제외) → (b) 바꾼 조건 없음(`REQUIRED`)·경로의 개수 초과(`TOO_MANY_CHANGES`, 종목도 1개로 센다) → (c) 경로가 허용하지 않는 조건(`NOT_CHANGEABLE`)·적재되지 않은 종목(`NOT_AVAILABLE`)·범위·소수 자릿수 순으로 보고, 앞 단계에서 오류가 나면 거기서 멈춘다 | 오류에 `NOT_CHANGEABLE`이 하나라도 있으면 `422 FIELD_NOT_CHANGEABLE`, 아니면 `422 VALIDATION_FAILED`(`min`·`max`·`unit`·`scale`) |
| 4 | `plan.revision ≠ expectedRevision` | `409 PLAN_CHANGED` |
| 5 | 현재 버전에 변경 키를 합친 계획의 입력 검증 | `422 VALIDATION_FAILED` |
| 6 | 합친 계획으로 `plan_version` INSERT(`source = path`, `calc_start_month`는 현재 값, D-013) → `UPDATE plan ... WHERE member_id=? AND revision=?` → 0행이면 롤백하고 `409 PLAN_CHANGED` → `PlanChanged(APPLY)` | `200 {revision}` |

- 3의 순서 때문에 ALTERNATIVE에 `goalAmount`와 다른 조건 하나를 함께 보내면 (b)에서 멈춰 `VALIDATION_FAILED`(`TOO_MANY_CHANGES`)다. 프론트는 두 422 코드를 같게 처리하므로(C12a-9-2) 화면 동작은 같다. 이 검증기는 F-5(C12b-7-1의 6·7)와 같이 쓴다.
- 2~6은 plan의 한 트랜잭션이다. 순서와 0행 롤백은 F-5(C12b-7-1의 5~10)와 같다.
- 중복 클릭: 두 번째 요청은 4에서 `PLAN_CHANGED`. 프론트는 요청 중 버튼을 막는다.

#### C12a-9-2. 프론트 흐름

1. **버튼 활성**: 비교 결과가 있고 `applicable=true`이며, 비교한 뒤 입력값과 공유 상태의 `revision`이 바뀌지 않았을 때만. 입력을 고치면 다시 비교할 때까지 비활성.
2. **확인창**(D-016, FR-PLAN-01): 바뀌는 조건을 "지금 값 → 바꿀 값"으로 보여 준다. 값은 비교 응답의 `display` 문자열 그대로다(NFR-INT-01). 확인창이 떠 있는 동안 내 계획은 그대로다.
3. **취소**: 아무 요청도 보내지 않는다.
4. **적용하기**: P-6을 부른다. 응답별 동작은 아래 표.

| `code` | 프론트 동작(문구표 키) |
|---|---|
| (성공) | 공유 상태 `revision` ← 응답 값 → 되돌리기 토스트 10초(그 `revision`을 들고 있음, D-041) → `loadPlan()` → 비교 영역 초기화 |
| `PLAN_CHANGED` | 확인창 닫기 + `toast.applyPlanChanged` + `loadPlan()` |
| `NO_PLAN` | 확인창 닫기 + `acceptNoPlan(expectedRevision)` → 입력 화면 |
| `VALIDATION_FAILED` / `FIELD_NOT_CHANGEABLE` | 확인창 닫기 + `toast.applyNotApplicable`. 다시 비교할 때까지 버튼 비활성 |
| `INTERNAL_ERROR` / 네트워크 / `MALFORMED_REQUEST` | `toast.applyFailed`. 확인창과 버튼은 그대로(재시도 가능) |
| `UNAUTHENTICATED` | 로그인 화면 |

- 성공 뒤 F-3은 여기서 부르지 않는다. 결과 화면의 `calcRunId`가 바뀌면 #54가 한 곳에서 부른다(C12b-7-3과 같다).
- 대화창의 적용 버튼은 `revision` 구독으로 모두 비활성된다(C12b-7-4, D-069). P-6 쪽에서 따로 부를 함수는 없다.

#### C12a-9-3. 결과 화면 ETF 종목 바꾸기 (#154)

화면 흐름: 종목을 고르면 **미리보기(비교)** → 사용자가 확인하면 **적용**. 둘 다 `COMPARE` 경로다(`rules.yml`).

- **미리보기**: 조건 변경 비교 `POST /api/compare`의 `conditionChanges`에 `"etfProductCode": "069500"`을 넣는다. 다른 조건과 함께 바꿀 수 있다. `current`는 계획의 종목, `changed`는 바꾼 종목의 시계열·세율로 계산한다(`current.assumptions.etfProductCode`·`changed.assumptions.etfProductCode`). 내 계획과 calc_run은 바뀌지 않는다. `changedKeys`에 `etfProductCode`가 들어간다.
- **적용**: `PlanApplyService.apply(memberId, COMPARE, {"etfProductCode": …}, expectedRevision, null)`이 새 plan_version에 종목을 저장하고 `PlanChanged`를 발행한다. 되돌리기는 직전 종목으로 돌아간다. 화면은 P-6 `POST /api/plan/apply`를 `{"path": "COMPARE", "expectedRevision": …, "conditionChanges": {"etfProductCode": "069500"}}`로 부른다. 확인창과 응답별 동작은 C12a-9-2와 같다.
- **고를 수 있는 종목**: 데이터를 적재한 종목만이다(`GET /api/etf-products`와 같은 목록). 아니거나 문자열이 아니면 `422 VALIDATION_FAILED`, `errors[0] = {"field": "etfProductCode", "reason": "NOT_AVAILABLE"}`이고 아무것도 계산·저장하지 않는다.
- **대화(CHAT)·대안(ALTERNATIVE)**: 아직 종목을 바꿀 수 없다(`NOT_CHANGEABLE`). 대화는 D-040 개정(#160) 뒤에 연다.

### C12a-10. 오류 공통 규칙과 코드 표

**기계 판단은 HTTP 상태가 아니라 `code`로만 한다.** 형식은 internal-calc C3-1, 공통 규칙은 #12-b C12b-9(Spring 기본 예외 매핑, 500 전역 `INTERNAL_ERROR`, 모듈 예외는 `ErrorResponseException` 상속·`config` 상속 금지, `title`은 임시이며 프론트는 쓰지 않음).

| HTTP | `code` | 엔드포인트 | 언제 | `errors` |
|---|---|---|---|---|
| 400 | `MALFORMED_REQUEST` | P-1~P-6 | 파싱 실패, 타입 불일치(P-1은 파싱 실패·비객체만), 알 수 없는 `goalType` | `[]` |
| 401 | `UNAUTHENTICATED` | P-2~P-6 | 쿠키 없음·서명 불일치·만료 | `[]` |
| 401 | `INVALID_CREDENTIALS` | P-1 | 아이디 없음·비밀번호 틀림 | `[]` |
| 404 | `NOT_FOUND` | 없는 경로 | | `[]` |
| 405 / 415 | `METHOD_NOT_ALLOWED` / `UNSUPPORTED_MEDIA_TYPE` | 전부 | 프론트는 쓰지 않음 | `[]` |
| 409 | `NO_PLAN` | P-4, P-5, P-6 | 저장된 계획 없음 | `[]` |
| 409 | `PLAN_CHANGED` | P-3, P-5, P-6 | `expectedRevision` 불일치, 쓰기 0행 | `[]` |
| 409 | `NOTHING_TO_UNDO` | P-5 | `previous_version_id` 없음 | `[]` |
| 422 | `VALIDATION_FAILED` | P-1, P-3, P-6 | 필드 검증 실패 | 1개 이상 |
| 422 | `FIELD_NOT_CHANGEABLE` | P-6 | 경로가 허용하지 않는 키, 알 수 없는 키 | 1개 이상 |
| 500 | `INTERNAL_ERROR` | 전부 | 예상하지 못한 오류, 결과 계산 실패, 대화 삭제 실패. **P-3의 500은 "저장되지 않았다"** | `[]` |

- `CALC_FAILED`는 이 계약에 나오지 않는다(`/internal/calc` 컨트롤러 한정).
- `errors[]`: `field`, `reason`(`REQUIRED`·`OUT_OF_RANGE`·`NOT_INTEGER`·`TOO_MANY_DECIMALS`·`NOT_CHANGEABLE`·`TOO_MANY_CHANGES`(경로의 개수 초과: ALTERNATIVE 1개, COMPARE·CHAT 6개. `field: conditionChanges`)·`NOT_AVAILABLE`(고를 수 없는 ETF 종목, #154)), `min`·`max`·`unit`(`KRW`·`MONTH`·`PCT`·`CHAR`), `scale`. `minDisplay`·`maxDisplay` 없음.
- 새 코드 `INVALID_CREDENTIALS`(auth), `NOTHING_TO_UNDO`(plan)는 모듈 예외가 `ErrorResponseException`으로 `code`를 직접 담는다. 전역 advice는 고치지 않는다.

예시(나머지 코드도 같은 모양):
```json
HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{ "type": "about:blank", "title": "계획이 없어요", "status": 409,
  "instance": "/api/plan", "code": "NO_PLAN", "errors": [] }
```
```json
HTTP/1.1 401 Unauthorized
Content-Type: application/problem+json

{ "type": "about:blank", "title": "아이디 또는 비밀번호가 맞지 않아요", "status": 401,
  "instance": "/api/auth/login", "code": "INVALID_CREDENTIALS", "errors": [] }
```

---

### C12a-11. 모듈 의존과 호출 순서

#### C12a-11-1. 의존 표와 시그니처

| 모듈 | 의존(변경 없음) | 이유 |
|---|---|---|
| `auth` | `{}` | 로그인·로그아웃은 `member`만 읽고 이벤트만 발행 |
| `plan` | `{}` | P-5·P-6 컨트롤러는 `plan.web` |
| `calc` | `{plan, refdata}` | P-3·P-4 컨트롤러(`calc.web`)가 plan 공개 저장·조회와 자기 결과 조회를 부른다 |
| `chat` | `{calc, rag, plan, auth}` | #12-b |

```
calc.web(P-3, P-4) ──▶ calc.application(입력 저장 유스케이스 @Transactional, 결과 조회 #29, ResultDisplayBuilder #28)
                                └──▶ plan(PlanSaveService: REQUIRED 합류, 현재 계획 조회)   [calc → plan, 기존]
plan.web(P-5, P-6) ──▶ plan.application(PlanUndoService, PlanApplyService)
auth.web(P-1, P-2) ──▶ auth.application ──publish──▶ MemberLoggedIn/Out ──(동기)──▶ chat
```

지켜야 할 것(C12b-10-2 (a)~(d)에 더해):
- (e) plan 공개 저장 서비스의 인자·반환에 calc·chat·config 타입이 없다.
- (f) `calc.web`은 plan 루트 공개 API만 쓴다. `GoalType`은 plan 루트 enum.
- (g) auth는 plan을 모른다.
- (h) `/internal/calc`의 422 `minDisplay`·`maxDisplay`와 500 `CALC_FAILED`는 **그 컨트롤러 클래스 안의 `@ExceptionHandler`에만** 둔다. 왜: P-3도 `calc.web`이라 패키지 단위 advice면 P-3 422에 `minDisplay`가 붙는다. 테스트 "P-3 422에 `minDisplay` 없음".
- (i) P-3·P-4 응답은 #29가 돌려준 **한 스냅샷**으로 조립한다.
- (j) 같은 계획 버전의 PLAN 기록 확인·기록은 회원 잠금 아래 한 메서드(#29)로만, 조건 4개(C12a-6-6).
- **(k) [v3]** `monthlyPoints`의 점 규칙은 엔진(#26·#27)이 지킨다. 화면 View는 복사만 한다.

```java
// plan 루트 (#19). chat·calc·config 타입 없음.
public interface PlanSaveService {
    /**
     * 입력 저장(INPUT). 호출자 트랜잭션에 합류한다(Propagation.REQUIRED).
     * 검증(INPUT_EDIT) → revision 검사 → plan_version INSERT
     * → plan 네이티브 INSERT ... ON CONFLICT DO NOTHING / UPDATE ... WHERE revision=? (0행이면 PlanChangedException)
     *   네이티브 쓰기는 @Modifying(flushAutomatically = true, clearAutomatically = true)
     * → PlanChanged(INPUT). 시작월·비중은 현재 버전에서 복사(없으면 요청 월·NULL).
     * @param expectedRevision null = 계획이 없다고 믿음
     */
    PlanSaveResult save(long memberId, PlanInput input, Long expectedRevision);
}
public record PlanInput(String goalType, String goalCustomName, Map<String, JsonNode> fields) {}
public record PlanSaveResult(long planVersionId, long revision) {}
public enum GoalType { HOUSE, CAR, WEDDING, EDUCATION, TRAVEL, EMERGENCY_FUND, CUSTOM }  // [D-075]

// plan.application (#33). 공개 API 아님.
interface PlanUndoService { PlanUndoResult undo(long memberId, long expectedRevision); }
record PlanUndoResult(long planVersionId, long revision) {}
```
- 결과 조회 서비스 반환(#29, 이름 재량)은 "현재 계획 스냅샷(`revision`, `currentVersionId`, 입력값, `baselineScenario`) + `calcRunId` + 결과 + display". planContext QUESTION(#78 `findForCurrentPlan`)도 같은 서비스를 쓴다.

#### C12a-11-2. 호출 순서

**앱 시작·로그인 → 진입 (FR-PLAN-06)**
```
브라우저(#47 가드) ─loadPlan() → GET /api/plan─▶ 401 → 로그인 화면
브라우저 ─POST /api/auth/login─▶ auth.web (@SkipRenewal, 리졸버는 이 경로에서 토큰을 보지 않음)
  auth: 검증 → 해시 비교 → publish MemberLoggedIn ─(동기)─▶ chat: DELETE chat_message
  auth: JWT 발급 → Set-Cookie moeun_at → 200 {}
브라우저(#51) ─loadPlan() → GET /api/plan─▶ calc.web
  ├ 409 NO_PLAN → acceptNoPlan(null) → 입력 화면
  └ 200 PlanView → revision·view 갱신(settledStatus 확정) → 결과 화면 → (#54) view.calcRunId 바뀜 + CALCULATED → F-3
```

**입력 저장 (FR-INP-10)**
```
브라우저(#48, 버튼 비활성) ─PUT /api/plan {expectedRevision, ...}─▶ calc.web
  calc.application 유스케이스 BEGIN
    plan.PlanSaveService.save (합류: 검증 → revision → INSERT/UPDATE(컨텍스트 비움), 0행이면 예외 → PlanChanged(INPUT))
    #29 결과 조회 (회원 잠금 → 이 버전 PLAN 기록 없음 → 계산·기록) → 스냅샷 + 결과 + display
  COMMIT  (계산 실패면 ROLLBACK → 500, 저장 안 됨)
  ◀─200 PlanView
[커밋 뒤, @ApplicationModuleListener] calc 리스너: 회원 잠금 → 이 버전 PLAN 기록 있음 → 건너뜀
브라우저: revision·view 갱신 → (#53) 버튼 비활성(D-069), (#52) settledStatus로 초과 재판단(D-072), (#54) F-3
```

**되돌리기 (FR-PLAN-04)**
```
브라우저(토스트) ─POST /api/plan/undo {expectedRevision}─▶ plan.web
  plan: 읽기 → NO_PLAN / NOTHING_TO_UNDO / PLAN_CHANGED → 복사 INSERT → 조건부 UPDATE → PlanChanged(UNDO)
  ◀─200 {revision}
브라우저: revision 갱신(버튼 비활성, 토스트 닫힘, settledStatus는 직전 값 유지) → loadPlan()(새 요청)
  → view 갱신 → settledStatus 확정 → 초과 재판단 → (#54) F-3
[커밋 뒤] calc 리스너와 P-4 중 먼저 잠금을 잡은 쪽만 기록 (calcRunId 하나)
```

**로그아웃**
```
브라우저 ─POST /api/auth/logout─▶ auth.web (@SkipRenewal)
  publish MemberLoggedOut ─(동기)─▶ chat: DELETE chat_message → Set-Cookie moeun_at=; Max-Age=0 → 200 {}
브라우저: 두 칸 null(폴링 중지) → 로그인 화면
```

---

### C12a-12. 목(mock) JSON 목록 (`frontend/src/mocks/auth/`, `frontend/src/mocks/plan/`)

**형식·선택 방식은 #12-b C12b-12 그대로**(파일 = `{responses: [...]}`, 요청 키 = 메서드 + 경로, 키마다 기본 파일 하나, 그 밖은 `useMock(파일명)`, 배열 끝을 넘으면 마지막 반복, `status`·`headers` 반영). 새 라이브러리 없음.

- `body`는 줄임표 없이 채운다. REST Docs 스니펫(#16·#30·#31·#33)과 같은 값.
- 기준 계획(A-1 계획): 목표 2,000만원(`HOUSE`), 보유 500만원, 월 30만원, 36개월, 금리 미입력(기본 3.12%), 비중 미입력(5:5), 시작월 2026-11. `result`·`display`는 plan-context 부록 A-1과 글자까지 같다(`calcRunId` 540).
- **[v3] `monthlyPoints` 채우는 규칙(목 전용)**: 시나리오마다 `targetPeriodMonths + 1`점(A-1은 37점), `month` = 2026-11 … 2029-11(k = 0 … 36), `afterTaxAsset = heldAssets + monthlySavingAmount × k + round(그 시나리오 afterTaxGain × k / 36)`. 첫 점 = 보유 자산, 마지막 점 = 시나리오 값(C12a-6-2). `principal` 키 없음.
- 헤더 `Set-Cookie`는 브라우저가 `Response`에 넣지 않는 금지 헤더라 무시된다. 목에서는 설명용이다.
- **기본 파일은 시퀀스**다. 이것 하나로 "앱 시작 → 로그인 → 입력 → 결과 → 대화 적용 → 되돌리기 → 입력 수정"이 목만으로 돈다. #12-b 목과 id가 이어진다.
- **[v3] 시퀀스 파일은 요청 순서로 소비된다.** `GET /api/plan`은 `loadPlan()` single-flight를 거치므로 개발 모드 이중 effect에도 한 번만 소비된다. 이 점을 `frontend/src/mocks/README`에 적는다(#12).

| 순서 | 사용자 동작 | 요청 | 목 응답(기본 파일) |
|---|---|---|---|
| 1 | 앱 시작 | GET /api/plan | `get-plan.seq-full-flow` 1번: `401 UNAUTHENTICATED` → 로그인 화면 |
| 2 | 로그인 | POST /api/auth/login | `post-login.200` |
| 3 | 진입 분기 | GET /api/plan | 2번: `409 NO_PLAN` → 입력 화면 |
| 4 | 결과 보기 | PUT /api/plan | `put-plan.seq-full-flow` 1번: `rev 12 / calcRunId 540` |
| 5 | 대화 적용 | POST /api/chat/messages/1031/apply | `post-apply.200` `{revision: 13}`(#12-b) |
| 6 | 재조회 | GET /api/plan | 3번: `rev 13 / 553`(월 50만원 적용, 3구간 모두 달성) |
| 7 | 되돌리기 | POST /api/plan/undo | `post-undo.200` `{revision: 14}` |
| 8 | 재조회 | GET /api/plan | 4번(마지막, 반복): `rev 14 / 554`(A-1 숫자) |
| 9 | 입력 수정 저장 | PUT /api/plan | 2번(마지막, 반복): `rev 15 / 555`(A-1 계획에서 목표 기간 48개월, 49점) |

| 파일 | 엔드포인트 | 응답 | 내용 |
|---|---|---|---|
| `auth/post-login.200.json` (기본) | P-1 | 200 | `{}` |
| `auth/post-login.401-invalid.json` | P-1 | 401 | `INVALID_CREDENTIALS` |
| `auth/post-login.422.json` | P-1 | 422 | `password` REQUIRED |
| `auth/post-login.500.json` | P-1 | 500 | `INTERNAL_ERROR` |
| `auth/post-logout.200.json` (기본) | P-2 | 200 | `{}` |
| `auth/post-logout.401.json` | P-2 | 401 | `UNAUTHENTICATED` |
| `plan/get-plan.seq-full-flow.json` (기본) | P-4 | 401 → 409 → 200 → 200 | 위 순서 1·3·6·8 |
| **`plan/get-plan.seq-exceeded-toggle.json`** [v3] | P-4 | 200 → 200 → 200 | `useMock` 전용. `rev 12 CALCULATED / 540`(A-1) → `rev 13 ALREADY_EXCEEDED / 542`(`get-plan.200-exceeded` 본문에서 `revision`·`calcRunId`만 바꿈) → `rev 14 CALCULATED / 554`. D-072 "미달 → 초과 → 풀림"과 #54 "초과면 F-3 안 부름" 시험용(F-5 `{13}`·P-5 `{14}` 목과 함께) |
| `plan/get-plan.200.json` | P-4 | 200 | A-1 계획, `rev 12`, `540`, 3구간 모두 미달 |
| `plan/get-plan.200-etf0.json` | P-4 | 200 | plan-context A-2(`541`), AVG 1개, `plan.savingsRatioPct: 100` |
| `plan/get-plan.200-exceeded.json` | P-4 | 200 | plan-context A-3(`542`), `ALREADY_EXCEEDED`, `scenarios: []`, `baselineResultScenario: null` |
| `plan/get-plan.200-after-apply.json` | P-4 | 200 | `rev 13`, `553` |
| `plan/get-plan.200-after-undo.json` | P-4 | 200 | `rev 14`, `554` |
| `plan/get-plan.200-ratio83.json` | P-4 | 200 | **[v4]** `plan.savingsRatioPct: 83`, `display["conditions.savingsRatioPct"]` "적금 83 : ETF 17". 나머지는 기본 목(A-1) 복사. `useMock` 전용 — 토글 ON 복원·되보내기 시험용 |
| `plan/get-plan.409-no-plan.json` | P-4 | 409 | `NO_PLAN` |
| `plan/get-plan.401.json` | P-4 | 401 | `UNAUTHENTICATED` |
| `plan/get-plan.500.json` | P-4 | 500 | `INTERNAL_ERROR` |
| `plan/put-plan.seq-full-flow.json` (기본) | P-3 | 200 → 200 | 위 순서 4·9 |
| `plan/put-plan.200.json` | P-3 | 200 | `get-plan.200`과 같은 본문 |
| `plan/put-plan.200-exceeded.json` | P-3 | 200 | `get-plan.200-exceeded`와 같은 본문 |
| `plan/put-plan.422-range.json` | P-3 | 422 | `OUT_OF_RANGE`(`max 10000000`) + `REQUIRED` |
| `plan/put-plan.422-decimals.json` | P-3 | 422 | `NOT_INTEGER` + `TOO_MANY_DECIMALS` `scale 2` |
| `plan/put-plan.422-custom-name.json` | P-3 | 422 | `goalCustomName` `OUT_OF_RANGE`(`min 1, max 100, unit CHAR`) [D-074] |
| `plan/put-plan.409-plan-changed.json` | P-3 | 409 | `PLAN_CHANGED` |
| `plan/put-plan.400.json` | P-3 | 400 | `MALFORMED_REQUEST` |
| `plan/put-plan.500.json` | P-3 | 500 | `INTERNAL_ERROR`(저장 안 됨) |
| `plan/post-undo.200.json` (기본) | P-5 | 200 | `{revision: 14}` |
| `plan/post-undo.409-nothing.json` | P-5 | 409 | `NOTHING_TO_UNDO` |
| `plan/post-undo.409-plan-changed.json` | P-5 | 409 | `PLAN_CHANGED` |
| `plan/post-undo.409-no-plan.json` | P-5 | 409 | `NO_PLAN` |
| `plan/post-undo.500.json` | P-5 | 500 | `INTERNAL_ERROR` |
| `plan/post-plan-apply.200.json` (기본) | P-6 | 200 | `{revision: 14}` |
| `plan/post-plan-apply.409-plan-changed.json` | P-6 | 409 | `PLAN_CHANGED` |
| `plan/post-plan-apply.409-no-plan.json` | P-6 | 409 | `NO_PLAN` |
| `plan/post-plan-apply.422-validation.json` | P-6 | 422 | `monthlySavingAmount` `OUT_OF_RANGE`(`min 10000, max 10000000, unit KRW`) |
| `plan/post-plan-apply.422-field-not-changeable.json` | P-6 | 422 | ALTERNATIVE에 `goalAmount` → `NOT_CHANGEABLE` |
| `plan/post-plan-apply.500.json` | P-6 | 500 | `INTERNAL_ERROR` |

- **[v4] 총 31개**(auth 6, plan 25). P-6 목은 없다(#68). 공통 401은 대화 절의 `chat/common.401-unauthenticated.json`을 재사용한다.
- **[개수 정정]** 최종본 본문은 "29개(plan 23)"로 적었지만 위 표는 30행이었다. `put-plan.422-custom-name`(D-074)이 개수 문장에 반영되지 않은 것으로 보여 표를 기준으로 했고, v4에서 `plan/get-plan.200-ratio83.json`을 더해 31개다. 대화 목 35개와 합쳐 66개다.
- 9번 뒤의 GET은 마지막 응답(`rev 14`)을 반복한다. C12a-6-7 규칙 1로 무시되어 화면은 `rev 15`를 유지한다.
- Issue #12 수용 기준 "정상, 422, 401, 409": P-1(200·422·401), P-3(200·422·409), P-4(200·409·401), P-5(200·409). 400·500도 있다.


---

## 3. 대화 절 (#12-b, 최종본 `frontend-api-chat.md`)

> **이 절의 머리말 (원문)** 최종본(2026-11-16). 쟁점 결정 D-068, D-069를 반영했다. 두 결정은 Claude 판단(진혁 위임)으로 **임시**이며, 바뀌면 `[D-068]`·`[D-069]` 표시 줄만 고친다. 본문의 "쟁점 X-n이 A/B로 정해지면 …" 문장은 기록용이고 결정이 우선한다.
>
> | 대화 절 쟁점 | 내용 | 결정 |
> |---|---|---|
> | X-1 (Q-056) | 되돌리기 방식 | D-068: 안 B, 직전 버전을 복사해 새 `plan_version` INSERT |
> | X-2 (Q-057) | 이전 비교 버튼 비활성 범위 | D-069: 안 A, 계획이 바뀐 모든 순간 |
>
> **번호는 절마다 따로 센다.** 이 표의 쟁점 X-1·X-2는 2절(계획·인증 절)의 쟁점 X-1(목표명 길이)·X-2(`GoalType`)·X-3(데모 방식)과 다른 것이다. 또 이 절의 "코드 차이 X-n"(C12b-15)은 쟁점 번호와 겹치므로 항상 "코드 차이 X-n"과 "쟁점 X-n"으로 구분해 읽는다. 질문 번호 Q-051~055는 C12b-16 원문에 있다.
>
> **표기 범례 (원문)**
> - **[합의]**: 양쪽이 수용했거나, 한쪽 수정 제안에 다른 쪽 이견이 없어 반영한 것.
> - **[쟁점 X-n]**·**⟵ 쟁점 X-n**: 원래 사람이 정하는 항목이었고 본문에는 기획 추천안(양쪽 재검토도 같은 안)을 임시로 적었다. 위 표대로 결정됐다.
> - **[v3]**: v2에서 바뀐 줄.
> - 항목 ID(C12b-x, R-x, H-x)는 v1·v2와 같다.

> **최종본 보충 (같은 날 결정, 본문보다 우선)**
> - **D-063** 초과 계획(D-062): 대화창은 열어 두고 첫 자리에 문구표 `chat.alreadyExceeded` 문장 한 줄을 프론트가 표시한다. F-3·LLM 호출 없음, `chat_message`·F-2 응답에 넣지 않는다. 설명 다시 요청 버튼(FR-AI-05)은 보이지 않는다. → #52·#54
> - **D-066** 대화창에 고지 한 줄(문구표 `disclaimer`(옛 이름 `chat.disclaimer`, 결과 화면 면책과 같은 키 하나, C12a-7-1): D-029 과거 데이터 고지 + "AI 설명은 참고용이며 계산 결과가 기준이에요")을 상시 표시한다. 서버 응답과 무관. → #52, #18
> - **D-064** 질문 예시 버튼(FR-AI-08, Should)은 "조건 고르기" 문구이며 F-1로 그 문장을 그대로 보낸다(응답은 `CLARIFY`). API 변경 없음.
> - **D-070** 목 제거 #55는 `team:common`, `features/chat/**` 포함(H-11e 확인 완료).
> - **D-072** 초과 고정 문장은 계획 `revision`이 바뀔 때마다(D-069 구독 지점) 현재 초과 여부로 다시 판단: 초과면 대화창 맨 위 표시·설명 다시 요청 버튼 숨김, 풀리면 지우고 버튼 표시, F-3은 #54 규칙대로. → #52·#53·#54
> - **D-071** 초과 계획에서 대화 조건 변경은 `ALREADY_EXCEEDED` 고정 문장(RECALC_RESULT 아님) → 비교 카드·적용 버튼 없음. → #53

### C12b-0. 이 계약의 원칙

`internal-calc.md` §0을 그대로 따른다.

1. **프론트는 Spring만 부른다.** ai-server는 브라우저에 노출하지 않는다(NFR-INT-03). 사용자 토큰은 ai-server로 가지 않는다(D-030).
2. **회원은 JWT subject로만 정한다.** 본문·쿼리에 `memberId`가 없다. 남의 리소스는 `404 NOT_FOUND`(존재 여부 숨김).
3. **숫자는 Spring만 만든다.** 대화창 비교 카드 문자열도 Spring `display`를 그대로 쓴다(C12b-6).
4. **적용은 값이 아니라 `messageId`로 한다**(D-033).
5. **JSON은 camelCase**, 금액 원 정수, 기간 개월 정수, 퍼센트 숫자, 시각 ISO-8601(오프셋 포함).
6. **null은 키를 빼지 않는다. 받는 쪽은 모르는 키를 무시한다.**
7. **오류 본문은 하나의 형식**: `ProblemDetail` + `code` + `errors[]`(C3-1). 기계 판단은 `code`로만. **모든 오류 응답에 `code`가 있다**(Spring 기본 예외 포함, C12b-9).
8. **새 라이브러리 없음.** 프론트 목은 JSON 파일 + 기존 `fetch` 래퍼 분기(C12b-12).
9. **`chat_message.condition_changes`는 camelCase로 저장한다**(C12b-14). 저장 뒤 모든 읽기는 변환하지 않는다.

---

### C12b-1. 범위와 엔드포인트 목록 [합의]

| ID | 메서드·경로 | 하는 일 | 성공 | 담당 모듈(팀) | 토큰 연장 |
|---|---|---|---|---|---|
| F-1 | `POST /api/chat/messages` | 질문 보내기 | `202` | chat(B) | 예 |
| F-2 | `GET /api/chat/messages?afterId={id}` | 메시지 폴링·대화 복원 | `200` | chat(B) | **아니오**(D-052, `@SkipRenewal`) |
| F-3 | `POST /api/chat/explanations` | 계산 결과 설명 요청 | `202` | chat(B) | 예 |
| F-4 | `GET /api/chat/messages/{messageId}/comparison` | 대화 비교 결과 조회 | `200` | chat(B), 계산은 calc 공개 API(A) | 예 |
| F-5 | `POST /api/chat/messages/{messageId}/apply` | 대화 비교 결과를 내 계획에 적용 | `200` | chat(B) 컨트롤러 → plan(A) 공개 서비스 | 예 |
| — | (엔드포인트 없음) 대화 기록 삭제 | 로그인·로그아웃 때 이전 대화 삭제(D-051) | — | auth(A)가 이벤트 발행, chat(B)이 동기 수신(C12b-8) | — |

- F-5는 `b-team-contracts` A-1의 대화 쪽을 대신한다. 대안·비교 적용(Should)은 plan 컨트롤러의 조건값 적용 API로 남는다(#33, #12-a).
- 대화 기록 삭제용 프론트 엔드포인트는 두지 않는다. 왜: D-051은 로그인·로그아웃 시점만 정했다.
- 모든 경로는 JWT 쿠키 인증. `/internal/**`과 필터 체인이 다르다(internal-calc C2-1).

### C12b-2. 인증·공통 헤더·공통 응답

| 항목 | 값 |
|---|---|
| 인증 | HttpOnly·Secure 쿠키의 JWT(D-052, ADR-002). **`SameSite=Lax` 이상 필수**. 왜: `SecurityConfig`가 CSRF를 끄므로 교차 사이트 POST(F-1 비용, F-5 계획 변경)를 막는 장치가 `SameSite`뿐이다. 쿠키 이름은 #12-a(#16) |
| **[v3] 배포 전제** | **프론트와 Spring은 같은 사이트**(한 도메인 또는 리버스 프록시)에서 서빙한다. 개발 서버는 Vite 프록시로 `/api`를 Spring에 넘긴다. 왜: `Lax` 쿠키는 교차 사이트 `fetch`(GET 포함)에 실리지 않아, 다른 사이트면 F-2 폴링이 전부 401이 된다. #13에 적는다 |
| 요청 헤더 | 본문이 있으면 `Content-Type: application/json`. 프론트는 `fetch(..., { credentials: "include" })` |
| 성공 / 오류 `Content-Type` | `application/json` / `application/problem+json` |
| 토큰 연장 | 사용자 조작 요청은 `TokenRenewalFilter`가 새 쿠키를 내린다. **F-2만 `@SkipRenewal`**(D-052) |
| 만료·없는 토큰 | 모든 엔드포인트 `401 UNAUTHENTICATED`. 프론트는 폴링을 멈추고 로그인 화면으로 |

- chat 컨트롤러는 `memberId`를 **Spring Security 타입**(`@AuthenticationPrincipal Jwt` 또는 `Authentication.getName()`)으로만 얻는다. auth가 만든 리졸버를 쓰지 않는다(C12b-10-2 규칙 b).
- `@SkipRenewal`·`TokenRenewalFilter`·`ChangePath`·`ConditionValidator`는 **이름만 정해진 상태**(`moved-to-code.md`)다. 코드는 해당 Issue가 만든다.
- **[v3] `@SkipRenewal` 구현 메모(#34, 계약 변경 아님)**: 핸들러 어노테이션이라 서블릿 필터는 핸들러가 정해지기 전에 돌아 바로 볼 수 없다. `HandlerInterceptor.preHandle`(본문을 쓰기 전에 `Set-Cookie`)로 하거나, 필터에서 `HandlerMappingIntrospector`로 핸들러를 찾는다(#34 재량). 어느 쪽이든 어노테이션은 auth 루트 타입이다.
- 폴링이 401을 받으면 사용자 조작 없이 만료된 것이므로 다른 요청을 다시 보내지 않고 로그인 화면으로 간다(FR-AUTH-02 예외).
- **[v3] 폴링 실패 규칙**: F-2가 **401 외의 실패**(네트워크 오류, 4xx, 5xx)를 **연속 3회** 받으면 폴링을 멈추고 대화창에 실패 안내를 한 번 보인다(문구표). 사용자가 F-1·F-3을 다시 보내면 재개한다. 한 번이라도 200이면 연속 횟수는 0으로. 왜: 서버가 멈춰 60초 정리(C12b-11-3)를 못 하는 경우와, `400 MALFORMED_REQUEST`(`afterId` 오류)가 1초마다 영원히 반복되는 경우를 모두 끝낸다.

### C12b-3. 메시지 모양 (F-2가 돌려줌)

```json
{
  "id": 1031,
  "role": "ASSISTANT",
  "messageType": "RECALC_RESULT",
  "status": "COMPLETED",
  "content": "월 저축액을 약 50만원으로 바꾸면 평균일 때 약 2,501만원으로 목표를 달성해요. 지금보다 약 789만원 증가해요.",
  "citations": [],
  "createdAt": "2026-11-16T10:15:31+09:00"
}
```

| 필드 | 타입 | 뜻 |
|---|---|---|
| `id` | integer | `chat_message.id`(BIGSERIAL) |
| `role` | `USER` \| `ASSISTANT` | |
| `messageType` | `QUESTION` \| `ANSWER` \| `EXPLANATION` \| `RECALC_RESULT` \| `CLARIFY` \| `OUT_OF_SCOPE` \| `FAILURE` \| `null` | `status=PENDING`이면 `null` |
| `status` | `PENDING` \| `COMPLETED` \| `FAILED` | USER는 항상 `COMPLETED` |
| `content` | string \| null | `PENDING`이면 `null`. `FAILED`면 문구표 실패 안내(FR-AI-04) |
| `citations` | array | 출처. **배열 순서가 순위**다. 없으면 `[]` |
| `citations[].title` | string | 문서 제목(D-028: 링크에는 상품명 허용) |
| `citations[].sourceUrl` | string | |
| `citations[].orgName` | string | 운용사·기관명 |
| `createdAt` | string | ISO-8601 |

- **`conditionChanges`·`citations[].rank`는 응답에 넣지 않는다.** 왜: 카드는 F-4의 `conditions`·`changedKeys`를 쓰고, 순위는 배열 순서로 충분하다. ai-server 키 표기 문제(C12b-14)가 프론트에 새지 않는다.
- **PENDING 행의 DB 값(구현 고정)**: 질문 답변 대기(F-1이 만든 ASSISTANT PENDING)는 `message_type='ANSWER'`, 설명 대기(F-3)는 `message_type='EXPLANATION'`. 왜: `ANSWER_PENDING` 판정(C12b-4)과 `answerPending`(C12b-5)을 같은 조건 `status='PENDING' AND message_type='ANSWER'` 하나로 만든다. 화면에는 PENDING의 `messageType`을 `null`로 가린다. 프론트가 "질문 대기인가"를 알아야 할 때는 F-2의 `answerPending`을 본다.
- `FAILED`는 항상 `messageType=FAILURE`. 실패 이유 코드는 로그에만.
- `calcRunId`·`retryOfMessageId`·`baselineScenario`는 내보내지 않는다.
- **id와 커밋 순서**: id는 할당 순서이지 커밋 순서가 아니다. 같은 회원의 F-1과 F-3이 몇 ms 안에 겹치면 작은 id가 늦게 커밋되어 `id > afterId` 폴링이 놓칠 수 있다. 데모에서 드물어 서버 잠금은 두지 않고 C12b-5의 복원 안전장치로 막는다.

### C12b-4. F-1 질문 보내기 `POST /api/chat/messages`

요청:
```json
{ "text": "월 50만원으로 하면 어때요?" }
```

응답 `202 Accepted`:
```json
{ "userMessageId": 1030, "assistantMessageId": 1031 }
```

- `userMessageId`는 프론트가 사용자 말풍선을 바로 그리고 폴링 upsert와 겹치지 않게 하는 키다. `status`는 넣지 않는다(항상 같은 값).

| 필드 | 규칙 |
|---|---|
| `text` | 필수. **앞뒤 공백을 지운(trim) 문자열**의 길이가 1 이상 2000 이하. 길이는 **UTF-16 코드 단위**(Java `String.length()` = JS `string.length`). **저장·ai-server 전송도 trim한 문자열**로 한다 |

- 왜 UTF-16: 프론트 글자 수 표시(`maxLength=2000`)와 서버 기준이 같다. ai-server `max_length=2000`(코드포인트)은 이보다 느슨하므로 Spring을 통과한 값은 ai-server에서 걸리지 않는다. trim한 값을 보내야 이 관계가 성립한다.
- trim 뒤 길이 검사는 `@Size`가 못 보므로 컨트롤러 또는 요청 record의 compact constructor에서 한다(구현 메모).

서버 동작(한 트랜잭션):
1. 오래된 `PENDING` 정리(C12b-11-3).
2. 그 회원에게 `status='PENDING' AND message_type='ANSWER'` 행이 있으면 `409 ANSWER_PENDING`. 설명 대기는 막지 않는다(R-4).
3. USER(`QUESTION`, `COMPLETED`, trim한 `text`) + ASSISTANT(`ANSWER`, `PENDING`) 저장 → `ChatAnswerRequested{assistantMessageId, memberId}` 발행.
4. 커밋 뒤 리스너가 ai-server `POST /chat`(mode=`QUESTION`) 호출 → `COMPLETED`/`FAILED`(#41, C12b-11-2).

- 계획이 없어도 받는다(NFR-USA-01). 리스너는 `planContext: null`로 부르고 **`baselineScenario`는 `"AVG"`로 채운다**. 왜: `ChatRequest.baseline_scenario`가 필수라 비우면 ai-server 422 → `FAILED`. 계획이 있으면 `plan.baseline_scenario`.
- `history`: 같은 회원의 `COMPLETED` 메시지 최근 20개. `role`은 ai-server 형식(소문자 `user`/`assistant`)으로 Spring이 바꾼다(프론트 영향 없음).
- 초과 계획에서도 평소대로 받는다. 대화창 표시는 Q-051이었고 3절 머리말 보충 D-063으로 정해졌다(프론트가 문구표 `chat.alreadyExceeded` 한 줄, F-1은 평소대로 받는다).

오류:

| HTTP | `code` | 언제 | `errors` | 프론트 동작 |
|---|---|---|---|---|
| 400 | `MALFORMED_REQUEST` | JSON 파싱 실패, 본문이 객체가 아님 | `[]` | 일반 실패 토스트(개발 오류) |
| 422 | `VALIDATION_FAILED` | trim 뒤 빈 문자열 | `[{field:"text", reason:"REQUIRED"}]` | 입력창 아래 안내 |
| 422 | `VALIDATION_FAILED` | 2000 초과 | `[{field:"text", reason:"OUT_OF_RANGE", min:1, max:2000, unit:"CHAR"}]` | 입력창 아래 안내 |
| 409 | `ANSWER_PENDING` | 질문 답변 대기 중 | `[]` | **보내기 버튼 비활성 + 폴링 재개**(다른 탭이 만든 대기일 수 있음). **입력창 글은 지우지 않는다** |
| 401 | `UNAUTHENTICATED` | 만료·없는 토큰 | `[]` | 로그인 화면 |
| 500 | `INTERNAL_ERROR` | DB 오류 등 | `[]` | "보내지 못했어요" 토스트, 입력창 글 유지 |

- "`text`가 문자열이 아님 → 400"은 두지 않는다. Jackson 기본 스칼라 변환(`123` → `"123"`)으로 받아질 수 있고 막을 가치가 낮다.
- 프론트는 `answerPending=true`인 동안 보내기 버튼을 막는다. 409는 두 탭·연타의 방어선이다. 2·3 사이 동시 요청 경쟁은 데모 범위에서 허용(막으려면 회원 행 잠금).

### C12b-5. F-2 메시지 폴링·복원 `GET /api/chat/messages?afterId={id}`

응답 `200`:
```json
{
  "messages": [
    { "id": 1030, "role": "USER", "messageType": "QUESTION", "status": "COMPLETED",
      "content": "월 50만원으로 하면 어때요?", "citations": [],
      "createdAt": "2026-11-16T10:15:30+09:00" },
    { "id": 1031, "role": "ASSISTANT", "messageType": null, "status": "PENDING",
      "content": null, "citations": [],
      "createdAt": "2026-11-16T10:15:30+09:00" }
  ],
  "hasPending": true,
  "answerPending": true
}
```

| 쿼리 | 규칙 |
|---|---|
| `afterId` 없음 | **복원**: 최근 50개를 `id` 오름차순으로(키셋 `DESC LIMIT 50` 뒤 뒤집기). 결과 화면 진입 때 1회 |
| `afterId=N` | **폴링**: `id > N` 전부를 `id` 오름차순. 정수가 아니면 `400 MALFORMED_REQUEST` |

| 응답 필드 | 뜻 | 프론트 사용 |
|---|---|---|
| `messages[]` | C12b-3 모양 | `id`로 upsert |
| `hasPending` | 그 회원에게 `PENDING`이 하나라도 있음(목록 밖 포함) | 폴링 유지/중단 |
| `answerPending` | 그 회원에게 **질문 답변 대기**(`PENDING` + DB `message_type='ANSWER'`)가 있음 | **보내기 버튼 막기**. F-1 `ANSWER_PENDING`과 같은 조건(같은 메서드) |

- 왜 `answerPending`(불리언)인가: 새로고침 뒤에도 보내기 버튼을 정할 수 있고, 프론트가 메시지 문자열을 해석하지 않는다. 서버 판정은 `EXISTS(member_id=? AND status='PENDING' AND message_type='ANSWER')` 하나다. `ix_chat_message_pending`은 회원 조건을 포함하지 않지만 PENDING 행이 몇 개뿐이라 인덱스를 바꾸지 않는다.
- **`hasPending=false` 보장**: 서버는 오래된 PENDING을 정리한 **뒤** 조회한다. 그래서 `hasPending=false`면 응답 시점에 서버의 모든 PENDING이 끝났고, 프론트가 가진 PENDING은 이미 덮어써졌어야 한다. 남아 있으면 아래 복원 안전장치를 쓴다.

**프론트 폴링 규칙**
- `afterId` = 가진 메시지 중 **가장 작은 `PENDING` id − 1**. `PENDING`이 없으면 가진 가장 큰 id.
- 받은 메시지는 `id`로 덮어쓴다(upsert). 같은 메시지를 다시 받는 것은 정상.
- 간격 **1초**. `hasPending=false`면 멈추고, F-1·F-3을 보내면 다시 시작한다.
- **복원 안전장치**: `hasPending=true`인데 가진 PENDING이 없거나, `hasPending=false`인데 가진 PENDING이 남아 있으면 `afterId` 없이 **복원을 1회** 부른다. **[v3] "1회"의 범위**: 한 번 부르면 **폴링이 멈출 때(`hasPending=false`)까지, 또는 사용자가 F-1·F-3을 보낼 때까지** 다시 부르지 않는다(무한 반복 방지). 서버 변경 없음.
- 401 외 실패 연속 3회면 중단(C12b-2), 401이면 로그인 화면.
- 다른 회원의 메시지는 나오지 않는다. `afterId`가 남의 id여도 오류 없이 자기 메시지만.
- ERD Q2-b의 `pending_ids` 파라미터는 쓰지 않는다(코드 차이 X-4).
- **구현 메모**: GET이 오래된 PENDING을 UPDATE하므로 F-2 서비스는 **읽기 전용 트랜잭션이 아니다**. 대개 0행 UPDATE라 부담은 작다.

### C12b-6. F-4 대화 비교 결과 조회 `GET /api/chat/messages/{messageId}/comparison`

응답 `200`:
```json
{
  "messageId": 1031,
  "applicable": true,
  "changedKeys": ["monthlySavingAmount"],
  "conditions": { "current": { "...": "C2-3과 같음(4키)" }, "changed": { "...": "" } },
  "current": { "...": "C2-4 CalcResultView (monthlyPoints 키 없음)" },
  "changed": { "...": "C2-4" },
  "diff": { "...": "C2-5" },
  "display": { "...": "C2-6 키·형식 그대로" }
}
```

- 본문 = `/internal/calc` 성공 응답(C2-3~C2-6)에서 `calcRunId`를 빼고 `messageId`·`applicable`·`changedKeys`를 더한 것. 목 파일에는 internal-calc 부록 A-1 값을 줄임 없이 넣는다.
- **`changedKeys`**: 저장된 `condition_changes`의 키 목록(camelCase). 계산이 아니라 저장값의 키를 그대로 꺼낸 것. 프론트는 카드에서 "무엇을 바꿨나"를 강조하는 데만 쓴다. **[v3] 순서는 보장하지 않는다. 프론트는 집합으로 쓴다**(포함 여부만 본다). 화면에 행을 그리는 순서는 프론트가 `conditions`의 고정 4키 순서(internal-calc C2-2 표)로 정한다. 왜: `condition_changes`는 `jsonb`라 PostgreSQL이 키 순서를 보존하지 않는다. 순서를 약속하면 정렬 코드가 생기고, 집합이면 코드가 0줄이다.
- `messageId` 에코는 유지(목 파일 식별용).
- `display` 포함, `monthlyPoints`(그래프) 제외(R-6, Q-039 3번 권장과 같음).

**계산 방식**
- 메시지의 `calc_run(CHAT)`에 기록된 **계획 버전·데이터 버전·`engine_version`** 과 저장된 `condition_changes`로 다시 계산한다(ADR-002 결과 미저장). 그래서 그 뒤 계획이 바뀌어도 이 비교는 **당시 기준**이다(AI 문장과 같다).
- **[v3] 재계산은 범위 검사를 하지 않고 타입 변환만 한다.** 그때 계산한 값을 재현하는 것이 목적이기 때문이다. 타입 변환 실패나 허용 4키 밖의 키는 `500 INTERNAL_ERROR`(아래 표의 "재계산 실패")다. **범위 위반의 최종 거절은 F-5 검사 6·7(plan) 한 곳**에서만 한다. 그래서 **F-4는 422를 내지 않는다.** `ConditionValidator`가 "범위 검사 없이 타입만 바꾸는" 진입점을 하나 더 내거나, 검증 결과 record에 위반이 있어도 값을 담아 주는 방식 중 #17 재량.
  - 허용 밖 키가 저장된 메시지는 F-4가 500이라 카드·버튼이 없다. F-5의 `FIELD_NOT_CHANGEABLE`은 방어선으로 남는다.
- chat은 **calc 루트 공개 API**로만 부른다(제공자 시그니처, 이름·세부는 A팀 재량):
  ```java
  // calc 루트. 소유권은 findPlanRun과 같은 2단계(calc_run 조회 → plan.findVersion(memberId, ...)).
  public interface ChatComparisonQuery {
      /** CHAT 기록의 기준 버전만. 없음·남의 것·PLAN 기록이면 empty. F-5 검사 4용(재계산 없음). */
      Optional<Long> findChatRunPlanVersion(long memberId, long calcRunId);
      /** CHAT 기록의 데이터 버전·engine_version + 그 기록의 plan_version + changes로 다시 계산. 범위 검사 없음. */
      Optional<ChatComparisonView> recompute(long memberId, long calcRunId, Map<String, JsonNode> conditionChanges);
  }
  public record ChatComparisonView(long basePlanVersionId, CalcComparison comparison, Map<String, String> display) {}
  ```
  - `display`는 `ResultDisplayBuilder`를 calc 안에서 불러 **루트 공개 타입 `Map<String,String>`** 으로 내보낸다(planContext `PlanContextView.display`와 같은 방식). chat이 `calc.web`·`calc.application` 타입을 쓰면 `modulith.md`(하위 패키지 비공개) 위반이다.
  - **[v3] 만드는 곳은 #78.** `ChatComparisonQuery`·`ChatComparisonView`와 **버전 지정 기준 데이터 조회**(`ReferenceDataService`에 버전 id로 꺼내는 메서드 3개)를 #78(`PlanContextProvider.findForCalcRun`)에서 만든다. 왜: #78도 "그 기록의 plan_version·데이터 버전으로" 다시 계산해야 해 같은 조회가 반드시 필요하고 소유권 확인도 같은 2단계라, 하나의 "기록 재현" 코드를 두 공개 API가 쓴다. #78의 선행에 #32가 있으므로 #32에 두면 #32가 refdata까지 떠안는다. #78이 너무 커지면 A팀이 같은 선행으로 나눌 수 있다(재량).
  - `JsonNode`는 Boot 4의 Jackson 3(`tools.jackson.databind.JsonNode`), #17 `ConditionValidator`와 같은 패키지.

**`applicable` 판정**(D-041, 서버 힌트)
1. 이 메시지가 그 회원의 `COMPLETED` `RECALC_RESULT` 중 id가 가장 크고,
2. 그 `calc_run.plan_version_id` = 지금 `plan.current_version_id`일 때 `true`.
- 되돌리기 뒤에도 이 값이 정확한 것은 되돌리기가 새 버전을 만들기 때문이다 [D-068](C12b-7-4).
- `currentVersionId`는 plan 공개 현재 계획 조회(#19)에서 얻는다(chat → plan).

오류:

| HTTP | `code` | 언제 | 프론트 동작 |
|---|---|---|---|
| 400 | `MALFORMED_REQUEST` | `messageId`가 정수 아님 | 카드 자리 비움(개발 오류) |
| 404 | `NOT_FOUND` | 없는 id, 남의 메시지, `RECALC_RESULT` 아님, `COMPLETED` 아님 — 모두 같은 404 | 카드 자리 비움, 버튼 없음 |
| 500 | `INTERNAL_ERROR` | 재계산 실패(타입 변환 실패·허용 밖 키 포함) | 카드 자리에 "비교 결과를 불러오지 못했어요"(문구표). **[v3] 버튼 없음, 자동 재시도 없음**(새로고침으로 다시 시도) |
| 401 | `UNAUTHENTICATED` | | 로그인 화면 |

**프론트 호출·버튼 규칙**
- **F-4는 가장 최근 `RECALC_RESULT`에만 자동으로 부른다.** 복원 때 RECALC_RESULT가 여러 개여도 1회. 이전 결과는 본문(`content`)만 보이고 카드는 "이전 결과"로 접는다(버튼 비활성). 이전 카드를 펼칠 때 F-4를 부르는 것은 선택 구현.
- 이미 받은 비교는 다시 부르지 않는다.
- **새 `RECALC_RESULT`가 폴링으로 도착하면 이전 카드의 적용 버튼을 즉시 비활성**으로 바꾼다(F-4 재호출 없음).
- **버튼 활성 한 줄**: 활성 = (이 메시지 id = 가진 메시지 중 최대 `RECALC_RESULT` id) AND (F-4 `applicable=true`) AND (이 메시지가 계획 `revision`이 마지막으로 바뀐 뒤에 도착함, C12b-7-4) ⟵ 셋째 조건의 범위는 쟁점 X-2였고 결정됨(D-069: 계획이 바뀐 모든 순간).
- 카드 문구: `diff.byScenario[].achievementChange` 4값과 **[v3] 조건 4개의 행 라벨**(월 저축액·저축 비중·적금 금리·목표 기간)은 프론트가 문구표에서 키로 찾는다. 프론트가 라벨을 지어내지 않는다. 키 목록은 #18·#53 수용 기준에 적는다.
- ETF 0%(`savingsRatioPct=100`)면 시나리오가 AVG 하나다. 카드는 1개·3개를 모두 그린다(목 `get-comparison.200-etf0.json`).

### C12b-7. F-5 대화 비교 결과 적용 `POST /api/chat/messages/{messageId}/apply`

확인창 없이 바로 적용한다(D-016, D-022).

요청:
```json
{ "expectedRevision": 12 }
```

응답 `200`:
```json
{ "revision": 13 }
```

| 필드 | 뜻 |
|---|---|
| `expectedRevision` | 프론트가 들고 있는 내 계획의 `revision`. 출처는 #12-a의 현재 계획 응답, 직전 적용·되돌리기·입력 저장 응답 하나뿐(R-8) |
| `revision` | 새 revision. 프론트는 이 값으로 계획 상태를 바꾸고 되돌리기 토스트가 쓴다 |

- **[v3] 응답은 `revision` 하나다.** v2의 `planVersionId`는 프론트가 쓰지 않아 뺐다(소비자 제안, 제공자 이견 없음). `previousVersionId`·`undoAvailable`도 없다. plan 서비스의 `PlanApplyResult`(C12b-13 요구 1)는 `planVersionId`·`previousVersionId`를 그대로 가져도 되며(로그·테스트용), chat 컨트롤러가 `revision`만 응답 DTO로 옮긴다. #33의 plan 쪽 REST(대안·비교 적용)가 `undoAvailable`을 내는 것은 그 Issue 범위다.
- `calcRunId`는 응답에 없다. 새 버전의 `calc_run(PLAN)`은 `PlanChanged` 리스너(calc)가 비동기로 남기고, 결과 화면 재조회(#30·#31)가 기록이 없으면 계산·기록한다(ERD Q1-c).

#### C12b-7-1. 서버 검사 순서 [합의]

| 순서 | 어디서 | 검사 | 결과 |
|---|---|---|---|
| 0 | 필터 | 토큰 | `401 UNAUTHENTICATED` |
| 1 | chat 컨트롤러 | 본문 파싱, `expectedRevision` 정수, 경로 `messageId` 정수 | `400 MALFORMED_REQUEST` |
| 2 | chat 서비스 | `findByIdAndMemberId`. 없음·남의 것·`RECALC_RESULT` 아님·`COMPLETED` 아님 | `404 NOT_FOUND` |
| 3 | chat 서비스 | 그 회원의 `COMPLETED` `RECALC_RESULT` 중 가장 큰 id가 아님 | `409 STALE_COMPARISON` |
| 4 | chat → calc | `ChatComparisonQuery.findChatRunPlanVersion` = **기준 버전** | (실패 없음. FK로 보장) |
| 5 | plan 서비스 | `plan` 행 없음 | `409 NO_PLAN` |
| 6 | plan 서비스 | `ConditionValidator.validate(ChangePath.CHAT, conditionChanges)`. 허용 밖 키 | `422 FIELD_NOT_CHANGEABLE` |
| 7 | plan 서비스 | 같은 검증의 범위·정수·소수 자릿수 | `422 VALIDATION_FAILED`(`min`·`max`·`unit`·`scale`, `minDisplay`·`maxDisplay` 없음) |
| 8 | plan 서비스 | `plan.revision ≠ expectedRevision` | `409 PLAN_CHANGED` |
| 9 | plan 서비스 | `plan.current_version_id ≠ 기준 버전` | `409 STALE_COMPARISON` |
| 10 | plan 서비스 | `plan_version` INSERT(현재 버전 복사 + 변경 키 + `source='CHAT'`, `calc_start_month`는 현재 값, D-013) → `UPDATE plan ... WHERE member_id=? AND revision=?` → **0행이면 `PlanChangedException`(런타임 예외)으로 트랜잭션 롤백** → `PlanChanged(APPLY)` 발행 | `200` |

- **0행 롤백**: 앞에서 INSERT한 `plan_version`이 남지 않게 한다. `plan_version`은 INSERT 전용이라 고아 행을 지울 경로가 없다. 응답은 8과 같은 `409 PLAN_CHANGED`이며 **현재 상태를 본문에 싣지 않는다** — `persistence.md:14`·`moved-to-code.md`, **[v3] #19 수용 기준**의 "0행이면 현재 상태를 돌려준다"와 다르므로 그 문장들을 고친다(코드 차이 X-10, #33·#19).
- **검사 9를 잠금 없이 읽어도 되는 이유**: 10의 `UPDATE ... WHERE revision = :expected`가 성공했다면 그 사이 `current_version_id`도 바뀌지 않았다(포인터를 바꾸는 모든 경로가 revision을 올린다). 그래서 `SELECT ... FOR UPDATE`가 필요 없다.
- **6·7 입력은 camelCase 키**만이다(원칙 9). chat이 snake_case를 넘기면 모든 대화 적용이 `FIELD_NOT_CHANGEABLE`이 된다.
- 6·7이 걸리는 경우: 비교 뒤 `rules.yml` 범위가 바뀐 때. 다시 검증하는 이유: 저장 경로의 검증을 plan 한 곳에 고정한다(F-4는 범위 검사를 하지 않으므로 여기가 유일한 범위 거절이다).
- **422·500 처리 위치**: plan의 `ConditionViolationException`은 전역 advice로 바로 나간다. `/internal/calc`용 `minDisplay` 덧붙이기와 **[v3] 500 `CALC_FAILED`** 는 **`/internal/calc` 컨트롤러 안의 `@ExceptionHandler`** 에 두어 F-5 응답에 섞이지 않게 한다(#32, C12b-9).
- **검사 순서의 의미(R-10)**: chat의 검사 3(`STALE_COMPARISON`, 최신 아님)은 plan의 검사 8보다 **항상 먼저** 돈다(plan은 대화를 모른다). "둘 다 해당하면 큰 쪽(`PLAN_CHANGED`)을 알린다"는 **plan 안의 8·9 사이에서만** 참이다. 검사 3이 먼저 걸려도 프론트 동작(그 버튼 비활성)이 맞다. 3과 9는 같은 코드·같은 프론트 동작이라 코드를 나누지 않는다.
- 6~10은 plan의 한 트랜잭션. 2~4는 chat의 읽기(트랜잭션 밖 가능).
- 중복 클릭: 두 번째 요청은 8에서 `PLAN_CHANGED`. 프론트는 요청 중 버튼을 막는다.

#### C12b-7-2. 오류 응답의 프론트 동작 [합의]

| `code` | 프론트 동작(문구는 문구표) |
|---|---|
| `STALE_COMPARISON` | 그 버튼 비활성 + "가장 최근 비교 결과만 적용할 수 있어요" 토스트. 계획 화면은 다시 부르지 않음 |
| `PLAN_CHANGED` | "계획이 다른 곳에서 바뀌었어요" 안내 → 결과 화면 전체 다시 불러오기(새 revision). 새 revision을 받으면 대화창 버튼이 모두 비활성된다(C12b-7-4) |
| `VALIDATION_FAILED` / `FIELD_NOT_CHANGEABLE` | "지금 기준으로는 적용할 수 없는 값이에요", 버튼 비활성 |
| `NO_PLAN` | 입력 화면으로. 대화창 상태(메시지 목록)는 그대로 둔다 |
| `NOT_FOUND` | 버튼 비활성 |
| `UNAUTHENTICATED` | 로그인 화면 |
| `INTERNAL_ERROR` / 네트워크 실패 | "적용하지 못했어요" 토스트. **버튼은 활성 그대로**(재시도 가능) |
| `MALFORMED_REQUEST` | 개발 오류. `INTERNAL_ERROR`와 같이 처리 |

#### C12b-7-3. 성공 뒤 프론트 순서 (FR-AI-07, FR-PLAN-03·04, D-041) [합의]

1. 계획 상태의 `revision`을 응답 값으로. → 대화창이 revision 변화를 보고 **모든** 적용 버튼을 끈다(C12b-7-4).
2. 되돌리기 토스트 **10초**(D-041). 토스트가 들고 있는 값은 `revision`.
3. 결과 화면 전체 다시 불러오기(#30·#31).
4. **[v3] F-3은 여기서 부르지 않는다.** 결과 화면의 `calcRunId`가 바뀌면 #54가 한 곳에서 부른다(C12b-3a "호출 지점").

- 결과 재조회와 `PlanChanged` 리스너가 같은 (버전, PLAN) 기록을 동시에 만들어 2행이 되는 경우는 **무해**하다(plan-context C1a-10 합의와 같음. 조회는 `ix_calc_run_latest`로 최신 1행). 유일 제약·멱등 INSERT는 **두지 않는다**. #29의 "같은 이벤트 두 번 → 중복 없음"(이벤트 단위 멱등)과는 다른 이야기라 둘 다 유지해도 모순이 없다. **[정정 2026-11-16, #12-a Y-17]** 이 문장은 `frontend-api-plan.md` C12a-6-6으로 대체: 기준 데이터 활성 버전·`engine_version`이 그대로인 동안 같은 (버전, PLAN) 기록은 #29 advisory 잠금으로 하나만 생긴다(같은 revision = 같은 `calcRunId`).

#### C12b-7-4. 계획이 바뀐 뒤 이전 비교 버튼 비활성 (D-041) — [D-068, D-069]

본문은 **결정됨: 쟁점 X-1 = B(D-068), 쟁점 X-2 = A(D-069)** 를 적는다. 원문에서 기획 추천안이었고 두 재검토도 같은 안을 지지했다(소비자는 v1의 반대를 철회). 결정 변경·새 정책이라 사람이 정할 항목(Q-056, Q-057)이었고 D-068·D-069로 정해졌다. 두 결정은 Claude 판단(진혁 위임)의 임시 결정이다.

- **서버**([D-068]): 되돌리기는 **직전 버전 행을 복사해 새 `plan_version`을 INSERT**하고 포인터를 그 행으로 옮긴다(`previous_version_id = NULL`, `revision + 1`). 그래서 되돌리기 전에 만든 모든 비교는 기준 버전 ≠ 현재가 되어 F-4 `applicable=false`, F-5 검사 9 `STALE_COMPARISON`으로 **서버가 D-041을 보장**한다. 새로고침해도 틈이 없다.
  - 막는 경우: **비교(기준 V1) → 적용(V2) → 10초 안에 되돌리기.** 포인터 교체라면 현재가 다시 V1이 되어 방금 적용한 그 비교가 새로고침 뒤 다시 활성이다. B에서는 현재가 V3(V1의 복사본)이라 비활성이다.
  - 되돌리기는 여전히 **1단계**다. 되돌린 뒤 `previous_version_id`가 NULL이라 다시 되돌리면 `NOTHING_TO_UNDO`. `plan_version`에는 부모 컬럼이 없어 체인이 생기지 않는다. #33 수용 기준("직전 계획으로 돌아가고 시작월은 그대로", "이전 버전이 없으면 409 NOTHING_TO_UNDO")이 그대로 참이다.
  - **[v3] 구현 메모(#33)**: (a) 조건부 UPDATE 하나로 하면 0행일 때 `NOTHING_TO_UNDO`와 `PLAN_CHANGED`를 구분할 수 없다. 복사할 행 id를 알려면 어차피 `plan`을 먼저 읽으므로 **"`plan` 읽기 → `previous_version_id` 없음 `409 NOTHING_TO_UNDO` → revision 다름 `409 PLAN_CHANGED` → 복사 INSERT → `UPDATE plan ... WHERE member_id=? AND revision=?`(0행이면 런타임 예외로 롤백) → `PlanChanged(UNDO)`"** 로 적용(C12b-7-1)과 같은 틀을 쓴다. (b) `schema.sql`의 `source` 주석 "이 버전을 만든 경로"가 B에서는 "복사한 원본의 경로"가 된다. CHECK에 `UNDO`를 더하지 않고(스키마 변경 없음) 주석과 `db/tables.md` 한 줄만 고친다(코드 차이 X-14). 새 버전의 `calc_run(PLAN)`은 기존 `PlanChanged(UNDO)` → calc 리스너가 기록하므로 추가 코드가 없다.
- **프론트**([D-069]): **내 계획이 바뀐 모든 순간**(대화 적용 성공, 되돌리기 성공, 입력 수정 저장, `PLAN_CHANGED`로 다시 불러옴)에 **그때 가진 모든 적용 버튼을 비활성**으로 바꾼다. 그 뒤 새로 온 비교만 F-4 `applicable`을 따른다.
  - **[v3] 구현 방식**: 네 곳에서 함수를 부르지 않는다. **대화창(#53) 한 곳이 계획 상태의 `revision`을 구독**해, 값이 바뀌면 그때 가진 메시지의 적용 버튼을 모두 끈다. **최초 로드(값 없음 → N)는 제외**한다. 왜: 네 경우 모두 `revision`이 바뀌고, 되돌리기(#51)·입력 저장은 A팀 `features/plan`·`features/input`에 있어 B팀 함수를 부르면 팀 경계를 넘는다. 구독 한 곳이면 A팀 코드를 건드리지 않는다.
  - 끈 상태는 메모리에만 있다. 새로고침 뒤에는 서버(`applicable`, 검사 9)가 같은 결과를 낸다(쟁점 X-1 = B 전제, D-068로 결정됨).
  - revision이 바뀌기 전에 보낸 F-4의 응답이 뒤늦게 `applicable=true`로 와도, 그 메시지가 revision 변경 전에 이미 있었다면 비활성을 유지한다.
  - 서버가 보장하므로 프론트는 id를 기억할 필요가 없다(`undoBarrierId`·`planChangeBarrierId` 없음). 이 규칙은 "누르면 409"보다 먼저 버튼을 끄는 화면 반응이다.
- 다른 탭에서의 변경은 프론트가 알 수 없다. 누르면 `PLAN_CHANGED`(revision 다름)나 `STALE_COMPARISON`을 받고 위 규칙대로 비활성된다.
- (기록용. 결정은 X-1 = B(D-068), X-2 = A(D-069)라 아래는 지금 적용하지 않는다. 결정이 바뀔 때의 대안이다.) 쟁점 X-1이 A(포인터 교체 유지)로 정해지면: 같은 `revision` 구독 지점에서 그 순간의 최대 메시지 id를 `planChangeBarrierId`로 기억하고 `id ≤ barrier`인 버튼을 계속 비활성으로 둔다. 새로고침 뒤 틈은 수용(데이터는 안전). 쟁점 X-2가 B로 정해지면: 구독은 되돌리기·대화 적용 성공으로 바뀐 경우에만 반응한다.

### C12b-8. 대화 기록 삭제 (D-051) [합의]

| 항목 | 값 |
|---|---|
| 언제 | (1) 로그인 성공 직후, 쿠키를 내리기 **전**. (2) 로그아웃 처리 중, 쿠키를 만료시키기 **전** |
| 무엇을 | 그 회원의 `chat_message` 전부. `chat_message_citation`은 FK `ON DELETE CASCADE` |
| 무엇은 남나 | `calc_run(CHAT)`(D-057), 계획 |
| 어떻게 | auth가 **`auth.MemberLoggedIn{memberId}`·`auth.MemberLoggedOut{memberId}`**(auth 루트 record)를 `ApplicationEventPublisher`로 발행 → chat이 **동기 `@EventListener`** 로 받아 자체 `@Transactional`에서 **한 문장 일괄 DELETE** `DELETE FROM chat_message WHERE member_id = ?`(`@Modifying` JPQL/네이티브) |
| 의존 | **chat → auth**(이벤트 타입 수신). auth `{}` 유지 |
| **[v3] 누가 만드나** | 이벤트 record·발행·`auth/package-info.java` 문장은 **A팀**: `MemberLoggedIn` + 발행 + `package-info` 문장은 **#16**, `MemberLoggedOut` + 발행은 **#34**. chat 동기 리스너·일괄 DELETE·`modulith.md`는 **B팀 #46**. B팀 Issue는 `auth/` 파일을 고치지 않는다 |

- 왜 이벤트 + 동기 수신: `@SkipRenewal`(auth 루트 타입)을 chat의 F-2가 쓰므로 chat → auth가 이미 생긴다. 삭제를 auth → chat 직접 호출로 하면 auth ⇄ chat 순환으로 `ModularityTests`가 깨진다. 이벤트로 방향을 chat → auth 하나로 맞추고, 동기 `@EventListener`로 받아 "로그인 직후 F-2 복원이 삭제보다 먼저 도는" 경쟁을 없앤다.
- 삭제가 실패하면 예외가 로그인·로그아웃으로 올라가 그 요청도 실패한다(D-030 쪽이 안전). 프론트 로그인 화면(#47)은 일반 5xx를 "로그인하지 못했어요"로 처리한다. 동기 리스너는 `event_publication`에 기록되지 않지만 재시도가 필요 없다(사용자가 다시 로그인).
- **`modulith.md:24` 예외 1줄**: "수신은 `@ApplicationModuleListener`" 규칙에 "응답 전에 끝나야 하는 수신(대화 삭제)은 동기 `@EventListener`"를 더한다(코드 차이 X-11, #46). `auth/package-info.java:4`의 "이 모듈을 직접 의존하지 않는다"는 "다른 모듈은 auth 루트의 이벤트와 `@SkipRenewal`만 쓴다"로 고친다(#16).
- **일괄 DELETE인 이유**: 엔티티를 하나씩 지우면 `retry_of_message_id`(자기 참조, NO ACTION)가 문장마다 검사되어 순서에 따라 실패한다. 한 문장이면 문장 끝 검사라 위반이 없다.
- 이벤트 이름은 #46 본문의 `LoggedIn`/`LoggedOut`을 `MemberLoggedIn`/`MemberLoggedOut`으로 맞춘다(같은 의미, 파일 이름만).
- **진행 중인 PENDING**: 삭제 뒤 ai 응답이 오면 리스너의 완료 갱신(`... AND status='PENDING'`)이 0행이다. 리스너는 이를 오류로 보지 않고 버리며, **0행이면 citation INSERT를 하지 않는다**(순서 고정: UPDATE → 1행일 때만 citation). 그렇지 않으면 삭제된 메시지에 citation을 넣다 FK 위반 → `event_publication` 미완료로 재기동마다 다시 돈다.
- 만료된 토큰으로 로그아웃하면 401이고 삭제는 다음 로그인 때 된다(NFR-INT-03 예외 수용 기준과 같음). #34에 한 줄.

### C12b-9. 오류 공통 규칙과 코드 표

형식은 internal-calc **C3-1 그대로**. `type`, `title`, `status`, `detail`, `instance`(선택) + `code`(필수) + `errors`(필수, 없으면 `[]`).

| HTTP | `code` | 엔드포인트 | 언제 | `errors` |
|---|---|---|---|---|
| 400 | `MALFORMED_REQUEST` | F-1~F-5 | JSON 파싱 실패, 타입 불일치, `afterId`·`messageId` 정수 아님 | `[]` |
| 401 | `UNAUTHENTICATED` | 전부 | 쿠키 없음·서명 불일치·만료 | `[]` |
| 404 | `NOT_FOUND` | F-3, F-4, F-5, 없는 경로 | 없음·남의 것·조건에 안 맞는 대상 | `[]` |
| 405 | `METHOD_NOT_ALLOWED` | 전부 | 지원하지 않는 메서드(프론트는 쓰지 않음) | `[]` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 본문 있는 것 | `Content-Type` 불일치(프론트는 쓰지 않음) | `[]` |
| 409 | `ANSWER_PENDING` | F-1 | 질문 답변 대기 중 | `[]` |
| 409 | `NO_PLAN` | F-5 | 저장된 계획 없음 | `[]` |
| 409 | `PLAN_CHANGED` | F-5 | `expectedRevision` 불일치, UPDATE 0행 | `[]` |
| 409 | `STALE_COMPARISON` | F-5 | 가장 최근 비교 아님, 또는 기준 버전 ≠ 현재 | `[]` |
| 422 | `VALIDATION_FAILED` | F-1, F-5 | 필드 검증 실패 | 1개 이상 |
| 422 | `FIELD_NOT_CHANGEABLE` | F-5 | 저장된 변경에 대화로 못 바꾸는 키 | 1개 이상 |
| 500 | `INTERNAL_ERROR` | 전부 | 예상하지 못한 오류, F-4 재계산 실패. `detail`에 내부 메시지 없음 | `[]` |

- **[v3] 500 코드는 한 곳에서 하나**: 전역 advice의 처리하지 못한 예외 기본값은 **`INTERNAL_ERROR`** 다. `/internal/calc`만 **그 컨트롤러 안의 `@ExceptionHandler`**(422 `minDisplay`를 붙이는 바로 그곳)가 자기 500을 **`CALC_FAILED`** 로 바꾼다(internal-calc C3-2 유지). 전역 advice에 경로별 분기를 넣지 않는다. 왜: 지금 #17 수용 기준은 "처리되지 않은 예외 → 500 `CALC_FAILED`"라 전역 advice 하나로는 두 계약을 동시에 만족할 수 없다. #17 기준을 `INTERNAL_ERROR`로, `CALC_FAILED`는 #32로 옮긴다(코드 차이 X-15).
- **Spring 기본 예외 매핑**(#17): `MethodArgumentTypeMismatchException` → 400 `MALFORMED_REQUEST`, `HttpMessageNotReadableException` → 400 `MALFORMED_REQUEST`, `NoResourceFoundException` → 404 `NOT_FOUND`, `HttpRequestMethodNotSupportedException` → 405 `METHOD_NOT_ALLOWED`, `HttpMediaTypeNotSupportedException` → 415 `UNSUPPORTED_MEDIA_TYPE`. 왜: 매핑하지 않으면 `code` 없는 Spring 기본 ProblemDetail이 나가 원칙 7이 깨진다.
- **모듈 예외는 `config`의 타입을 상속하지 않는다.** 권장 구현: 모듈 예외가 Spring `ErrorResponseException`을 상속하고 `ProblemDetail`에 `code`·`errors` 속성을 직접 담는다. 전역 advice는 `ErrorResponse`를 일반 처리만 하므로 새 코드가 늘어도 `config`를 고치지 않는다. 왜: 모듈 → config 의존이 생기면 config → 모듈과 합쳐 순환이다. 구현 방식 자체는 #17 재량.
- `MALFORMED_REQUEST`, `VALIDATION_FAILED`, `FIELD_NOT_CHANGEABLE`, `NO_PLAN`은 internal-calc C3-2와 같은 이름·뜻. 이 계약이 더하는 코드: `UNAUTHENTICATED`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `ANSWER_PENDING`, `PLAN_CHANGED`, `STALE_COMPARISON`, `INTERNAL_ERROR`. `NOTHING_TO_UNDO`는 #12-a(되돌리기).
- `errors[]`: `field`, `reason`, `min`·`max`·`unit`(OUT_OF_RANGE), `scale`(TOO_MANY_DECIMALS). **`minDisplay`·`maxDisplay`는 프론트 API에 없다**(C3-5).
- `unit`에 `CHAR` 추가(R-7). 받는 쪽은 모르는 값을 무시한다. Bean Validation `@Size`의 `min`·`max`를 `OUT_OF_RANGE`로 바꾸는 매핑 하나로 된다.
- `401`은 리소스 서버 `AuthenticationEntryPoint`(#16)가 쓴다. 필터에서 나오므로 `instance`가 없을 수 있다. 403은 쓰지 않는다(소유권 위반은 404).
- 오류별 프론트 동작은 각 절의 표(C12b-2 폴링, C12b-4 F-1, C12b-6 F-4, C12b-7-2 F-5, C12b-3a F-3)에 있다. 목 파일(C12b-12)과 일대일이다.

예시(`title`은 임시이며 프론트는 쓰지 않는다):

```json
HTTP/1.1 401 Unauthorized
Content-Type: application/problem+json

{ "type": "about:blank", "title": "로그인이 필요해요", "status": 401, "code": "UNAUTHENTICATED", "errors": [] }
```

```json
HTTP/1.1 422 Unprocessable Content
Content-Type: application/problem+json

{
  "type": "about:blank",
  "title": "입력 범위를 벗어났어요",
  "status": 422,
  "detail": "monthlySavingAmount: OUT_OF_RANGE",
  "instance": "/api/chat/messages/1031/apply",
  "code": "VALIDATION_FAILED",
  "errors": [
    { "field": "monthlySavingAmount", "reason": "OUT_OF_RANGE", "min": 10000, "max": 10000000, "unit": "KRW" }
  ]
}
```

```json
HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{ "type": "about:blank", "title": "가장 최근 비교 결과만 적용할 수 있어요", "status": 409,
  "instance": "/api/chat/messages/1029/apply", "code": "STALE_COMPARISON", "errors": [] }
```

```json
HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{ "type": "about:blank", "title": "계획이 바뀌었어요", "status": 409,
  "instance": "/api/chat/messages/1031/apply", "code": "PLAN_CHANGED", "errors": [] }
```

```json
HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{ "type": "about:blank", "title": "앞 질문의 답을 기다리고 있어요", "status": 409,
  "instance": "/api/chat/messages", "code": "ANSWER_PENDING", "errors": [] }
```

```json
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{ "type": "about:blank", "title": "찾을 수 없어요", "status": 404,
  "instance": "/api/chat/explanations", "code": "NOT_FOUND", "errors": [] }
```

### C12b-10. 모듈 의존 방향과 호출 순서 [합의]

#### C12b-10-1. 적용 API 위치: 안 A (R-1)

| 안 | 위치 | 판단 |
|---|---|---|
| **A (채택)** | chat 컨트롤러 `POST /api/chat/messages/{id}/apply` → chat 서비스가 메시지 확인 → plan 공개 서비스(조건값 적용) | chat → plan 추가, plan `{}` 유지. 순환 없음. 대화 규칙(가장 최근 비교, D-041)은 대화를 아는 chat에 |
| B | plan 컨트롤러 `{source:"CHAT", chatMessageId}` | plan → chat. chat → calc → plan과 합쳐 순환 |
| C | 브라우저가 조건 값 전송 | D-033·원칙 4 위반 |
| D | chat 이벤트 → plan 수신 | plan → chat(이벤트 타입), 순환. HTTP 응답에 revision을 줄 수 없음 |

한 줄 이유: 의존을 "대화를 아는 쪽 → 계획을 아는 쪽" 한 방향으로만 두면 순환 없이 D-033·D-041을 서버에서 지킨다.

#### C12b-10-2. 바뀌는 의존 (`package-info.java`)

| 모듈 | 지금 | 이 계약 뒤 | 이유 |
|---|---|---|---|
| `plan` | `{}` | `{}` | 대화를 모른다 |
| `calc` | `{plan, refdata}` | 변경 없음 | |
| `chat` | `{calc, rag}` | **`{calc, rag, plan, auth}`** | F-5 plan 적용, F-4 현재 계획 조회, auth 이벤트 수신·`@SkipRenewal` |
| `auth` | `{}` | **`{}`(변경 없음)** | 이벤트를 발행만 한다 |
| `config`(표 밖) | — | 어느 모듈도 `config`에 의존하지 않음 | 순환 방지 |

- **[v3] 바꾸는 시점은 #41**(paths가 `chat/**`라 `chat/package-info.java`를 이미 포함). `{calc, rag, plan, auth}`로 한 번에 바꾸고 `backend/CLAUDE.md` 모듈 표의 "의존 가능" 칸도 #41에서 고친다. 왜: #42(`@SkipRenewal`, auth)·#44(`currentVersionId`, plan)가 #45보다 먼저 이 의존을 쓰므로, #45에서 바꾸면 그 전 Issue의 `ModularityTests`가 깨진다.

```
chat ──▶ calc ──▶ plan
  │        └────▶ refdata
  ├──────▶ plan
  ├──────▶ rag
  └──────▶ auth   (MemberLoggedIn/MemberLoggedOut 수신, @SkipRenewal)
config ──▶ (auth, plan, calc, chat: 보안 필터 등록·예외 처리. 역방향 없음)
```

지켜야 할 것:
- (a) **calc → chat, plan → (chat·calc), auth → (어느 모듈이든)** 이 생기지 않는다.
- (b) chat은 auth 루트의 이벤트와 `@SkipRenewal`만 쓰고 `memberId`는 Spring Security 타입에서 얻는다.
- (c) 모듈은 `config` 타입을 쓰지 않는다(C12b-9).
- (d) plan 공개 API의 인자·반환·예외에 chat·calc·config 타입을 쓰지 않는다. 기준 버전은 `Long`, 변경은 `Map<String, JsonNode>`(camelCase).

#### C12b-10-3. 호출 순서

**F-1 / F-3 (비동기 답변)**
```
브라우저 ─POST /api/chat/messages─▶ chat.web
  chat: 오래된 PENDING 정리 → ANSWER_PENDING 검사 → USER + ASSISTANT(ANSWER, PENDING) 저장 → ChatAnswerRequested  (한 트랜잭션)
  ◀─202 {userMessageId, assistantMessageId}
[커밋 뒤, 비동기] chat 리스너(#41, 트랜잭션 밖 HTTP)
  행이 PENDING이 아니면 끝 → calc.PlanContextProvider (chat → calc)
  ai-server POST /chat (연결 2초·응답 30초)  ── ai-server ─POST /internal/calc─▶ calc (필요할 때)
  짧은 트랜잭션: UPDATE ... AND status='PENDING' (condition_changes는 camelCase로 변환해 저장) → 1행이면 citation 저장
브라우저 ─GET /api/chat/messages?afterId─▶ (1초, @SkipRenewal) ... hasPending=false면 멈춤
```

**F-4 (비교 조회)**
```
브라우저 ─GET /api/chat/messages/{id}/comparison─▶ chat.web
  chat: findByIdAndMemberId, RECALC_RESULT·COMPLETED 확인
  calc.ChatComparisonQuery.recompute(memberId, calcRunId, conditionChanges)   (chat → calc, 범위 검사 없음)
  plan 현재 계획 조회: currentVersionId                                       (chat → plan)
  chat: applicable, changedKeys
  ◀─200
```

**F-5 (적용)**
```
브라우저 ─POST /api/chat/messages/{id}/apply {expectedRevision}─▶ chat.web
  chat: 메시지 확인(404) → 가장 최근 비교인지(409 STALE_COMPARISON)
  calc.ChatComparisonQuery.findChatRunPlanVersion = 기준 버전                  (chat → calc)
  plan.PlanApplyService.apply(memberId, CHAT, conditionChanges, expectedRevision, 기준 버전)   (chat → plan)
     └ plan 한 트랜잭션: NO_PLAN → 재검증 → PLAN_CHANGED → STALE_COMPARISON → INSERT·UPDATE(0행이면 롤백) → PlanChanged(APPLY)
  ◀─200 {revision}
[커밋 뒤, 비동기] calc 리스너: 새 버전 calc_run(PLAN)
브라우저: revision 갱신(대화창 버튼 비활성) → 결과 화면 다시 불러오기(#30·#31) → calcRunId가 바뀌고 status=CALCULATED면 #54가 F-3
```

**로그인·로그아웃 (C12b-8)**
```
브라우저 ─(#12-a 로그인/로그아웃)─▶ auth
  auth: 인증 성공(또는 로그아웃 요청) → publish MemberLoggedIn / MemberLoggedOut
     └ chat @EventListener(동기, 같은 스레드): DELETE FROM chat_message WHERE member_id = ?
  auth: 쿠키 발급(또는 만료) → 응답
```

### C12b-11. 시간 값과 PENDING 처리

#### C12b-11-1. 임시값 (TBD-33 실측 전) [합의, R-3]

| 값 | 값 | 근거 |
|---|---|---|
| 폴링 간격 | **1초**, `hasPending=false`면 멈춤 | FR-AI-01 체감. PENDING일 때만 돈다 |
| Spring → ai-server 연결 타임아웃 | **2초** | 같은 Compose 네트워크 |
| Spring → ai-server 응답 타임아웃 | **30초** | 도구 호출 1~2회 + 생성 |
| 오래된 `PENDING` 기준 | **60초**(`created_at`) | 응답 타임아웃 + 재기동·재처리 여유 |
| 프론트 폴링 안전 상한 | 시간 상한 없음, **401 외 실패 연속 3회 중단**(C12b-2) | 서버가 60초 안에 `FAILED`로 바꾼다. 서버가 멈춘 경우·4xx 반복은 실패 중단이 막는다 |

- 값은 chat의 `@ConfigurationProperties` 하나와 프론트 상수 하나(폴링 간격)에 둔다.
- ai-server → `/internal/calc` 타임아웃은 30초보다 작게(예: 10초). ai-server 설정, H-2 메모.
- **구현 메모**: 리스너는 Boot 기본 `applicationTaskExecutor`에서 돌고 ai-server 호출 동안 스레드를 최대 30초 잡는다. 동시 질문이 몰리면 큐에서 기다리다 60초 정리로 `FAILED`가 될 수 있다(결과는 일관됨). Java 21이므로 `spring.threads.virtual.enabled=true` 한 줄로 푼다(#41).

#### C12b-11-2. 리스너 규칙 (#41) [합의]

- 시작할 때 행이 `PENDING`이 아니면(정리됨·삭제됨) ai-server를 부르지 않고 끝낸다(재처리 멱등, `modulith.md`).
- 응답 타임아웃·연결 실패·ai 4xx/5xx·planContext 생성 실패 → `FAILED`·`FAILURE`, `content` = 문구표 `chat.failed`.
- 완료 갱신은 `UPDATE ... AND status='PENDING'`. **0행이면 버리고 citation을 저장하지 않는다**(C12b-8).
- `condition_changes`는 **camelCase로 바꿔 저장**한다(C12b-14).

#### C12b-11-3. 오래된 PENDING 정리 [합의, R-9]

- F-1·F-2·F-3이 처리 전에 그 회원의 `status='PENDING' AND created_at < now() − 60초` 행을 `FAILED`·`FAILURE`·`chat.failed`로 바꾼다(`ix_chat_message_pending`).
- 왜 읽을 때 정리: 스케줄러 없이, 재기동 직후에도 사용자가 보는 순간 정리된다. F-2가 정리 후 조회하므로 같은 응답에 `FAILED`가 실린다.

### C12b-3a. (보충) F-3 설명 요청 `POST /api/chat/explanations` [합의]

> 번호는 v1과 같다.

요청:
```json
{ "calcRunId": 540 }
```

응답 `202`:
```json
{}
```

- **[v3] 본문은 빈 객체다.** v2의 `assistantMessageId`는 프론트가 쓰지 않아 뺐다(소비자 제안, 제공자 이견 없음). 프론트는 폴링으로 설명 메시지를 받는다. PENDING 재사용은 Spring 테스트(#43)가 DB 행 수로 확인한다. 왜 `{}`이고 빈 본문이 아닌가: 프론트 `fetch` 래퍼가 성공 응답을 항상 JSON으로 읽게 둔다.
- **[v3] 호출 지점은 #54 한 곳**: 결과 화면이 그린 **`calcRunId`가 바뀌고 `status=CALCULATED`이면** 부른다(effect 하나). 최초 결과 보기, 입력 수정, 적용 뒤, 되돌리기 뒤(TBD-26)가 모두 "결과 재조회 → `calcRunId` 바뀜"으로 이 한 지점에 모인다. #51(되돌리기)·#53(적용)은 결과 재조회까지만 하고 F-3을 부르지 않는다. 왜: 두 곳이 같은 `calcRunId`로 부르면 앞 설명이 이미 `COMPLETED`일 때 PENDING 재사용이 안 되어 설명이 두 개 생긴다. 옛 `calcRunId`로 부르는 일도 구조적으로 없어진다.
- **초과 계획(`status=ALREADY_EXCEEDED`)에서는 부르지 않는다**(D-062). 불리면 서버는 정상 처리하고 ai-server가 LLM 없이 `chat.alreadyExceeded`로 답한다(방어용).
- **404 규칙**(C1a-10): 없는 `calcRunId`, 남의 기록, CHAT 기록은 모두 같은 `404 NOT_FOUND`. 판단은 `calc.PlanContextProvider.findPlanRun(memberId, calcRunId)`(#78) 하나.
- 서버 동작: 오래된 PENDING 정리 → `findPlanRun` → ASSISTANT(`EXPLANATION`, `PENDING`, `calc_run_id`, `baseline_scenario` = `PlanRunRef.baselineScenario`) 저장 → `ChatAnswerRequested` → 리스너 mode=`EXPLAIN`(#43).
- **같은 `calcRunId`로 이미 `PENDING`인 설명이 있으면 새 행 없이 `202`**(R-4). 조회는 `ix_chat_message_run`. 동시 두 요청이 둘 다 못 찾아 2행이 생기는 경우는 무해(잠금 없음).
- F-3은 `ANSWER_PENDING`으로 막지 않는다. `retryOfMessageId`(FR-AI-05, Should)는 범위 밖.

오류와 프론트 동작:

| HTTP | `code` | 프론트 동작 |
|---|---|---|
| 400 | `MALFORMED_REQUEST` | 설명 자리에 아무것도 그리지 않음(대화창에 오류를 쓰지 않음) |
| 404 | `NOT_FOUND` | 결과 화면을 다시 불러온다. **`calcRunId`가 바뀌면 위 호출 지점이 한 번만** 다시 부른다. 같거나 또 실패하면 아무것도 그리지 않음 |
| 500 / 네트워크 | `INTERNAL_ERROR` | 아무것도 그리지 않음 |
| 401 | `UNAUTHENTICATED` | 로그인 화면 |

### C12b-12. 목(mock) JSON 목록 (`frontend/src/mocks/chat/`) [합의]

**파일 형식**: 파일 하나 = **응답 배열**(호출 순서대로). 응답이 하나면 길이 1 배열이다. 새 라이브러리(MSW 등) 없음.

```json
{
  "responses": [
    { "status": 200, "headers": { "Content-Type": "application/json" },
      "body": { "messages": [ "...PENDING..." ], "hasPending": true, "answerPending": true } },
    { "status": 200, "headers": { "Content-Type": "application/json" },
      "body": { "messages": [ "...RECALC_RESULT COMPLETED..." ], "hasPending": false, "answerPending": false } }
  ]
}
```

**[v3] 목 선택 방식(래퍼 계약, #13)**
1. **요청 키 = 메서드 + 경로**(쿼리 제외). 예: `GET /api/chat/messages`, `POST /api/chat/messages/1031/apply`.
2. 요청 키마다 기본 파일이 하나 있다(아래 표의 "기본" 표시). **같은 키의 다른 파일은 개발·테스트 전용 `useMock(파일명)` 호출로 고른다.** 화면 코드는 이 함수를 부르지 않는다.
3. 래퍼는 고른 파일의 i번째 호출에 i번째 응답을 돌려주고, 배열 끝을 넘으면 마지막 응답을 반복한다. **`status`·`headers`를 그대로 반영**한다(4xx·5xx는 실제 `fetch`와 같은 오류 경로로).
4. **F-4 목은 서로 다른 `messageId`를 쓴다**: 정상 `1031`, not-applicable `1027`, ETF 0% `1033`. 경로 키만으로 구분되어 한 화면에 여러 카드를 띄울 수 있다. F-4 오류 목(400·404·500)은 `useMock`으로 고른다.

- `body`는 **줄임표 없이** 이 문서의 예시 값으로 채운다(위 블록의 `"..."`는 설명용). F-4 정상은 internal-calc 부록 A-1에서 `calcRunId`를 지우고 `messageId: 1031`, `applicable: true`, `changedKeys: ["monthlySavingAmount"]`를 더한다.
- 예시 id(1029/1030/1031, calcRunId 540)는 #42·#45 Spring 테스트(REST Docs) 스니펫과 같은 값을 쓴다. REST Docs 스니펫은 단일 응답이므로 배열 목의 각 원소가 스니펫 하나와 같도록 맞춘다.

| 파일 | 엔드포인트 | 응답(순서) | 내용 |
|---|---|---|---|
| `post-messages.202.json` (기본) | F-1 | 202 | C12b-4 응답 |
| `post-messages.400.json` | F-1 | 400 | `MALFORMED_REQUEST` |
| `post-messages.422-required.json` | F-1 | 422 | `text` REQUIRED |
| `post-messages.422-too-long.json` | F-1 | 422 | `text` OUT_OF_RANGE `min 1, max 2000, unit CHAR` |
| `post-messages.409-answer-pending.json` | F-1 | 409 | `ANSWER_PENDING` |
| `post-messages.500.json` | F-1 | 500 | `INTERNAL_ERROR` |
| `get-messages.200-restore-empty.json` (기본) | F-2 | 200 | `{messages: [], hasPending: false, answerPending: false}`(로그인 직후) |
| `get-messages.200-restore-history.json` | F-2 | 200 | 복원: 과거 `RECALC_RESULT` 2개 이상(이전 카드 + 최신 카드), EXPLANATION 1개 |
| **`get-messages.200-restore-pending.json`** [v3] | F-2 | 200 | 복원인데 USER + ASSISTANT PENDING, `hasPending: true`, `answerPending: true`(새로고침 중 보내기 버튼 막기, 복원 안전장치 시험) |
| `get-messages.seq-question-recalc.json` | F-2 | 200 → 200 | USER + ASSISTANT PENDING(`answerPending: true`) → `RECALC_RESULT` COMPLETED(`hasPending: false`) |
| `get-messages.seq-explanation.json` | F-2 | 200 → 200 | 설명 PENDING(`hasPending: true`, `answerPending: false`) → `EXPLANATION` COMPLETED, citations 2개(서로 다른 운용사, FR-AI-03) |
| `get-messages.200-failed.json` | F-2 | 200 | 질문 답변 `FAILURE`/`FAILED`, `content` = 실패 안내 |
| `get-messages.200-explanation-failed.json` | F-2 | 200 | 설명 요청의 `FAILURE`/`FAILED`(앞 메시지가 USER가 아닌 맥락) |
| `get-messages.200-clarify.json` | F-2 | 200 | `CLARIFY`(범위 밖 값·상대 표현 되묻기, D-033·D-059) |
| `get-messages.200-out-of-scope.json` | F-2 | 200 | `OUT_OF_SCOPE` |
| `get-messages.seq-500x3.json` | F-2 | 500 → 500 → 500 | 연속 3회 실패 중단 테스트 |
| `get-messages.401.json` | F-2 | 401 | 폴링 중 만료 |
| `post-explanations.202.json` (기본) | F-3 | 202 | `{}` |
| `post-explanations.404.json` | F-3 | 404 | `NOT_FOUND` |
| `post-explanations.500.json` | F-3 | 500 | `INTERNAL_ERROR` |
| `get-comparison.200.json` (기본, `/1031`) | F-4 | 200 | internal-calc A-1 기반 전체 본문, `messageId: 1031`, `applicable: true` |
| `get-comparison.200-not-applicable.json` (`/1027`) | F-4 | 200 | 같은 본문, `messageId: 1027`, `applicable: false` |
| `get-comparison.200-etf0.json` (`/1033`) | F-4 | 200 | ETF 0%, 시나리오 AVG 1개, `messageId: 1033` |
| `get-comparison.200-goal-changed.json` (`/1034`) | F-4 | 200 | 목표 금액 변경 비교(D-090), `conditions.current.goalAmount`·`conditions.changed.goalAmount` 분리, `messageId: 1034` |
| `get-comparison.200-asymmetric-exceeded.json` (`/1035`) | F-4 | 200 | 현재 초과에서 목표 상향 비대칭 비교(D-092), `current.status=ALREADY_EXCEEDED`, `diff.byScenario=[]`, `messageId: 1035` |
| `get-comparison.200-changed-exceeded.json` (`/1036`) | F-4 | 200 | 바꾼 조건 초과 비대칭 비교(D-092), `changed.status=ALREADY_EXCEEDED`, `diff.byScenario=[]`, `messageId: 1036` |
| `get-comparison.400.json` | F-4 | 400 | `MALFORMED_REQUEST` |
| `get-comparison.404.json` | F-4 | 404 | `NOT_FOUND` |
| `get-comparison.500.json` | F-4 | 500 | `INTERNAL_ERROR`(재계산 실패) |
| `post-compare.200-etf.json` (기본) | `POST /api/compare` | 200 | 결과 화면 ETF 종목 미리보기(C12a-9-3). `changedKeys: ["etfProductCode"]`, current는 SP500·changed는 069500, 다섯 조건은 양쪽이 같다 |
| `post-compare.422-not-available.json` | `POST /api/compare` | 422 | `VALIDATION_FAILED`, `errors[0] = {"field": "etfProductCode", "reason": "NOT_AVAILABLE"}` |
| `post-apply.200.json` (기본) | F-5 | 200 | `{revision: 13}` |
| `post-apply.409-plan-changed.json` | F-5 | 409 | `PLAN_CHANGED` |
| `post-apply.409-stale.json` | F-5 | 409 | `STALE_COMPARISON` |
| `post-apply.409-no-plan.json` | F-5 | 409 | `NO_PLAN` |
| `post-apply.422-validation.json` | F-5 | 422 | C12b-9 예시(`min`·`max`·`unit`, display 키 없음) |
| `post-apply.422-field-not-changeable.json` | F-5 | 422 | `FIELD_NOT_CHANGEABLE`, `errors: [{field: "goalType", reason: "NOT_CHANGEABLE"}]` |
| `post-apply.404.json` | F-5 | 404 | `NOT_FOUND` |
| `post-apply.500.json` | F-5 | 500 | `INTERNAL_ERROR` |
| `common.401-unauthenticated.json` | 전부 | 401 | C12b-9 401 예시. 래퍼 공통 401 분기 테스트용 |

- 총 **38개**. `post-explanations.seq-reuse`는 F-3 응답에 id가 없어 프론트에서 확인할 것이 없으므로 두지 않는다.
- Issue #12 수용 기준 "정상, 422, 401, 409를 모두 받을 수 있다": F-1(202·422·409), F-2(200·401), F-5(200·409·422), 공통 401. 404·400·500도 있다.
- `FIELD_NOT_CHANGEABLE`의 `errors[].reason` 값은 internal-calc C3-1의 해당 행을 따른다. 위 예시의 `reason` 문자열이 C3-1과 다르면 C3-1이 기준이다.

### C12b-13. #12-a(및 A팀)에 요구하는 것 [합의]

| # | 요구 | 담당 Issue | 추가 비용(제공자 추정) | 왜 |
|---|---|---|---|---|
| 1 | **plan 공개 적용 서비스**(아래 시그니처). 검사 5~10, 기준 버전 검사, **0행 롤백** | A, **#33** | +0.5일 | F-5가 부른다 |
| 2 | 401 `AuthenticationEntryPoint`가 C3-1 형식 | A, **#16** | 0 | Issue #12 수용 기준(예외) |
| 3 | F-2에 `@SkipRenewal`(auth 루트 타입, 인터셉터 또는 필터 + `HandlerMappingIntrospector`) | A, **#34** | 0 | D-052 |
| 4 | 현재 계획·결과 응답에 **`revision`, `calcRunId`(PLAN), `result.status`**. **[v3] 입력 저장(결과 보기) 응답에 `revision`** | A, **#31**(현재 계획 조회), **#30**(결과 보기 = 입력 저장 REST). #19는 저장 서비스가 새 `revision`을 반환 | 필드 3개, 0.25일 | F-5 `expectedRevision`, F-3 `calcRunId`, D-062 |
| 5 | 되돌리기 응답에 새 **`revision`**. 되돌리기 방식 [D-068] | A, **#33** | B안이면 INSERT 1문장·테스트 1건(0.25일) | C12b-7-4 |
| 6 | **[v3]** calc 공개 API `ChatComparisonQuery`(기준 버전 조회, 범위 검사 없는 재계산 + display)와 버전 지정 기준 데이터 조회(refdata 3메서드) | A, **#78**(기록 재현 코드를 `PlanContextProvider.findForCalcRun`과 공유). `calc_run` 조회는 **#29** 리포지토리 재사용 | 약 1일 | F-4·F-5 |
| 7 | plan 공개 현재 계획 조회에 `currentVersionId` | A, **#19**(G-12와 같은 API) | 필드 1개 | F-4 `applicable` |
| 8 | **[v3]** 이벤트 record + 발행(쿠키 응답 전) + `auth/package-info.java` 문장 | A, **#16**(`MemberLoggedIn`, package-info)·**#34**(`MemberLoggedOut`). 수신은 B **#46** | 0.25일 | D-051, C12b-8 |
| 9 | **[v3]** 전역 advice: C12b-9 코드, 500 기본값 `INTERNAL_ERROR`, Spring 기본 예외 매핑. 모듈 예외 `config` 상속 금지. `/internal/calc`의 500 `CALC_FAILED`·422 `minDisplay`는 그 컨트롤러의 `@ExceptionHandler` | A, **#17**(전역), **#32**(`/internal/calc` 한정) | `ErrorResponseException` 방식이면 0에 가까움 | 원칙 7, 코드 차이 X-15 |
| 10 | **[v3]** 결과 화면이 `revision`·`calcRunId`·`status`를 공유 상태로 둔다. 되돌리기·적용 뒤에는 결과 재조회까지만(F-3은 #54) | 프론트 A **#51**(되돌리기 토스트)·**#49**(결과 화면), B **#54**(F-3 호출) | Spring 영향 없음 | C12b-3a, C12b-7-3 |

요구 1 시그니처(인자·검사 순서·예외 종류는 계약, 세부는 A팀 재량):
```java
// plan 루트 패키지(공개 API). chat·calc·config 타입을 쓰지 않는다.
public interface PlanApplyService {
    /**
     * 바꾼 조건을 현재 계획에 적용한다. 한 트랜잭션. 시작월은 현재 버전 값(D-013).
     * UPDATE 0행이면 PlanChangedException으로 롤백한다.
     * @param path ALTERNATIVE·COMPARE·CHAT만 허용(INPUT_EDIT이면 IllegalArgumentException)
     * @param expectedBaseVersionId 비교의 기준 버전. null이면 검사하지 않음(대안·비교 적용)
     */
    PlanApplyResult apply(long memberId, ChangePath path, Map<String, JsonNode> conditionChanges,
                          long expectedRevision, Long expectedBaseVersionId);
}
public record PlanApplyResult(long planVersionId, Long previousVersionId, long revision) {}
// 예외(plan 루트, config 타입 상속 금지): NoPlanException(NO_PLAN), ConditionViolationException(VALIDATION_FAILED/FIELD_NOT_CHANGEABLE),
//                 PlanChangedException(PLAN_CHANGED), StaleBaseVersionException(STALE_COMPARISON)
```
- `ConditionValidator`는 검증 결과를 **타입 있는 값 record**로 돌려준다(다시 파싱하지 않게). **[v3]** F-4용 "범위 검사 없이 타입만 바꾸는" 사용 방식도 같은 record로 지원한다(#17 재량).
- ERD Q4-b의 SQL에 기준 버전 검사를 더한다(코드 차이 X-6).

### C12b-14. ai-server에 미치는 영향 [합의]

- **새 필드·새 엔드포인트 없음.** F-1·F-3은 지금의 `ChatRequest`·`ChatResponse`를 쓴다.
- **키 표기**: ai-server `ConditionChanges.changed()`는 snake_case 키를 내고 `ChatResponse.condition_changes`로 그대로 온다. **chat 리스너(#41)가 저장할 때 camelCase로 바꿔 저장**하고(4키 고정 맵), 그 뒤 F-4 재계산·F-5 적용은 변환 없이 쓴다. 허용 4키 밖의 키는 **그대로 저장**한다(조용히 버리지 않음). 그런 메시지는 F-4가 500, F-5가 `FIELD_NOT_CHANGEABLE`이다. #44 수용 기준의 "snake_case로 저장된 RECALC_RESULT → camelCase로 바꿔 넘긴다"는 "저장은 camelCase(#41), #44는 변환 없음"으로 고친다. 운영 데이터가 없으므로 이전 형식 호환은 필요 없다.
- **`ConditionChanges`가 지금 7키**라 D-040의 4키와 다르다. **#21(4키로 축소)을 #44·#45 선행**으로 둔다. F-2가 `conditionChanges`를 내보내지 않으므로 프론트에는 새지 않는다.
- F-1에서 계획이 없으면 Spring이 `baselineScenario: "AVG"`를 채운다(C12b-4). `history[].role`은 Spring이 소문자로 바꾼다.
- 위험 메모(제공자): Spring은 ai-server가 보고한 `conditionChanges`가 실제 `/internal/calc`에 보낸 값과 같은지 검증하지 못한다(`calc_run`에 변경값 미저장, ADR-002). 다르면 F-4 카드가 AI 문장과 어긋난다(카드와 적용은 서로 일치하므로 계획 데이터는 안전). **ai-server 테스트(#23)에서 "보고값 = 보낸 값"을 고정**한다.

---

## 4. 기준 데이터 절 (#220)

### R-1. `GET /api/etf-products`: 고를 수 있는 ETF 종목 목록

결과 화면에서 ETF 종목을 바꿀 때(#156 2단계) 고를 수 있는 종목을 내려준다. 종목은 서버 설정(`application.yml`의 `moeun.refdata.seeds`)으로 적재된 것만 나오며, 프론트는 목록을 하드코딩하지 않는다(FR-DATA-03).

- 요청: 본문 없음. 로그인 쿠키 필요(다른 `/api/**`와 같음)
- 성공 `200`:

```json
{
  "products": [
    { "code": "SP500", "displayName": "S&P500 추종 ETF", "gainTaxRatePct": 15.4 },
    { "code": "069500", "displayName": "코스피200 추종 ETF", "gainTaxRatePct": 0 },
    { "code": "133690", "displayName": "나스닥100 추종 ETF", "gainTaxRatePct": 15.4 }
  ]
}
```

| 필드 | 타입 | 뜻 |
|---|---|---|
| `products[].code` | string | 종목 코드. 계획·대화 조건에 쓰는 값(#154, #157) |
| `products[].displayName` | string | 화면 이름. 상품명 대신 추종 지수로 쓴다 |
| `products[].gainTaxRatePct` | number | 매매차익 세율(%). 국내 주식형은 `0` |

- 순서: 서버 설정(`moeun.refdata.seeds`)에 적힌 순서. 대표 상품(`SP500`, 종목을 정하지 않은 계획이 쓰는 종목)을 맨 앞에 둔다. 설정에 없는 종목은 맨 뒤에 종목 코드 순
- 가격 데이터가 적재되지 않은 종목은 목록에 없다(계산할 수 없으므로). 하나도 없으면 `200 {"products": []}`
- 오류: 로그인하지 않으면 `401 UNAUTHENTICATED`(C3-1). 서버의 기준 데이터가 어긋나면(종목 정보 누락 등) 공통 `500 INTERNAL_ERROR`. 그 밖의 오류 코드는 없다
- 목: `refdata/get-etf-products.200.json`

---

## 5. 대안 절 (#66, #258)

결과 화면 대안 영역(FR-ALT-01~04, 와이어프레임 03)이 쓰는 대안 값 API. 백엔드는 #66에서 구현했고 이 절은 #258에서 화면 연결을 위해 적었다. 카드 값과 본문은 **D-096**(Q-089·Q-103)을 따른다.

### A-1. `POST /api/alternatives`

현재 계획에서 조건 **하나만** 바꾼 대안 3개를 계산해 돌려준다. 내 계획과 calc_run은 바뀌지 않는다.

요청(본문은 비어 있어도 된다):
```json
{ "targetPeriodMonths": 48 }
```

| 필드 | 뜻 |
|---|---|
| `targetPeriodMonths` | '기한을 늘리면' 카드에 쓸 원하는 기간(개월). 없으면 그 카드는 입력 전의 빈 카드다(D-020·D-039) |

응답 `200`:
```json
{
  "hasAlternatives": true,
  "alternatives": [
    { "key": "monthlySavingAmount", "changes": { "monthlySavingAmount": 330000 },
      "conditions": { "current": { "...": "5개 조건" }, "changed": { "...": "" } },
      "current": { "...": "CalcResultView" }, "changed": { "...": "CalcResultView" }, "diff": { "...": "" },
      "display": { "conditions.current.monthlySavingAmount": "약 30만원", "conditions.changed.monthlySavingAmount": "약 33만원",
                   "diff.AVG.afterTaxAssetDelta": "약 117만원 증가", "changed.AVG.afterTaxAsset": "약 1,788만원",
                   "changed.AVG.gapToGoal": "-약 212만원 미달", "...": "비교 display(C12b-6)와 같은 키" } },
    { "key": "targetPeriodMonths", "changes": {}, "conditions": null, "current": null, "changed": null, "diff": null, "display": {} },
    { "key": "savingsRatioPct", "changes": { "savingsRatioPct": 40 }, "...": "" }
  ]
}
```

| 필드 | 뜻 |
|---|---|
| `hasAlternatives` | 대안을 보여줄지. 기준 시나리오로 목표를 달성했거나 이미 넘은 계획이면 `false`이고 `alternatives`는 빈 배열 |
| `alternatives[]` | 항상 `monthlySavingAmount`·`targetPeriodMonths`·`savingsRatioPct` 순서의 3장. 순서는 우열이 아니다(FR-ALT-02) |
| `key` | 바꾼 조건 |
| `changes` | `{key: 바꾼 값}`. '비교하기'가 비교 영역에 채우고, '내 계획으로 적용'이 P-6 `conditionChanges`로 보낸다. 빈 카드면 `{}` |
| `display` | 비교 display(C12b-6)와 같은 키. 빈 카드면 `{}` |

- 대안 값(D-096 ①): 월 저축액은 현재의 **+10%**, 비중은 적금 **−10%p**(ETF +10%p)를 서버가 정한다. 기한은 사용자가 입력한다.
- 바꿀 값이 없는 카드(기한 입력 전 등)는 `changes`가 비고 나머지가 `null`·`{}`인 빈 카드다.

#### A-1-1. 카드 본문과 display 키 (D-096 ②)

| 카드 줄 | display 키 |
|---|---|
| 바뀌는 조건 | `conditions.current.<key>` → `conditions.changed.<key>` (문구표 형식으로 잇기만 한다) |
| 변화 | `diff.AVG.afterTaxAssetDelta` (평균 세후 자산 변화) |
| 예상 자산(평균) | `changed.AVG.afterTaxAsset` |
| 목표 대비 | `changed.AVG.gapToGoal` |
| 나빴던 때 | `changed.BAD.afterTaxAsset` · `changed.BAD.gapToGoal` (ETF 0%면 키가 없어 줄을 그리지 않는다) |

- 프론트는 숫자를 만들지 않는다(NFR-INT-01). 키가 없는 줄은 그리지 않는다.
- 변동 경고(W-10)는 서버 값이 없어 그리지 않는다(D-096 ③).

#### A-1-2. 오류

| HTTP | `code` | 언제 | 프론트 동작 |
|---|---|---|---|
| 401 | `UNAUTHENTICATED` | 쿠키 없음·만료 | 로그인 화면(래퍼) |
| 409 | `NO_PLAN` | 계획 없음 | 입력 화면 |
| 422 | `VALIDATION_FAILED` | `targetPeriodMonths`가 허용 범위 밖(`errors[0] = {field: "targetPeriodMonths", reason: "OUT_OF_RANGE", min: 1, max: 360, unit: "MONTH"}`) | 기한 카드에 범위 안내, 다른 카드는 그대로 |
| 500 | `INTERNAL_ERROR` | 그 밖 | 카드를 비우고 안내. 적용 버튼 비활성 |

#### A-1-3. 화면 흐름

1. 결과 화면이 열리면 본문 없이 부른다. `hasAlternatives: false`면 대안 영역을 그리지 않는다.
2. 기한 카드에 개월 수를 적으면 `{targetPeriodMonths}`로 다시 불러 카드를 채운다.
3. 내 계획 `revision`이 바뀌면(적용·되돌리기·입력 수정) 다시 부른다. 이전 revision으로 받은 카드는 적용할 수 없다.
4. '내 계획으로 적용'은 확인창(C12a-9-2)을 거쳐 P-6을 `{path: "ALTERNATIVE", expectedRevision, conditionChanges: card.changes}`로 부른다. 응답별 동작은 C12a-9-2 표와 같다.

#### A-1-4. 목(mock) JSON (`frontend/src/mocks/alternative/`)

목은 요청 키(메서드 + 경로)로만 고르므로 본문으로 응답을 나누지 않는다. 기본 파일은 순서 파일이다.

| 파일 | 응답 | 내용 |
|---|---|---|
| `alternative/post-alternatives.seq.json` (기본) | 200 → 200 | 첫 요청은 기한 카드 빈 응답, 그다음(반복)은 기한 48개월 응답 |
| `alternative/post-alternatives.200.json` | 200 | 기한 카드 빈 응답 |
| `alternative/post-alternatives.200-period.json` | 200 | `{targetPeriodMonths: 48}` 응답(REST Docs `alternative-find`와 같은 값) |
| `alternative/post-alternatives.200-none.json` | 200 | `{hasAlternatives: false, alternatives: []}` |
| `alternative/post-alternatives.422-period.json` | 422 | `targetPeriodMonths` `OUT_OF_RANGE` |
| `alternative/post-alternatives.409-no-plan.json` | 409 | `NO_PLAN` |
