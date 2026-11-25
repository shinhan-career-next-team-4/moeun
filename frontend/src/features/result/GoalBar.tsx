import { COPY } from "../../shared/copy/copy";

interface GoalBarProps {
  /** 기준 시나리오의 세후 예상 자산(원) */
  afterTaxAsset: number;
  /** 목표 금액(원) */
  goalAmount: number;
  /** 목표 금액 표시 문자열(서버 display). 없으면 막대를 그리지 않는다 */
  goalAmountText?: string;
  isAchieved: boolean;
}

/** 막대 전체가 나타내는 금액. 목표를 넘겼으면 넘긴 자산까지 담아 목표 표시가 중간에 오게 한다. */
function scaleMax(afterTaxAsset: number, goalAmount: number): number {
  return Math.max(afterTaxAsset, goalAmount);
}

function percent(value: number, max: number): number {
  if (max <= 0) return 0;
  return Math.min(100, Math.max(0, (value / max) * 100));
}

/** 목표 위치가 이 값(%) 이상이면 글자를 막대 오른쪽 끝에 맞춰 카드 밖으로 나가지 않게 한다(#425). */
const LABEL_EDGE_PCT = 80;

function labelStyle(goalPct: number): { left?: string; right?: string } {
  return goalPct >= LABEL_EDGE_PCT ? { right: "0" } : { left: `${goalPct}%` };
}

/**
 * 결과 요약의 목표 대비 가로 막대(와이어프레임 03, FR-RES-02).
 * 숫자 문자열은 서버 display만 쓰고, 화면은 막대 길이와 표시 위치(기하)만 정한다(NFR-INT-01).
 */
export function GoalBar({ afterTaxAsset, goalAmount, goalAmountText, isAchieved }: GoalBarProps) {
  if (!goalAmountText || goalAmount <= 0) return null;

  const max = scaleMax(afterTaxAsset, goalAmount);
  const fillPct = percent(afterTaxAsset, max);
  const goalPct = percent(goalAmount, max);

  const labelAlign = goalPct >= LABEL_EDGE_PCT ? "right" : "center";

  return (
    <div className="mt-2 flex w-full flex-col gap-1.5" role="img" aria-label="목표 대비 예상 자산">
      <div className="relative h-3 w-full rounded-full bg-surface-muted">
        <div
          data-testid="goal-bar-fill"
          className={`h-3 rounded-full ${isAchieved ? "bg-success" : "bg-primary"}`}
          style={{ width: `${fillPct}%` }}
        />
        <div
          data-testid="goal-bar-marker"
          aria-hidden
          className="absolute top-0 h-3 w-0.5 bg-text"
          style={{ left: `${goalPct}%` }}
        />
      </div>
      <div className="relative h-5 text-xs text-text-muted">
        <span className="absolute left-0">{COPY["result.goalBarZero"]}</span>
        <span
          data-testid="goal-bar-label"
          className={`absolute whitespace-nowrap font-medium text-text ${
            labelAlign === "center" ? "-translate-x-1/2" : ""
          }`}
          style={labelStyle(goalPct)}
        >
          {COPY["result.goalBarLabel"].replace("{goalAmount}", goalAmountText)}
        </span>
      </div>
    </div>
  );
}
