# 결과 화면 ETF 종목 비교 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 결과 화면 그래프 아래에서 ETF 종목을 골라, 저장 없이 현재 종목과 비교해 보고 3구간 산정 문구가 그 종목을 따라가게 한다.

**Architecture:** 새 컴포넌트 `EtfCompareSection`(`features/compare/`)이 `GET /api/etf-products`로 종목을 받고 `POST /api/compare`로 비교한다. 비교 결과는 컴포넌트 상태에만 두고 `planStore`는 건드리지 않는다. 숫자는 모두 서버 `display` 문자열이고, 종목 이름만 응답 본문의 `assumptions.etfProductName`에서 읽는다. 문구표에서 지수 이름을 떼어내 조립용 키로 바꾼다.

**Tech Stack:** React 19, TypeScript strict, Tailwind 4, Vitest + Testing Library, `node --test`(목 계약)

**Spec:** `docs/superpowers/specs/2026-11-23-result-etf-compare-design.md`

**Issue/브랜치:** #156. `develop`에서 `feature/156-result-etf-compare`.

## Global Constraints

- 금액·수익률·기간을 프론트에서 계산하거나 조립하지 않는다. 숫자는 서버 `display` 문자열 그대로(NFR-INT-01, `frontend/CLAUDE.md`).
- `any` 금지. TypeScript strict.
- ETF는 **화면 이름**("코스피200 추종 ETF")만 쓴다. 운용사 상품명 금지(CP-06).
- 문구에 '추천' 표현을 긍정형으로 쓰지 않는다(CP-06).
- 종목 목록은 서버가 준 것만 쓴다. 화면에 종목 코드·이름을 하드코딩하지 않는다(#156).
- 문구는 `copy/copy.ko.yml`(원본)과 `frontend/src/shared/copy/copy.ts`(사본)를 **같이** 고친다.
- 비교·선택만으로 내 계획(`planStore`의 `revision`)이 바뀌어서는 안 된다(FR-CMP-02).
- 커밋 메시지는 Conventional Commits + `(#156)`.
- 완료 조건: `cd frontend && npm run lint`, `npx tsc -b --noEmit`, `npm test` 모두 통과. 목을 건드린 과제는 `node --test frontend/src/mocks/mocks.test.mjs`도 통과(폴더 인자로는 Node 24에서 0개가 돈다).
- `frontend/src/mocks/chat/get-comparison.200*.json`의 `assumptions` 누락은 #222가 고치는 **기존 실패**다. 이 계획은 그 파일을 고치지 않고, 새 목에는 `assumptions`를 처음부터 넣는다.

## Review Focus

- **종목 목록을 두 번 가져온다**: 영역을 닫고 다시 열거나 다시 그려지면 `GET /api/etf-products`가 또 나간다. 한 번만 나가야 한다. → Task 5
- **늦은 응답이 최신 선택을 덮어쓴다**: 종목을 빠르게 두 번 고르면 먼저 보낸 비교의 늦은 응답이 나중 선택의 표를 덮어쓸 수 있다. 마지막으로 고른 종목의 결과만 보여야 한다. → Task 5
- **계획이 바뀐 뒤 낡은 비교표가 남는다**: 대화 적용·되돌리기로 `revision`이 바뀌면 표의 '현재' 열이 더 이상 내 계획이 아니다. 표를 지워야 한다. → Task 5
- **종목 이름이 없을 때 문구가 깨진다**: `display["assumptions.etfProductName"]`이 없으면 `기준 종목 ` 뒤가 비거나 ` · `만 남을 수 있다. 뒤 조각만 보여야 한다. → Task 1
- **목록에 내 종목 하나뿐**: 칩이 하나만 있고 고를 것이 없는데 "비교해 보기"만 보인다. 안내가 필요하다. → Task 5

---

### Task 1: 3구간 머리글에서 지수 이름 떼어내기

**Files:**
- Modify: `copy/copy.ko.yml:227-230`
- Modify: `frontend/src/shared/copy/copy.ts:33-35`
- Modify: `frontend/src/features/result/ScenarioCards.tsx:14-17,26`
- Test: `frontend/src/features/result/__tests__/scenarioCards.test.tsx` (새로)

**Interfaces:**
- Consumes: 없음
- Produces: `ScenarioCards`의 새 prop `productName?: string`. 문구표 키 `result.scenarioMethodProduct`(`기준 종목 {product}`). `result.scenarioMethodShort.CAGR`·`.ROLLING_DISTRIBUTION`에서 `기준 지수 S&P500 · ` 접두가 빠진 문장.

- [ ] **Step 1: Write the failing test**

```tsx
// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { ScenarioCards } from "../ScenarioCards";

const scenarios = [
  { scenario: "AVG" as const, afterTaxAsset: 17_120_000, principal: 15_800_000, afterTaxGain: 1_320_000,
    gapToGoal: -2_880_000, isAchieved: false, goalReachMonth: null, monthlyPoints: [] },
];
const display = { "AVG.afterTaxAsset": "약 1,712만원", "AVG.gapToGoal": "-약 288만원 미달" };

describe("3구간 머리글 둘째 줄 (#156)", () => {
  afterEach(cleanup);

  it("종목 이름이 있으면 '기준 종목 <이름> · <산정 방식>'으로 보인다", () => {
    render(<ScenarioCards scenarios={scenarios} display={display}
      scenarioMethod="ROLLING_DISTRIBUTION" productName="코스피200 추종 ETF" />);

    expect(screen.getByText("기준 종목 코스피200 추종 ETF · 과거 10년 수익률로 1만 가지 경우를 계산해 세 구간 (가정 보기 참고)")).toBeTruthy();
  });

  it("종목 이름이 없으면 산정 방식만 보인다", () => {
    render(<ScenarioCards scenarios={scenarios} display={display} scenarioMethod="CAGR" />);

    expect(screen.getByText("과거 데이터의 연평균 수익률로 세 구간을 나눠 계산 (가정 보기 참고)")).toBeTruthy();
    expect(screen.queryByText(/기준 종목/)).toBeNull();
    expect(screen.queryByText(/S&P500/)).toBeNull();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/result/__tests__/scenarioCards.test.tsx`
Expected: FAIL — `productName` prop이 없고 문구에 `기준 지수 S&P500`이 남아 있다.

- [ ] **Step 3: 문구표 두 곳을 고친다**

| 키 | 값 |
|---|---|
| `result.scenarioMethodProduct` (새로) | `기준 종목 {product}` |
| `result.scenarioMethodShort.CAGR` | `과거 데이터의 연평균 수익률로 세 구간을 나눠 계산 (가정 보기 참고)` |
| `result.scenarioMethodShort.ROLLING_DISTRIBUTION` | `과거 10년 수익률로 1만 가지 경우를 계산해 세 구간 (가정 보기 참고)` |

`copy.ko.yml`의 주석(`# W-06 와이어프레임: 3구간 머리글 둘째 줄`)에 종목 이름은 `assumptions.etfProductName`에서 조립한다는 한 줄을 더한다.

- [ ] **Step 4: `ScenarioCards`에 `productName?: string`을 더하고 둘째 줄을 조립한다**

`result.scenarioMethodProduct`의 `{product}`를 `productName`으로 바꾼 조각과 `result.scenarioMethodShort.<CODE>` 조각을 ` · `로 잇는다. `productName`이 없거나 빈 문자열이면 뒤 조각만, `scenarioMethod`가 없거나 모르는 코드면 지금처럼 줄 자체를 숨긴다.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/result`
Expected: PASS (기존 `result.test.tsx`의 머리글 단정도 함께 통과해야 한다. `기준 지수 S&P500`을 기대하는 단정이 있으면 새 문구로 고친다)

- [ ] **Step 6: Commit**

```bash
git add copy/copy.ko.yml frontend/src/shared/copy/copy.ts frontend/src/features/result/ScenarioCards.tsx frontend/src/features/result/__tests__/scenarioCards.test.tsx
git commit -m "feat(result): 3구간 머리글 둘째 줄의 종목 이름을 문구에서 떼어낸다 (#156)"
```

---

### Task 2: 가정 보기에 ETF 종목·세율 줄

**Files:**
- Modify: `frontend/src/features/result/AssumptionsSection.tsx:19-40`
- Test: `frontend/src/features/result/__tests__/assumptions.test.tsx` (새로)

**Interfaces:**
- Consumes: 없음
- Produces: 없음(화면 전용)

- [ ] **Step 1: Write the failing test**

`AssumptionsSection`을 `display`만 바꿔 세 번 그린다.

```tsx
it("종목 이름과 매매차익 세율을 display 문자열 그대로 보여 준다", () => {
  renderAssumptions({ "assumptions.etfProductName": "코스피200 추종 ETF",
    "assumptions.etfGainTaxRatePct": "0%(국내 주식형 ETF 매매차익 비과세)" });

  const section = screen.getByLabelText("계산 가정");
  expect(section.textContent).toContain("ETF 종목");
  expect(section.textContent).toContain("코스피200 추종 ETF");
  expect(section.textContent).toContain("0%(국내 주식형 ETF 매매차익 비과세)");
});

it("ETF 0%면 종목·세율 줄을 생략한다", () => {
  renderAssumptions({});

  expect(screen.getByLabelText("계산 가정").textContent).not.toContain("ETF 종목");
});

it("기준일 라벨은 종목과 무관한 'ETF 기준일'이다", () => {
  renderAssumptions({ "assumptions.sp500BaseDate": "2026년 10월" });

  const section = screen.getByLabelText("계산 가정");
  expect(section.textContent).toContain("ETF 기준일");
  expect(section.textContent).not.toContain("S&P500 기준일");
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/result/__tests__/assumptions.test.tsx`
Expected: FAIL — 종목 줄이 없고 라벨이 `S&P500 기준일`이다.

- [ ] **Step 3: `AssumptionsSection`의 `dl`에 두 줄을 더하고 기준일 라벨을 고친다**

`display["assumptions.etfProductName"]`이 있을 때만 `ETF 종목` 줄, `display["assumptions.etfGainTaxRatePct"]`가 있을 때만 `ETF 매매차익 세율` 줄. 기존 `S&P500 기준일` 라벨 → `ETF 기준일`(값 키 `assumptions.sp500BaseDate`는 그대로). 세금 안내(`taxNoteFor`)는 건드리지 않는다.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/result`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/result/AssumptionsSection.tsx frontend/src/features/result/__tests__/assumptions.test.tsx
git commit -m "feat(result): 가정 보기에 ETF 종목과 매매차익 세율 줄 (#156)"
```

---

### Task 3: 목과 전송 계층

**Files:**
- Create: `frontend/src/mocks/chat/post-compare.200-etf.json`
- Create: `frontend/src/mocks/chat/post-compare.422-not-available.json`
- Create: `frontend/src/features/compare/requestEtfComparison.ts`
- Modify: `frontend/src/api/mock.ts:24-56` (`KEY_BY_PREFIX`, `DEFAULT_FILE_BY_KEY`)
- Modify: `frontend/src/mocks/mocks.test.mjs:12-52`(`CHAT_FILES`), `:119-127`(개수), `:219-256`(`ERROR_CODES`)
- Modify: `docs/api/frontend-api.md:1510-1519`(목 표)
- Test: `frontend/src/features/compare/__tests__/requestEtfComparison.test.ts` (새로)

**Interfaces:**
- Consumes: 없음
- Produces:
  - `export interface EtfProduct { code: string; displayName: string; gainTaxRatePct: number }`
  - `export type EtfProductsResult = { ok: true; products: EtfProduct[] } | { ok: false; message: string }`
  - `export async function fetchEtfProducts(deps: ComparisonRequestDeps): Promise<EtfProductsResult>`
  - `export async function requestEtfComparison(deps: ComparisonRequestDeps, code: string): Promise<ComparisonResult>`
  - `export interface EtfCompareDeps { mode: "mock" | "live"; request(method: string, path: string, body?: unknown): Promise<unknown> }` — 새 파일에 따로 둔다. 기존 `requestComparison.ts`의 `ComparisonRequestDeps`는 손대지 않는다(`ApiClient.request`는 이미 세 번째 인자를 받는다).

**목 본문**: `chat/get-comparison.200.json`을 복사해 `messageId`를 빼고, `changedKeys`를 `["etfProductCode"]`로, `conditions`의 다섯 조건은 current·changed 같은 값으로, `current.assumptions`에 `etfProductCode: "SP500"`·`etfProductName: "S&P500 추종 ETF"`·`etfGainTaxRatePct: 15.4`, `changed.assumptions`에 `"069500"`·`"코스피200 추종 ETF"`·`0`을 넣는다. `changed`의 금액 display는 current보다 조금 낮은 값으로 손으로 적고(`diff.*Delta`는 `감소` 문구) 세 시나리오 모두 미달로 둔다. 422 목은 `{type, title, status: 422, instance: "/api/compare", code: "VALIDATION_FAILED", errors: [{field: "etfProductCode", reason: "NOT_AVAILABLE"}]}`.

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it, vi } from "vitest";
import productsMock from "../../../mocks/refdata/get-etf-products.200.json";
import compareMock from "../../../mocks/chat/post-compare.200-etf.json";
import { fetchEtfProducts, requestEtfComparison } from "../requestEtfComparison";

function deps(impl: (method: string, path: string, body?: unknown) => Promise<unknown>) {
  return { mode: "live" as const, request: vi.fn(impl) };
}

it("종목 목록을 GET /api/etf-products에서 가져온다", async () => {
  const d = deps(async () => productsMock.responses[0].body);
  const result = await fetchEtfProducts(d);

  expect(d.request).toHaveBeenCalledWith("GET", "/api/etf-products");
  expect(result.ok && result.products[0].displayName).toBe("S&P500 추종 ETF");
});

it("비교는 POST /api/compare에 conditionChanges.etfProductCode로 보낸다", async () => {
  const d = deps(async () => compareMock.responses[0].body);
  const result = await requestEtfComparison(d, "069500");

  expect(d.request).toHaveBeenCalledWith("POST", "/api/compare", { conditionChanges: { etfProductCode: "069500" } });
  expect(result.ok).toBe(true);
});

it("422 NOT_AVAILABLE이면 그 종목을 계산할 수 없다는 문구를 돌려준다", async () => { /* ApiError 던지는 request */ });
it("그 밖의 실패면 comparison.loadFailed 문구를 돌려준다", async () => { /* … */ });
```

422 판별은 `ApiError`의 `code === "VALIDATION_FAILED"`와 `errors[0].field === "etfProductCode"`로 한다(`api/errors.ts`의 모양을 먼저 확인하고 그대로 쓴다).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/compare/__tests__/requestEtfComparison.test.ts`
Expected: FAIL — 모듈이 없다.

- [ ] **Step 3: 목 두 개를 만들고 목 표·계약 문서·목 테스트 목록에 더한다**

`api/mock.ts`: `KEY_BY_PREFIX`에 `"post-compare": "POST /api/compare"`, `DEFAULT_FILE_BY_KEY`에 `"POST /api/compare": "post-compare.200-etf.json"`. `mocks.test.mjs`: `CHAT_FILES`에 두 이름을 더하고 개수 단정 `38` → `40`(테스트 제목의 숫자도 같이), `ERROR_CODES`에 `"chat/post-compare.422-not-available.json": [422, "VALIDATION_FAILED"]`. `frontend-api.md`의 F-4 목 표 아래에 두 줄(설명: C12a-9-3 ETF 종목 미리보기).

- [ ] **Step 4: `requestEtfComparison.ts`를 구현한다**

`fetchEtfProducts`는 `GET /api/etf-products`, `requestEtfComparison`은 `POST /api/compare`. 실패 문구는 문구표 키(`etfCompare.listFailed`, `etfCompare.notAvailable`, `comparison.loadFailed`)에서 가져온다. 기존 `requestComparison.ts`는 고치지 않는다.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/compare && node --test src/mocks/mocks.test.mjs`
Expected: vitest PASS. `node --test`는 기존 실패(#222, F-4 `get-comparison` `assumptions`) 하나만 남고 새 목·개수·오류 표 테스트는 통과.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/mocks/chat/post-compare.200-etf.json frontend/src/mocks/chat/post-compare.422-not-available.json frontend/src/mocks/mocks.test.mjs frontend/src/api/mock.ts frontend/src/features/compare/requestEtfComparison.ts frontend/src/features/compare/__tests__/requestEtfComparison.test.ts docs/api/frontend-api.md
git commit -m "feat(compare): ETF 종목 목록·비교 전송 계층과 목 (#156)"
```

---

### Task 4: 비교표에 ETF 종목 줄

**Files:**
- Modify: `frontend/src/features/compare/types.ts`
- Modify: `frontend/src/features/compare/CompareTable.tsx:4-32`
- Modify: `frontend/src/shared/copy/copy.ts`, `copy/copy.ko.yml` (`condition.etfProductCode`)
- Test: `frontend/src/features/compare/__tests__/compare.test.tsx` (기존 파일에 추가)

**Interfaces:**
- Consumes: Task 3의 목 `post-compare.200-etf.json`
- Produces:
  - `ConditionChanges`에 `etfProductCode?: string`을 더한다(숫자 조건 다섯 개는 그대로).
  - `ComparisonView`에 `current`·`changed`의 `assumptions: { etfProductName?: string | null }`을 더한다(필요한 칸만. 나머지 칸은 쓰지 않으므로 선언하지 않는다).
  - `changedKeys`의 요소 타입은 `ConditionKey | "etfProductCode"`.

- [ ] **Step 1: Write the failing test**

```tsx
it("종목을 바꾼 비교면 표 첫 줄이 'ETF 종목'이고 현재·바꾼 종목 이름이 보인다", () => {
  render(<CompareTable view={etfView} />);

  const row = screen.getByRole("rowheader", { name: "ETF 종목" }).closest("tr") as HTMLElement;
  expect(row.textContent).toContain("S&P500 추종 ETF");
  expect(row.textContent).toContain("코스피200 추종 ETF");
});
```

`etfView`는 `post-compare.200-etf.json`의 본문을 `ComparisonView`로 읽은 것.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/compare/__tests__/compare.test.tsx`
Expected: FAIL — `etfProductCode`에 라벨이 없어 줄이 안 나오거나 타입이 맞지 않는다.

- [ ] **Step 3: 타입을 넓히고 표에 줄을 더한다**

`CONDITION_LABEL_KEY`에 `etfProductCode: "condition.etfProductCode"`를 더하고, 그 키의 `current`·`changed` 칸만 `view.current.assumptions?.etfProductName`·`view.changed.assumptions?.etfProductName`에서 읽는다(나머지 조건은 지금처럼 `display`에서). `change` 칸은 `undefined`(`—`). 문구표에 `condition.etfProductCode: "ETF 종목"`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/compare && npx tsc -b --noEmit`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/compare/types.ts frontend/src/features/compare/CompareTable.tsx frontend/src/features/compare/__tests__/compare.test.tsx frontend/src/shared/copy/copy.ts copy/copy.ko.yml
git commit -m "feat(compare): 비교표에 ETF 종목 줄 (#156)"
```

---

### Task 5: ETF 비교 영역

**Files:**
- Create: `frontend/src/features/compare/EtfCompareSection.tsx`
- Modify: `frontend/src/features/compare/index.ts`
- Modify: `frontend/src/shared/copy/copy.ts`, `copy/copy.ko.yml` (§7 문구 10개)
- Test: `frontend/src/features/compare/__tests__/etfCompare.test.tsx` (새로)

**Interfaces:**
- Consumes: Task 3 `fetchEtfProducts`·`requestEtfComparison`, Task 4 `CompareTable`·`ComparisonView`, Task 1 머리글 조립(같은 두 문구표 키를 비교 영역에서도 쓴다)
- Produces:

```ts
interface EtfCompareSectionProps {
  /** 내 계획의 종목 이름. display["assumptions.etfProductName"]. 없으면 영역을 그리지 않는다 */
  currentProductName: string;
  /** 3구간 산정 방식 코드. 비교 영역의 산정 문구에 쓴다 */
  scenarioMethod: string | null;
  /** 계획 revision. 바뀌면 비교 결과를 지운다 */
  revision: number | null;
}
export function EtfCompareSection(props: EtfCompareSectionProps): ReactElement | null;
```

**문구**(`etfCompare.*` 10개와 값은 설계 §7 표 그대로 옮긴다)

- [ ] **Step 1: Write the failing test**

```tsx
// 공통: fetchEtfProducts/requestEtfComparison을 vi.mock으로 세우고 products는 목 3개를 쓴다.

it("버튼을 누르면 서버가 준 종목이 모두 보이고 지금 종목이 선택돼 있다", async () => {});
it("다른 종목을 고르면 비교표가 보인다", async () => {});
it("종목을 고르면 산정 문구가 고른 종목 이름으로 바뀐다", async () => {});
it("지금 종목을 다시 고르면 표가 사라지고 같은 종목 안내가 보인다", async () => {});
it("영역을 닫고 다시 열어도 종목 목록은 한 번만 가져온다", async () => {});
it("종목을 연달아 고르면 마지막으로 고른 종목의 표만 남는다", async () => {});
it("revision이 바뀌면 비교표를 지운다", async () => {});
it("목록 호출이 실패하면 안내와 다시 시도 버튼이 보인다", async () => {});
it("비교가 422 NOT_AVAILABLE이면 안내가 role=alert로 보이고 표는 없다", async () => {});
it("종목이 하나뿐이면 비교할 종목이 없다는 안내가 보인다", async () => {});
it("currentProductName이 빈 문자열이면 아무것도 그리지 않는다", () => {});
```

각 테스트의 단정은 설계 §5·§7의 문구와 `CompareTable` 출력으로 쓴다. "마지막으로 고른 종목의 표만 남는다"는 첫 요청의 promise를 늦게 resolve해 확인한다.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/compare/__tests__/etfCompare.test.tsx`
Expected: FAIL — 컴포넌트가 없다.

- [ ] **Step 3: `EtfCompareSection`을 구현한다**

- `currentProductName`이 비면 `null`을 돌려준다.
- 닫힘: `etfCompare.current`(`{product}` 치환)와 `etfCompare.open` 버튼.
- 처음 열 때만 `fetchEtfProducts`를 부른다(이미 가져왔으면 다시 부르지 않는다).
- 종목은 `shared/ui/ChipGroup`으로 그리고 초깃값은 내 계획의 종목(`displayName`이 `currentProductName`과 같은 항목).
- 다른 종목을 고르면 `requestEtfComparison`. 요청마다 세대 번호를 올려, 늦게 온 응답은 세대가 다르면 버린다(`UndoToast.tsx`의 `generation` 패턴과 같다).
- 지금 종목을 다시 고르면 요청하지 않고 결과를 지우며 `etfCompare.sameProduct`.
- `revision`이 바뀌면 결과·오류를 지운다.
- 비교표 위 한 줄: Task 1과 같은 두 문구표 키로 **고른 종목 이름**으로 조립한다. 세율은 보여 주지 않는다(설계 §5).
- 오류는 설계 §7 표대로. 목록 실패는 다시 시도 버튼.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/features/compare`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/compare/EtfCompareSection.tsx frontend/src/features/compare/index.ts frontend/src/features/compare/__tests__/etfCompare.test.tsx frontend/src/shared/copy/copy.ts copy/copy.ko.yml
git commit -m "feat(compare): 결과 화면 ETF 종목 비교 영역 (#156)"
```

---

### Task 6: 결과 화면에 붙이기

**Files:**
- Modify: `frontend/src/features/result/ResultPage.tsx:155-175`
- Test: `frontend/src/features/result/__tests__/result.test.tsx` (기존 파일에 추가)

**Interfaces:**
- Consumes: Task 5 `EtfCompareSection`, Task 1 `ScenarioCards`의 `productName`
- Produces: 없음

- [ ] **Step 1: Write the failing test**

`get-plan.200.json` 본문을 `structuredClone`해 `display["assumptions.etfProductName"] = "S&P500 추종 ETF"`를 넣고 그린다(기존 `withoutDisplayKeys` 헬퍼의 반대).

```tsx
it("그래프 바로 다음에 ETF 비교 영역이 온다", async () => {});
it("3구간 머리글이 display의 종목 이름을 쓴다", async () => {});
it("ETF 0% 계획이면 ETF 비교 영역이 없다", async () => {});  // get-plan.200-etf0.json
```

"그래프 바로 다음"은 `document.querySelectorAll("section[aria-label]")`의 순서로 확인한다.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/features/result/__tests__/result.test.tsx`
Expected: FAIL — ETF 비교 영역이 없다.

- [ ] **Step 3: `ResultPage`에서 `ResultChart` 바로 아래에 영역을 넣는다**

`<EtfCompareSection currentProductName={display["assumptions.etfProductName"] ?? ""} scenarioMethod={result.assumptions.scenarioMethod} revision={view.revision} />`. `ScenarioCards`에는 같은 `display` 값을 `productName`으로 넘긴다. `planStore`의 `revision` 필드 이름은 `api/planStore/index.ts`의 `PlanState`를 보고 맞춘다.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm run lint && npx tsc -b --noEmit && npm test`
Expected: 모두 PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/result/ResultPage.tsx frontend/src/features/result/__tests__/result.test.tsx
git commit -m "feat(result): 그래프 아래에 ETF 종목 비교 영역을 붙인다 (#156)"
```

---

## 끝낸 뒤

- `gh pr create --base develop`. 본문은 `.github/pull_request_template.md` 형식, `Part of #156`(2단계의 적용은 남는다).
- "범위 밖 변경"에 적을 것: `features/compare/**`(#156 paths 밖. 비교표·타입 재사용), `copy/copy.ko.yml`, `docs/api/frontend-api.md`(목 표), `frontend/src/mocks/mocks.test.mjs`(목 목록·개수).
- PR 본문에 남길 가정: 적용(저장)은 #68 확인 흐름 뒤로 미뤘다. 고른 종목의 세율은 비교 응답 display에 없어 보여 주지 않는다. `get-plan` 목·`docs/api/examples/**`에 ETF 세 칸을 반영하는 일(#156 1단계 셋째 기준)은 `mocks.test.mjs`의 A-1 인라인 표까지 함께 고쳐야 해서 이 PR에 넣지 않았다.
