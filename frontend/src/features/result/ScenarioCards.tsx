import { COPY } from "../../shared/copy/copy";
import type { ScenarioCode, ScenarioResult } from "./types";

const SCENARIO_ORDER: readonly ScenarioCode[] = ["GOOD", "AVG", "BAD"];

interface ScenarioCardsProps {
  scenarios: ScenarioResult[];
  display: Record<string, string>;
}

/** 3구간 비교 카드(FR-RES-03, #50, C12a-7). */
export function ScenarioCards({ scenarios, display }: ScenarioCardsProps) {
  const byCode = new Map(scenarios.map((s) => [s.scenario, s]));
  const ordered = SCENARIO_ORDER.map((code) => byCode.get(code)).filter(
    (s): s is ScenarioResult => s !== undefined,
  );

  return (
    <section aria-label="세 가지 경우 비교" className="flex flex-col gap-3">
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
