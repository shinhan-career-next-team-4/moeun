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
}

/** 3구간 비교 카드(FR-RES-03, #50, C12a-7). */
export function ScenarioCards({ scenarios, display, scenarioMethod, productName }: ScenarioCardsProps) {
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
          return (
            <article
              key={item.scenario}
              className="flex flex-col gap-2 rounded-xl border border-border bg-surface p-5 shadow-sm"
            >
              <h3 className="text-sm font-semibold text-text-muted">{COPY[`scenario.${item.scenario}`]}</h3>
              <p className="text-xl font-bold text-text">{display[`${item.scenario}.afterTaxAsset`]}</p>
              <p className={`text-sm font-medium ${item.isAchieved ? "text-success" : "text-danger"}`}>
                {display[`${item.scenario}.gapToGoal`]}
              </p>
              {reachText && <p className="mt-auto pt-2 text-xs text-text-muted">{reachText}</p>}
            </article>
          );
        })}
      </div>
      <p className="text-xs text-text-muted">{COPY["result.scenarioNotice"]}</p>
    </section>
  );
}
