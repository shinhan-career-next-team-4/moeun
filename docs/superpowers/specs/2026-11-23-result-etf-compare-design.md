# 결과 화면 ETF 종목 비교 설계

- 작성일: 2026-11-23
- Issue: #156 2단계(결과 화면 종목 바꾸기)
- 영역: `frontend/` (+ 공용 문구표 `copy/copy.ko.yml`)
- 근거: FR-RES-03, FR-RES-06, FR-CMP-02, CP-06, NFR-INT-01, D-067, #150·#154·#220

## 1. 목적과 왜

결과 화면 그래프 아래에서 **ETF 종목을 바꿔 보면 결과가 어떻게 달라지는지** 볼 수 있게 한다.

지금 화면은 모든 계획이 S&P500 추종 ETF라는 전제로 굳어 있다. 백엔드는 이미 종목을 3개(SP500, 069500, 133690) 적재하고, 종목 목록 API(#220)와 종목을 바꾼 비교(#154)까지 내려주는데 화면에 입구가 없다. 그래서 사용자는 "다른 종목이면 어떻게 되는데?"를 물어볼 곳이 없고, 3구간 머리글은 종목과 무관하게 `기준 지수 S&P500`으로 고정돼 있어 종목을 바꾸면 곧 틀린 문구가 된다(#156 댓글).

## 2. 목표 / 비목표 / 실패 조건

**목표**
- 그래프(`ResultChart`) 바로 아래에 ETF 종목 비교 입구(버튼)를 둔다.
- 고를 수 있는 종목은 서버(`GET /api/etf-products`)가 준 것만 쓴다. 화면에 종목을 하드코딩하지 않는다.
- 종목을 고르면 저장 없이 **현재 종목 vs 고른 종목** 비교를 보여 준다(`POST /api/compare`).
- 3구간 머리글의 종목 이름을 고정 문구에서 빼고, 계획의 종목(그리고 비교에서 고른 종목)을 따라가게 한다.

- 고른 종목을 확인창을 거쳐 내 계획에 적용하면 화면의 ETF 기준 값(그래프·3구간·요약·가정 보기)이 모두 그 종목 값이 된다. 되돌리기로 직전 종목으로 돌아간다.
  - **2026-11-23 범위 변경**: 처음에는 비목표였다(#68의 적용 확인창 프론트가 없었다). 작업 중 #68이 develop에 들어왔고(339c937 `ConfirmApplyDialog`·`applyComparison`), 사람이 "나스닥으로 고르면 화면에 모든 ETF 기준 값을 나스닥으로 바꿔 달라"고 해서 목표로 옮겼다. 미리보기만으로는 그래프(비교 응답에 월별 점이 없다)와 가정 보기(비교 display에 `assumptions.*`가 없다)를 바꿀 수 없어, 저장 뒤 계획을 다시 읽는 길만 화면 전체를 일관되게 만든다.

**비목표**
- 종목 2~3개를 한 표에 나란히 놓기. 종목마다 비교 요청을 따로 보내야 하고, 종목을 골라 주는 모양이 되어 추천 금지 원칙(CP-06)에 걸릴 소지가 있다.
- 입력 화면에서 종목 고르기(#156 1번: 입력은 대표 상품으로 계산, 지금과 같다).
- 기존 조건 변경 비교 영역(`CompareSection`, #65)의 동작 변경.

**실패 조건**(동작해도 실패로 본다)
- 화면이 금액·수익률을 계산하거나 조립한다(NFR-INT-01). 숫자는 서버 display 문자열만 쓴다.
- 종목을 고르는 것만으로 내 계획이 바뀐다(FR-CMP-02).
- 상품명(운용사 상품 이름)이 화면에 나온다. 화면 이름("코스피200 추종 ETF")만 쓴다(CP-06).
- 문구에 '추천' 표현이 긍정형으로 들어간다(CP-06).
- ETF 비중이 0%인 계획에서 종목 비교가 보인다(고를 종목이 없다).

> **2026-11-23 화면 변경(구현 후 사용자 요청. 최종 동작이며, §5·§7의 칩·미리보기·열기 단계 설명은 이 메모로 대체된다)**
> 1. 열기 단계 없이 화면이 뜨면 서버가 준 종목 3개를 **카드(3안)** 로 바로 보여 준다. 칩 선택은 없다. 지금 종목이 아닌 종목은 `POST /api/compare`를 종목마다 보내 카드에 평균 자산·지금 종목 대비·목표 대비·나빴던 때를 채운다(요청 = 종목 수 - 1). §2가 비목표로 둔 "여러 종목 나란히"를 사람이 요청해 목표로 옮겼다.
> 2. 카드를 누르면 **확인창·'바꾸기' 버튼 없이 바로 적용**한다(P-6 `COMPARE`). 계약 C12a-9-2·FR-PLAN-01·D-016의 "확인창을 거친다"와 다르며, 이 예외는 계약 C12a-9-3에 반영했다. 되돌리기 토스트(10초)와 '직전 계획으로 되돌리기'가 안전망이다. 비교 영역·대안 카드의 적용은 확인창을 그대로 쓴다.
> 3. 적용하면 계획을 다시 읽어 화면 전체가 새 종목 기준이 되고, 바뀐 종목이 지금 종목이 되도록 나머지 두 종목 카드를 다시 받는다. 적용 뒤의 별도 비교표("바뀐 조건 | 이전 조건")는 두지 않는다.
> 4. 칩·250ms 디바운스·'같은 종목' 안내·열기/닫기는 없다. 3구간 머리글 문구(§6)와 가정 보기(§8)는 그대로다.
> 5. 계획 목·`docs/api/examples/**`에 ETF 세 칸을 반영하는 일(#156 1단계 마지막 기준)은 하지 않는다. 이후 확인은 실서버에서만 한다(사용자 결정, 2026-11-23).

## 3. 제약

- 프론트: React 19, TypeScript strict(`any` 금지), Tailwind 4, Vitest. 숫자는 서버 display 그대로(`frontend/CLAUDE.md`).
- 비교 응답의 display에는 `assumptions.*` 키가 없다. `ResultDisplayBuilder`가 `resultPrefix`가 빈 문자열일 때만 가정 키를 만든다(계약 C2-6). 종목 이름은 응답 본문 `current.assumptions.etfProductName`·`changed.assumptions.etfProductName`에서 읽는다. 이름은 계산값이 아니라 표시 이름이라 NFR-INT-01에 걸리지 않는다.
- `POST /api/compare`의 조건 중 `etfProductCode`만 문자열이다. 적재하지 않은 종목은 422 `VALIDATION_FAILED` / field `etfProductCode` / reason `NOT_AVAILABLE`.
- 문구표는 `copy/copy.ko.yml`이 원본이고 `frontend/src/shared/copy/copy.ts`가 사본이다. 둘을 같이 고친다.
- 작업 규칙: #156으로 진행하고 `develop`에서 `feature/156-result-etf-compare`를 딴다(지금 브랜치 `feature/55-connect-real-api`는 커밋이 없어 버릴 것이 없다).

## 4. 접근 방식

고려한 세 가지.

1. **종목 하나를 골라 미리보기** — 버튼으로 종목 목록을 열고, 하나 고르면 기존 비교표(`CompareTable`)로 현재 종목과 나란히 본다. #156 수용 기준과 `POST /api/compare` 계약에 그대로 맞고, 비교 영역(#65)·대안 카드(#67)와 흐름이 같아 사용자가 새로 배울 것이 없다. 한 번에 두 종목만 견준다는 점이 약점.
2. **종목 전부를 한 표에 나란히** — 보기엔 시원하지만 종목마다 비교를 따로 요청해야 하고(N번 호출), 결과를 줄 세워 보여 주는 모양이 추천 금지(CP-06)에 가깝다. 비목표로 옮겼다.
3. **기존 비교 영역에 조건 하나로 끼워넣기** — `CompareSection`의 조건 체크박스에 '종목'을 더하는 방법. 코드는 가장 적게 들지만 그래프 아래가 아니라 화면 한참 밑이고, 다섯 조건은 숫자 입력칸인데 종목만 목록이라 영역 안에서 모양이 어긋난다.

**선택: 1번.** 사람이 "버튼 누르면 (문구도) 같이 바뀌게"라고 한 흐름과 같고, #156 수용 기준을 그대로 테스트로 옮길 수 있다.

## 5. 화면

그래프 아래에 접힌 상태로 들어간다. ETF 비중이 0%인 계획(= `display["assumptions.etfProductName"]`이 없음)에서는 영역 자체를 그리지 않는다.

**닫힘**

```
┌────────────────────────────────────────────┐
│ 지금 종목: S&P500 추종 ETF                 │
│ [ 다른 ETF 종목으로 비교해 보기 ]          │
└────────────────────────────────────────────┘
```

**열림 → 종목 고름**

```
┌────────────────────────────────────────────┐
│ 다른 ETF 종목으로 비교                     │
│ 고른 종목으로 계산만 해 봐요. 내 계획은    │
│ 그대로예요                                 │
│                                            │
│ ( S&P500 추종 ETF ) (•코스피200 추종 ETF)  │
│ ( 나스닥100 추종 ETF )                     │
│                                            │
│ 기준 종목 코스피200 추종 ETF · 과거 데이   │
│ 터의 연평균 수익률로 세 구간을 나눠 계산   │
│                                            │
│ 항목        현재    바꾼 종목    변화      │
│ ─────────────────────────────────────────  │
│ ETF 종목    S&P500  코스피200     —        │
│             추종ETF 추종ETF                │
│ 평균 예상   1,712   1,689만원   23만원 감소│
│ 목표 대비   -288    -311만원    여전히 미달│
│ 나빴던 때   1,598   1,602만원   여전히 미달│
│ 원금        1,580   1,580만원     —        │
│ 도달 시점   못 닿음  못 닿음      —        │
│                                            │
│ [ 닫기 ]                                   │
└────────────────────────────────────────────┘
```

- 종목은 단일 선택 칩(`shared/ui/ChipGroup`, radiogroup·방향키 지원)으로 보여 주고 **지금 종목이 선택된 상태로 열린다**.
- 지금 종목을 다시 고르면 비교할 것이 없으므로 표를 지우고 안내만 둔다(요청하지 않는다).
- 다른 종목을 고르면 바로 비교를 요청한다. 조건 입력칸이 없어 '비교하기' 버튼을 따로 둘 이유가 없다 — 내 결정.
- 비교표는 기존 `CompareTable`을 그대로 쓰고, 맨 위에 'ETF 종목' 줄이 하나 붙는다.
- 표 위 한 줄이 **고른 종목 기준의 3구간 산정 문구**다(§6).
- 고른 종목의 매매차익 세율은 **보여 주지 않는다.** 비교 응답의 display에 `assumptions.*`가 없어서 세율은 숫자로만 오고(`changed.assumptions.etfGainTaxRatePct`, `GET /api/etf-products`의 `gainTaxRatePct`도 숫자), 화면이 `%`를 붙여 조립하면 NFR-INT-01을 깬다. 내 계획 종목의 세율은 가정 보기에 서버 display 문자열로 있다(§8) — 내 결정.

## 6. 3구간 머리글 문구 (고정 해제)

지금은 `result.scenarioMethodShort.<CODE>` 한 문장에 지수 이름이 박혀 있다.

```
기준 지수 S&P500 · 과거 데이터의 연평균 수익률로 세 구간을 나눠 계산 (가정 보기 참고)
```

종목 이름을 문장에서 빼고 조립용 문구를 하나 새로 둔다. 자리표시자 한 개짜리 문장을 쓰면 종목 이름이 없을 때(ETF 0%) 문자열을 잘라내야 해서, 두 조각으로 나누는 쪽이 깔끔하다 — 내 결정.

| 키 | 값 |
|---|---|
| `result.scenarioMethodProduct` (새로) | `기준 종목 {product}` |
| `result.scenarioMethodShort.CAGR` (수정) | `과거 데이터의 연평균 수익률로 세 구간을 나눠 계산 (가정 보기 참고)` |
| `result.scenarioMethodShort.ROLLING_DISTRIBUTION` (수정) | `과거 10년 수익률로 1만 가지 경우를 계산해 세 구간 (가정 보기 참고)` |

`{product}`에는 display 문자열을 그대로 넣는다. 두 조각은 ` · `로 잇고, 종목 이름이 없으면 뒤 조각만 보여 준다.

- 결과 화면 3구간(`ScenarioCards`): `display["assumptions.etfProductName"]` → 내 계획의 종목.
- ETF 비교 영역: 고른 종목의 `changed.assumptions.etfProductName` → **버튼으로 종목을 고르면 이 줄이 같이 바뀐다.**

내 계획의 3구간 머리글은 미리보기 중에도 내 계획의 종목을 그대로 보여 준다. 저장하기 전까지 그 아래 3구간 숫자가 내 계획의 값이라, 머리글만 고른 종목으로 바꾸면 숫자와 어긋난 문구가 된다 — 내 결정.

## 7. API와 상태

| 쓰는 곳 | 호출 |
|---|---|
| 종목 목록 | `GET /api/etf-products` → `{ products: [{ code, displayName, gainTaxRatePct }] }` |
| 비교 | `POST /api/compare` `{ "conditionChanges": { "etfProductCode": "069500" } }` → 비교 응답(F-4에서 `messageId`만 뺀 모양) |

- 목록은 영역을 **처음 열 때 한 번** 가져와 컴포넌트 안에 담아 둔다. 결과 화면이 뜰 때마다 부르지 않는다.
- 비교 결과는 프론트 상태에만 둔다. `planStore`를 건드리지 않는다(FR-CMP-02).
- 새 함수 `requestEtfComparison`을 `features/compare/`에 둔다. 기존 `requestComparison`(#65, 아직 목 전용)은 손대지 않는다. #65가 실서버로 옮겨갈 때 같은 경로를 쓰면 된다.

**오류와 빈 상태**

| 상황 | 화면 |
|---|---|
| 목록 호출 실패 | 영역에 안내 한 줄, 칩 없음, 다시 시도 버튼 |
| 목록이 1개(= 내 종목뿐) | 비교할 종목이 없다는 안내 한 줄 |
| 비교 422 `NOT_AVAILABLE` | "그 종목은 아직 계산할 수 없어요" 안내(`role="alert"`), 표는 지운다 |
| 비교 그 밖의 실패 | 기존 `comparison.loadFailed` 문구 |
| 401 | 기존 클라이언트가 로그인 화면으로 보낸다(그대로) |

**새 문구**(`copy.ko.yml` + 프론트 사본)

| 키 | 값 |
|---|---|
| `etfCompare.open` | `다른 ETF 종목으로 비교해 보기` |
| `etfCompare.title` | `다른 ETF 종목으로 비교` |
| `etfCompare.notice` | `고른 종목으로 계산만 해 봐요. 내 계획은 그대로예요` |
| `etfCompare.current` | `지금 종목: {product}` |
| `etfCompare.pickLabel` | `ETF 종목` |
| `etfCompare.sameProduct` | `지금 계획과 같은 종목이에요. 다른 종목을 골라 보세요` |
| `etfCompare.onlyOne` | `지금은 비교할 다른 종목이 없어요` |
| `etfCompare.notAvailable` | `그 종목은 아직 계산할 수 없어요` |
| `etfCompare.listFailed` | `종목 목록을 불러오지 못했어요. 다시 시도해 주세요` |
| `etfCompare.close` | `닫기` |
| `condition.etfProductCode` | `ETF 종목` |

## 8. 가정 보기 (#156 1단계 마무리)

`AssumptionsSection`이 아직 종목 키를 무시한다. 종목 이름을 화면에 쓰기 시작하는 작업이니 같이 끝낸다 — 내 결정.

- `display["assumptions.etfProductName"]` → `ETF 종목` 줄. 키가 없으면 줄 생략.
- `display["assumptions.etfGainTaxRatePct"]` → `ETF 매매차익 세율` 줄. 키가 없으면 줄 생략.
- `S&P500 기준일` 라벨 → `ETF 기준일`(값은 `assumptions.sp500BaseDate` 그대로. 키 이름은 그대로지만 값은 계산에 쓴 종목의 기준일이다, `docs/api/plan-context.md`).
- 세금 안내는 이미 `taxNoteFor`로 종목 세율을 따른다(그대로).

## 9. 파일

| 파일 | 변경 |
|---|---|
| `frontend/src/features/compare/EtfCompareSection.tsx` | 새로. 버튼·칩·비교표·오류 |
| `frontend/src/features/compare/requestEtfComparison.ts` | 새로. `GET /api/etf-products`, `POST /api/compare` |
| `frontend/src/features/compare/types.ts` | `EtfProduct`, 비교 응답의 `current`·`changed`의 `assumptions` |
| `frontend/src/features/compare/CompareTable.tsx` | `etfProductCode` 줄(이름은 본문 `assumptions`에서) |
| `frontend/src/features/compare/index.ts` | 내보내기 |
| `frontend/src/features/result/ResultPage.tsx` | `ResultChart` 바로 아래에 영역 삽입 |
| `frontend/src/features/result/ScenarioCards.tsx` | 머리글 둘째 줄 조립 |
| `frontend/src/features/result/AssumptionsSection.tsx` | §8 |
| `frontend/src/shared/copy/copy.ts`, `copy/copy.ko.yml` | §6·§7 문구 |
| `frontend/src/api/mock.ts` | `GET /api/etf-products`, `POST /api/compare` 목 키 |
| `frontend/src/mocks/compare/post-compare.200-etf.json` 외 | 새 목(정상·422) |
| 테스트 | 아래 §10 |

`features/compare/**`는 #156의 `paths`(`features/result/**`) 밖이다. 비교표·비교 타입을 재사용해야 해서 그쪽에 둔다. PR 본문 "범위 밖 변경"에 적는다.

## 10. 테스트 (수용 기준 → Vitest)

`features/compare/__tests__/etfCompare.test.tsx`

1. 버튼을 누르면 서버가 준 종목 이름이 모두 보이고, 지금 종목이 선택돼 있다.
2. 다른 종목을 고르면 비교표가 보이고, `planStore`의 계획(revision)은 그대로다.
3. 비교표에 'ETF 종목' 줄이 현재/바꾼 종목 이름으로 보인다.
4. 종목을 고르면 비교 영역의 산정 문구가 고른 종목 이름으로 바뀐다. 결과 화면 3구간 머리글은 내 계획 종목을 유지한다.
5. 지금 종목을 다시 고르면 표가 사라지고 안내가 보인다.
6. 목록 호출 실패 / 비교 422 `NOT_AVAILABLE` 안내.
7. ETF 0% 계획(`get-plan.200-etf0.json`)에서는 영역이 아예 없다.

`features/result/__tests__/result.test.tsx`(기존 보강): 3구간 머리글이 `display["assumptions.etfProductName"]`을 쓰고 'S&P500' 고정 문구가 더 이상 나오지 않는다. 가정 보기에 종목·세율 줄이 보이고, 키가 없으면 생략된다.

목: `node --test frontend/src/mocks/mocks.test.mjs`(폴더 인자로는 Node 24에서 0개가 돈다, #222).

## 11. 남긴 것

- #222(F-4 목에 ETF 3칸)가 머지되기 전이면 비교 목에 `assumptions`가 없다. 새로 만드는 `post-compare` 목에는 처음부터 넣는다.
