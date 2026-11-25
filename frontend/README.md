# moeun frontend

Vite + React + TypeScript. 패키지 관리자는 npm, 라우터는 `react-router-dom`, 테스트는 vitest.

```bash
cd frontend
npm install
npm run dev      # 헤더("모은")가 있는 빈 화면. API 호출은 없다
npm run build    # tsc -b && vite build
npm run lint     # eslint
npm test         # vitest run (src/**/*.test.{ts,tsx})
```

목 JSON 자체의 검증은 `node --test frontend/src/mocks`(Node 내장 테스트)이고 `npm test`와 별개다.

## 폴더

| 경로 | 내용 |
|---|---|
| `src/app/` | 진입점(`main.tsx`), 공통 레이아웃과 라우트(`App.tsx`), 경로 상수(`routes.ts`) |
| `src/api/` | fetch 래퍼(`client.ts`), 목(`mock.ts`), 공유 계획 store(`planStore/`), 폴링 레지스트리(`polling.ts`), 탭 동기화(`authSync.ts`) |
| `src/mocks/` | API 목 JSON 65개와 그 설명(`src/mocks/README.md`) |

화면 코드(`src/app` 등)는 `fetch`·`useMock`·`mockFetch`·`XMLHttpRequest`를 쓰지 않는다. 서버와는 `src/api`의 `api.request()` 하나로만 통신한다.

## 같은 사이트 전제

로그인 쿠키는 `HttpOnly; SameSite=Lax`다. 그래서 프론트와 Spring은 **같은 사이트**로 서빙한다. 브라우저는 절대 URL 없이 상대 경로 `/api/...`만 부른다.

- 개발: Vite 개발 서버가 `/api`를 Spring(`http://localhost:8080`)으로 넘기는 **프록시**를 둔다(`vite.config.ts`의 `server.proxy["/api"]`). Spring 주소가 다르면 `VITE_API_PROXY_TARGET=http://호스트:포트 npm run dev`.
- 운영: Vercel이 `vercel.json`의 rewrites로 `/api/*`를 백엔드 도메인에 넘긴다. 브라우저 입장에서는 같은 출처라 쿠키가 프론트 도메인에 붙는다(`../docs/deploy.md`).
- 래퍼는 모든 요청에 `credentials: "include"`를 붙인다. 토큰·쿠키를 JS에서 읽거나 `localStorage`에 두지 않는다.

## 목 모드

```bash
VITE_API_MODE=mock npm run dev
```

래퍼가 `fetch` 대신 `src/mocks/**`의 JSON을 돌려준다. `VITE_API_MODE`가 `mock`이 아니면 실서버 모드다.

- 요청 키는 **메서드 + 경로**(쿼리 제외)다. 예: `GET /api/chat/messages`.
- 키마다 기본 파일이 하나다. 같은 키의 다른 파일은 개발·테스트 전용 `useMock("get-plan.seq-exceeded-toggle.json")`으로 고른다(확장자 포함 파일 이름). 고르면 그 키의 호출 횟수가 0으로 돌아간다. `resetMocks()`는 선택과 횟수를 모두 되돌린다.
- 파일의 i번째 호출에 i번째 응답을 돌려주고, 끝을 넘으면 마지막 응답을 반복한다.
- `common.401-unauthenticated.json`은 모든 키에 적용된다.
- `GET /api/chat/messages/{id}/comparison`은 id별 기본 파일(1031·1027·1033)이고 그 밖의 id는 404 목이다. 이 경로와 `…/{id}/apply`는 `useMock`으로 고른 파일이 모든 id에 적용된다.

## 래퍼 오류

`api.request()`는 성공하면 파싱한 본문을 돌려주고, 실패는 항상 `ApiError`로 거절한다. 분기는 HTTP 상태가 아니라 `code`로 한다.

- `application/problem+json`이면 `kind: "problem"`(`status`, `code`, `problem.errors`).
- 그 밖(프록시 502 HTML, JSON이 아닌 본문, 연결 실패)은 `kind: "network"`, `code: null`, `messageKey: "common.networkError"`.
- `UNAUTHENTICATED`면 공유 store 비우기 → 폴링 `stopAll()` → `/login` 이동을 이 순서로 한 번씩 하고 오류를 던진다. 이미 `/login`이면 이동하지 않는다. `INVALID_CREDENTIALS`는 아무것도 하지 않는다.
- `PUT /api/plan` 응답이 유실되면(network 오류) 오류를 던진 뒤 `loadPlan()`을 한 번 부른다(이미 떠난 GET에 합류하지 않고 새로 조회한다).
- 로그인·로그아웃 성공은 다른 탭에 알리고(`BroadcastChannel("moeun-auth")`), 받은 탭은 store를 비우고 `loadPlan()`부터 다시 시작한다.

## 공유 계획 store

계획 상태(`revision`·`view`·`settledStatus`)는 `src/api`의 `planStore` 하나만 읽고 쓴다.

- `GET /api/plan`은 `loadPlan()`만 부른다. 같은 `revision`에서 시작된 진행 중 요청이 있으면 같은 Promise에 합류한다(목 시퀀스도 한 번만 소비). 화면이 `fetch`로 직접 부르면 안 된다.
- `acceptRevision(n)`·`acceptPlanView(view)`는 작은 값으로 덮어쓰지 않는다. F-5·P-5 응답은 `acceptRevision`, P-3·P-4 응답은 `acceptPlanView`.
- `settledStatus`는 `view.revision === revision`일 때만 `view.status`로 바뀌고 아니면 직전 값을 유지한다. 계획이 초과인지는 이 값으로 판단한다.
- `acceptNoPlan(sentRevision)`은 `NO_PLAN`을 받았을 때 `loadPlan()`이 부른다. 요청 시점 `revision`이 `null`이었는데 지금 값이 있으면 무시하고, 아니면 비우고 입력 화면으로 보낸다.
- `subscribe`는 상태가 바뀔 때, `onRevisionChange(next, prev)`는 `revision`이 N→M·N→null로 바뀔 때만 불린다(최초 로드 null→N은 제외). `useSyncExternalStore`와 같이 쓸 수 있다.
