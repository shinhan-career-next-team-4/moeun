import { useState } from "react";
import { COPY } from "../../shared/copy/copy";
import { ScenarioCards } from "../result/ScenarioCards";
import { buildMockScenarioCards, MOCK_PROJECTION } from "./mockProjection";
import { ProjectionChart } from "./ProjectionChart";
import type { ProjectionScenario } from "./types";

// 카드용 mock은 한 번만 만든다(그래프 데이터는 10년치 MOCK_PROJECTION)
const MOCK_CARDS = buildMockScenarioCards(MOCK_PROJECTION);

/**
 * 예상 자산 누적 그래프를 mock 데이터로 확인하는 데모 페이지(#298). 서버 연동 전 확인용이다.
 * 위쪽 3구간 카드가 시나리오 버튼이고, 고른 시나리오를 아래 그래프가 그린다(월별/연별 토글은 그래프 카드 안에 있다).
 * 연동되면 이 페이지와 mock은 지우고, 결과 화면에서 같은 카드와 ProjectionChart에 서버 응답을 넘기면 된다.
 * (컴포넌트는 최대 30년까지 받는다. 데모는 10년치만 보여 준다)
 */
export function ProjectionDemoPage() {
  const [scenario, setScenario] = useState<ProjectionScenario>("AVG");

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-4 px-4 py-8 sm:px-6">
      <div>
        <h1 className="text-xl font-bold text-text">{COPY["projection.demoTitle"]}</h1>
        <p className="mt-1 text-sm text-text-muted">{COPY["projection.demoNotice"]}</p>
      </div>
      <ScenarioCards
        scenarios={MOCK_CARDS.scenarios}
        display={MOCK_CARDS.display}
        scenarioMethod="ROLLING_DISTRIBUTION"
        productName="S&P500 추종 ETF"
        selected={scenario}
        onSelect={setScenario}
      />
      <ProjectionChart data={MOCK_PROJECTION} scenario={scenario} />
    </div>
  );
}
