import { COPY } from "../../shared/copy/copy";
import { MOCK_PROJECTION } from "./mockProjection";
import { ProjectionChart } from "./ProjectionChart";

/**
 * 예상 자산 누적 그래프를 mock 데이터로 확인하는 데모 페이지(#298). 서버 연동 전 확인용이다.
 * API가 연결되면 이 페이지는 지우고, ProjectionChart에 서버 응답을 넘기면 된다.
 */
export function ProjectionDemoPage() {
  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-4 px-4 py-8 sm:px-6">
      <div>
        <h1 className="text-xl font-bold text-text">{COPY["projection.demoTitle"]}</h1>
        <p className="mt-1 text-sm text-text-muted">{COPY["projection.demoNotice"]}</p>
      </div>
      <ProjectionChart data={MOCK_PROJECTION} />
    </div>
  );
}
