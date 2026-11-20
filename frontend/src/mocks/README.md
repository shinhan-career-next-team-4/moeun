# 프론트 API 목(mock) JSON

[`docs/api/frontend-api.md`](../../../docs/api/frontend-api.md)의 응답 예시를 파일로 옮긴 것이다. 파일 이름과 내용은 계약 문서 C12a-12(로그인·계획)·C12b-12(대화) 표와 같고, REST Docs 스니펫(#16·#30·#31·#33·#42·#45)과 같은 값이다. 선택·소비 방식은 #13(fetch 래퍼)이 구현한다.

| 폴더 | 파일 수 | 엔드포인트 |
|---|---|---|
| `chat/` | 35 | F-1~F-5, 공통 401 |
| `auth/` | 6 | P-1, P-2 |
| `plan/` | 24 | P-3, P-4, P-5 (P-6 목은 #68) |

로그인·계획 목의 합계는 30개다. 계약 최종본 본문은 29개라고 적었지만 같은 문서의 표는 30행이다(`put-plan.422-custom-name` 추가분). 표를 기준으로 했다.

## 파일 형식

파일 하나는 응답 배열이다(호출 순서대로). 응답이 하나면 길이 1 배열이다.

```json
{
  "responses": [
    { "status": 200, "headers": { "Content-Type": "application/json" }, "body": {} }
  ]
}
```

- 성공은 `application/json`, 오류는 `application/problem+json`이다. 오류 본문은 `type`·`title`·`status`·`code`·`errors[]`(C3-1)이고 프론트 API에는 `minDisplay`·`maxDisplay`가 없다.
- `headers`의 `Set-Cookie`는 브라우저가 `Response`에 넣지 않는 금지 헤더라 무시된다. 쿠키 속성을 보여 주는 설명용이다.
- `body`에 줄임표가 없다. 나머지 문구(`title`)는 임시이며 프론트는 쓰지 않는다. `code`로만 분기한다.

## 선택 방식

1. 요청 키는 메서드 + 경로(쿼리 제외)다. 예: `GET /api/chat/messages`, `POST /api/chat/messages/1031/apply`.
2. 요청 키마다 기본 파일이 하나다(`(기본)` 표시, 계약 문서의 표 참고). 같은 키의 다른 파일은 개발·테스트 전용 `useMock(파일명)`으로 고른다. 화면 코드는 이 함수를 부르지 않는다.

**개발 중 기본 파일 바꾸기**: `VITE_MOCK_FILES`에 쉼표로 파일 이름을 적으면 그 파일을 기본 대신 쓴다(계약 표의 기본 파일은 그대로다).

```bash
VITE_API_MODE=mock VITE_MOCK_FILES=get-plan.200.json npm run dev
```

대안 영역은 미달일 때만 보이므로(FR-ALT-01) 기본 `get-plan.seq-full-flow.json`의 세 번째 응답(세 구간 모두 달성)에서는 카드가 숨는다. 위처럼 `get-plan.200.json`(세 구간 모두 미달)을 고르면 바로 보인다.
3. 고른 파일의 i번째 호출에 i번째 응답을 돌려주고, 배열 끝을 넘으면 마지막 응답을 반복한다. `status`·`headers`는 그대로 반영한다(4xx·5xx는 실제 `fetch`와 같은 오류 경로로).
4. F-4 목은 `messageId`가 달라 경로만으로 구분된다: 정상 `1031`, 적용 불가 `1027`, ETF 0% `1033`. F-4 오류 목(400·404·500)은 `useMock`으로 고른다.

## 시퀀스 파일

**시퀀스 파일은 요청 순서로 소비된다.** 파일 이름에 `seq`가 들어간 것이 시퀀스다.

- `GET /api/plan`은 공유 store의 `loadPlan()`(single-flight)을 거친다. 그래서 개발 모드의 이중 effect나 여러 화면의 동시 마운트가 있어도 요청이 한 번만 나가고 시퀀스는 한 번만 소비된다. 화면이 `fetch`를 직접 부르면 순서가 밀린다.
- 기본 파일 `plan/get-plan.seq-full-flow.json`(401 → 409 → 200 → 200)과 `plan/put-plan.seq-full-flow.json`(200 → 200)이 함께 "앱 시작 → 로그인 → 입력 → 결과 → 대화 적용 → 되돌리기 → 입력 수정"을 목만으로 돌린다.

| 순서 | 사용자 동작 | 요청 | 목 응답 |
|---|---|---|---|
| 1 | 앱 시작 | `GET /api/plan` | `get-plan.seq-full-flow` 1번: 401 `UNAUTHENTICATED` |
| 2 | 로그인 | `POST /api/auth/login` | `auth/post-login.200` |
| 3 | 진입 분기 | `GET /api/plan` | 2번: 409 `NO_PLAN` |
| 4 | 결과 보기 | `PUT /api/plan` | `put-plan.seq-full-flow` 1번: `rev 12 / calcRunId 540` |
| 5 | 대화 적용 | `POST /api/chat/messages/1031/apply` | `chat/post-apply.200` `{revision: 13}` |
| 6 | 재조회 | `GET /api/plan` | 3번: `rev 13 / 553`(월 50만원 적용, 3구간 모두 달성) |
| 7 | 되돌리기 | `POST /api/plan/undo` | `plan/post-undo.200` `{revision: 14}` |
| 8 | 재조회 | `GET /api/plan` | 4번(마지막, 반복): `rev 14 / 554`(기준 계획 숫자) |
| 9 | 입력 수정 저장 | `PUT /api/plan` | 2번(마지막, 반복): `rev 15 / 555`(목표 기간 48개월, 49점) |

9번 뒤의 `GET /api/plan`은 마지막 응답(`rev 14`)을 반복하지만 공유 상태 규칙 1(작은 `revision`은 무시)로 걸러져 화면은 `rev 15`를 유지한다.

`plan/get-plan.seq-exceeded-toggle.json`은 `useMock` 전용이다. 미달(`rev 12 / 540`) → 초과(`rev 13 / 542`) → 풀림(`rev 14 / 554`)으로 D-072와 "초과면 F-3을 부르지 않는다"(D-062)를 시험한다. 대화 적용 `{revision: 13}`·되돌리기 `{revision: 14}` 목과 함께 쓴다.

## 값을 채운 규칙

- 기준 계획(A-1): 목표 2,000만원(`HOUSE`), 보유 500만원, 월 30만원, 36개월, 금리 미입력(기본 3.12%), 비중 미입력(5:5), 시작월 2026-11. `result`·`display`는 plan-context 부록 A-1과 같고 `calcRunId`는 540이다. ETF 0%(541)는 A-2, 목표 초과(542)는 A-3이다. `get-plan.200-exceeded`는 `rev 12`다(시퀀스 안에서만 `rev 13`).
- `monthlyPoints`(목 전용 규칙): 시나리오마다 `targetPeriodMonths + 1`점이고 `month`는 시작월 + k(k = 0 … N)다. `afterTaxAsset = heldAssets + monthlySavingAmount × k + round(afterTaxGain × k / N)`라 첫 점은 보유 자산, 마지막 점은 시나리오 값이다. `principal` 키는 없다.
- 대화 적용 뒤(`rev 13 / 553`)의 숫자는 `docs/api/examples/internal-calc/`의 `changed` 쪽이다. 입력 수정(`rev 15 / 555`)의 수치와 대화 목의 과거 메시지 일부(1025~1029)는 화면 시험용으로 이 목에서만 정한 값이다. 실패·범위 밖 문구는 문구표(`copy/copy.ko.yml`)와 같다.
- F-4 ETF 0%(`1033`)의 `current`는 plan-context A-2다. `changed`·`diff`·`display`(월 50만원, 적금 100%)는 원문에 없어 A-2의 이득을 월 납입 비율로 늘려 이 목에서 정했다(화면 시험용).
- `plan/get-plan.200-after-apply.json`(`get-plan.seq-full-flow`의 3번째 응답, `rev 13`과 같은 본문)의 BAD 시나리오는 `monthlyPoints`가 목표선을 넘는 달(2029-05)과 `goalReachMonth`(2029-04)가 1달 다르다. `goalReachMonth`는 internal-calc 값이고 점은 계약이 정한 목 전용 선형 공식으로 만들기 때문이다(그래프에 월 라벨이 없어 화면 영향은 작다).
- F-4 정상은 `internal-calc` 성공 예시에서 `calcRunId`를 지우고 `messageId`·`applicable`·`changedKeys`를 더한 것이다.
- 설명 메시지의 `citations`는 `docs/rag-sources.md`의 선정 목록(서로 다른 운용사 2곳)을 쓴다.

## 검증

```bash
node --test frontend/src/mocks
```

의존성 없이 돈다(Node 내장 테스트). 파일 개수·이름, 응답 형식, 오류 본문 형식, 계획 숫자(A-1~A-3), `monthlyPoints` 규칙, 계약 문서와의 이름 일치를 확인한다.
