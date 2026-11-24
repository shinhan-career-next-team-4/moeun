import type { ReactNode } from "react";
import { COPY } from "../../shared/copy/copy";
import { scenarioMethodLine } from "../../shared/copy/scenarioMethod";
import type { ScenarioCode, ScenarioResult } from "./types";

const SCENARIO_ORDER: readonly ScenarioCode[] = ["GOOD", "AVG", "BAD"];

interface ScenarioCardsProps {
  scenarios: ScenarioResult[];
  display: Record<string, string>;
  /** 구간을 어떻게 나눴는지 가리키는 문구표 키(`result.scenarioMethodShort.<코드>`). 모르는 코드면 줄을 숨긴다. */
  scenarioMethod: string | null;
  /** 계산에 쓴 ETF 종목의 화면 이름(`display["assumptions.etfProductName"]`). 없으면 산정 방식만 보인다(#156). */
  productName?: string;
  /** 고른 카드(#298). `onSelect`와 함께 줄 때만 쓴다. */
  selected?: ScenarioCode;
  /** 있으면 카드가 단추가 되어, 누르면 그 시나리오를 고른다(예상 자산 그래프의 시나리오 선택, #298). 없으면 지금처럼 보기만 한다. */
  onSelect?: (code: ScenarioCode) => void;
  /** 하단 안내 문구와 같은 줄 오른쪽에 놓을 것(예: 그래프의 월별/연별 토글, #298). 없으면 안내 문구만 있다. */
  noticeSlot?: ReactNode;
}

/** 3구간 비교 카드(FR-RES-03, #50, C12a-7). */
export function ScenarioCards({ scenarios, display, scenarioMethod, productName, selected, onSelect, noticeSlot }: ScenarioCardsProps) {
  const methodText = scenarioMethodLine(scenarioMethod, productName);
  const byCode = new Map(scenarios.map((s) => [s.scenario, s]));
  const ordered = SCENARIO_ORDER.map((code) => byCode.get(code)).filter(
    (s): s is ScenarioResult => s !== undefined,
  );

  return (
    <section aria-label="세 가지 경우 비교" className="flex flex-col gap-3">
      <header className="flex flex-col gap-1">
        <h2 className="text-base font-semibold text-text">{COPY["result.scenarioHeading"]}</h2>
        {methodText && <p className="text-xs text-text-muted">{methodText}</p>}
      </header>
      <div className="grid gap-4 sm:grid-cols-3">
        {ordered.map((item) => {
          const reachText = item.isAchieved
            ? display[`${item.scenario}.goalReachMonth`]
            : COPY["result.notReachedInPeriod"];
          const asset = display[`${item.scenario}.afterTaxAsset`];
          const gap = display[`${item.scenario}.gapToGoal`];
          const gapTone = item.isAchieved ? "text-success" : "text-danger";

          // 단추 카드: 안에는 문장 요소(span)만 둔다
          if (onSelect) {
            const isSelected = selected === item.scenario;
            return (
              <button
                key={item.scenario}
                type="button"
                aria-pressed={isSelected}
                onClick={() => onSelect(item.scenario)}
                className={`flex flex-col gap-2 rounded-xl border p-5 text-left shadow-sm transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary ${
                  isSelected ? "border-primary bg-primary-soft ring-1 ring-primary" : "border-border bg-surface hover:bg-surface-muted"
                }`}
              >
                <span className="text-sm font-semibold text-text-muted">{COPY[`scenario.${item.scenario}`]}</span>
                <span className="text-xl font-bold text-text">{asset}</span>
                <span className={`text-sm font-medium ${gapTone}`}>{gap}</span>
                {reachText && <span className="mt-auto pt-2 text-xs text-text-muted">{reachText}</span>}
              </button>
            );
          }

          return (
            <article
              key={item.scenario}
              className="flex flex-col gap-2 rounded-xl border border-border bg-surface p-5 shadow-sm"
            >
              <h3 className="text-sm font-semibold text-text-muted">{COPY[`scenario.${item.scenario}`]}</h3>
              <p className="text-xl font-bold text-text">{asset}</p>
              <p className={`text-sm font-medium ${gapTone}`}>{gap}</p>
              {reachText && <p className="mt-auto pt-2 text-xs text-text-muted">{reachText}</p>}
            </article>
          );
        })}
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-xs text-text-muted">{COPY["result.scenarioNotice"]}</p>
        {noticeSlot}
      </div>
    </section>
  );
}
