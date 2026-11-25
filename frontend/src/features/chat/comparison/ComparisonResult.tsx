import type { ChatMessage } from "../types";
import {
  ACHIEVEMENT_CHANGE_LABELS,
  APPLY_LABEL,
  COLLAPSED_LABEL,
  CONDITION_LABELS,
  DIRECTION_COPY,
  ETF_LABEL,
  GOAL_LABEL,
  GOAL_NAME_LABEL,
  LOAD_FAILED,
} from "./copy";
import { isApplyActive } from "./controller";
import {
  ETF_KEY,
  GOAL_KEY,
  GOAL_NAME_KEY,
  conditionDisplayKey,
  conditionKeysOf,
  type Comparison,
  type ComparisonSideKey,
  type Direction,
} from "./types";
import type { ComparisonState } from "./useComparison";
import { COPY } from "../../../shared/copy/copy";

// 비교 카드(#394): 한 줄에 "이름 · 지금 값 → 바꾼 값 · 변화"를 보이고, 줄 사이를 옅은 선으로 나눈다.
const ROW = "grid grid-cols-[4.5rem_minmax(0,1fr)] items-baseline gap-x-2 gap-y-0.5 border-b border-border/60 px-1 py-1.5 last:border-b-0";
// 바뀐 조건은 배경색과 굵은 글씨로 눈에 띄게 한다.
const CHANGED_ROW = "grid grid-cols-[4.5rem_minmax(0,1fr)] items-baseline gap-x-2 gap-y-0.5 rounded-lg bg-primary-soft px-2 py-1.5 font-semibold text-text";
// 값들을 한 칸에 모아 좁은 패널에서도 줄이 자연스럽게 바뀌게 한다
const VALUES = "flex flex-wrap items-baseline gap-x-2 gap-y-0.5";
// 줄 앞의 이름 칸. 너비를 맞춰 값이 세로로 정렬된다.
const LABEL_CELL = "text-xs text-text-muted";
// 지금 값(흐리게) → 바꾼 값(진하게)
const CURRENT_CELL = "text-text-muted";
const CHANGED_CELL = "font-semibold text-text";
// 목표 이름은 1~100자라 길면 칸 안에서 줄을 바꾼다(띄어쓰기 없는 이름도)
const NAME_CELL = "min-w-0 wrap-anywhere";

// 결과의 변화 방향(#284). 클수록 좋은 값(예상 자산·목표 대비)만 쓴다. 색만으로 구분하지 않게 기호와 낭독 문구를 함께 둔다
const RESULT_TONE: Record<Direction, { className: string; mark: string; spoken: string }> = {
  up: { className: "text-success", mark: "▲", spoken: DIRECTION_COPY.better },
  down: { className: "text-danger", mark: "▼", spoken: DIRECTION_COPY.worse },
  same: { className: "text-text-muted", mark: "–", spoken: DIRECTION_COPY.same },
};
// 조건 자체에는 좋고 나쁨이 없다. 늘림·줄임만 중립 강조색으로 보인다
const CONDITION_MARK: Record<Direction, { mark: string; spoken: string }> = {
  up: { mark: "▲", spoken: DIRECTION_COPY.increased },
  down: { mark: "▼", spoken: DIRECTION_COPY.decreased },
  same: { mark: "–", spoken: DIRECTION_COPY.same },
};

function labelOf(key: string): string {
  if (key === GOAL_KEY) return GOAL_LABEL;
  if (key === ETF_KEY) return ETF_LABEL;
  return CONDITION_LABELS[key];
}

/** 실제로 바뀐 항목만 카드 행 순서대로 잇는다(#284). 목표 이름은 맨 앞 행이다(#345). */
function titleOf(data: Comparison): string {
  const changed = conditionKeysOf(data.changedKeys).filter((key) => data.changedKeys.has(key));
  const labels = [...(data.changedGoalName !== null ? [GOAL_NAME_LABEL] : []), ...changed.map(labelOf)];
  return DIRECTION_COPY.title.replace("{conditions}", labels.join("·"));
}

/** 목표 이름만 바꿨는지. 이름은 계산에 쓰지 않아 양쪽 숫자가 같다(#345). */
function nameOnly(data: Comparison): boolean {
  return data.changedGoalName !== null && [...data.changedKeys].every((key) => key === GOAL_NAME_KEY);
}

/** 바꾼 목표 이름 행(#345). 지금 이름은 계획에서 읽고, 모르면 칸을 비운다. 이름은 숫자가 아니라 방향 표시가 없다. */
function GoalNameRow({ current, changed }: { current: string | null; changed: string }) {
  return (
    <div data-testid="chat-comparison-condition" data-key={GOAL_NAME_KEY} data-changed="true" className={CHANGED_ROW}>
      <span data-testid="chat-comparison-label" className={LABEL_CELL}>{GOAL_NAME_LABEL}</span>
      <div className={VALUES}>
        <span data-testid="chat-comparison-current" className={`${NAME_CELL} ${CURRENT_CELL}`}>
          {current ?? ""}
        </span>
        <span aria-hidden="true">→</span>
        <span data-testid="chat-comparison-changed" className={`${NAME_CELL} ${CHANGED_CELL}`}>
          {changed}
        </span>
      </div>
    </div>
  );
}

/**
 * 결과 값의 변화. 값 칸(testId)에는 서버 display 문자열만 둔다. text가 없으면(목표가 바뀐 목표 대비: 서버에 차이 값이
 * 없다) 방향만 보인다.
 */
function ResultChange({ testId, direction, text }: { testId: string; direction: Direction; text?: string }) {
  const tone = RESULT_TONE[direction];
  return (
    <span data-testid={`${testId}-tone`} data-direction={direction} className={`inline-flex items-baseline gap-0.5 rounded-full px-2 py-0.5 text-xs font-semibold ${tone.className}`}>
      <span aria-hidden="true">{tone.mark} </span>
      {text !== undefined && <span data-testid={testId}>{text}</span>}
      <span className="sr-only">{tone.spoken}</span>
    </span>
  );
}

function ConditionChange({ direction }: { direction: Direction | undefined }) {
  if (direction === undefined) return null;
  const mark = CONDITION_MARK[direction];
  return (
    <span data-testid="chat-comparison-condition-direction" data-direction={direction} className="text-primary-strong">
      <span aria-hidden="true">{mark.mark}</span>
      <span className="sr-only">{mark.spoken}</span>
    </span>
  );
}

function ApplyButton({ comparison, messageId, active }: { comparison: ComparisonState; messageId: number; active: boolean }) {
  if (comparison.hideApply) return null;
  return (
    <button
      type="button"
      data-testid="chat-comparison-apply"
      disabled={!active}
      onClick={() => comparison.controller.apply(messageId)}
      // 글자처럼 보이지 않게 버튼 모양을 입힌다(#392). 비활성(이미 적용했거나 지난 비교)이면 흐리게 보인다
      className="mt-2 inline-flex h-9 items-center justify-center rounded-lg bg-primary px-4 text-sm font-semibold text-white transition-colors hover:bg-primary-strong focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary disabled:cursor-not-allowed disabled:bg-border disabled:text-text-muted"
    >
      {APPLY_LABEL}
    </button>
  );
}

function goalNote(data: Comparison): string | null {
  if (!data.changedKeys.has(GOAL_KEY)) return null;
  return COPY["chat.goalAmountChangedNote"]
    .replace("{currentGoal}", data.display[`conditions.current.${GOAL_KEY}`])
    .replace("{changedGoal}", data.display[`conditions.changed.${GOAL_KEY}`]);
}

/** 한쪽이 이미 목표를 넘은 비교(D-092). 초과한 쪽은 안내 문장, 계산된 쪽은 기준 시나리오 값을 display 그대로 보인다. */
function ExceededSides({ data, exceeded }: { data: Comparison; exceeded: ComparisonSideKey }) {
  const { display, baselineScenario } = data;
  const calculated: ComparisonSideKey = exceeded === "current" ? "changed" : "current";
  const message = COPY["chat.cardAlreadyExceeded"]
    .replace("{heldAssets}", display[`conditions.${exceeded}.heldAssets`])
    .replace("{goalAmount}", display[`conditions.${exceeded}.${GOAL_KEY}`]);
  return (
    <>
      <p data-testid={`chat-comparison-${exceeded}-exceeded`} className="rounded-lg bg-surface-muted px-2 py-1.5 text-xs leading-relaxed text-text">{message}</p>
      <div data-testid={`chat-comparison-${calculated}-calculated`} className={ROW}>
        <span>{COPY[calculated === "current" ? "compare.colCurrent" : "compare.colChanged"]}</span>
        <span>{COPY[`scenario.${baselineScenario}` as keyof typeof COPY] ?? baselineScenario}</span>
        <span>{display[`${calculated}.${baselineScenario}.afterTaxAsset`]}</span>
        <span>{display[`${calculated}.${baselineScenario}.gapToGoal`]}</span>
      </div>
      {exceeded === "changed" && <p data-testid="chat-comparison-changed-exceeded-note" className="text-xs text-text-muted">{COPY["chat.changedAlreadyExceeded"]}</p>}
    </>
  );
}

function Details({ data, currentGoalName }: { data: Comparison; currentGoalName: string | null }) {
  const note = goalNote(data);
  const goalChanged = data.changedKeys.has(GOAL_KEY);
  return (
    <>
      <p data-testid="chat-comparison-title" className="mb-1 text-sm font-bold text-text">
        {titleOf(data)}
      </p>
      {data.changedGoalName !== null && <GoalNameRow current={currentGoalName} changed={data.changedGoalName} />}
      {conditionKeysOf(data.changedKeys).map((key) => {
        const changed = data.changedKeys.has(key);
        return (
          <div
            key={key}
            data-testid="chat-comparison-condition"
            data-key={key}
            data-changed={changed ? "true" : "false"}
            className={changed ? CHANGED_ROW : ROW}
          >
            <span data-testid="chat-comparison-label" className={LABEL_CELL}>{labelOf(key)}</span>
            <div className={VALUES}>
              <span data-testid="chat-comparison-current" className={CURRENT_CELL}>{data.display[conditionDisplayKey("current", key)]}</span>
              <span aria-hidden="true">→</span>
              <span data-testid="chat-comparison-changed" className={CHANGED_CELL}>{data.display[conditionDisplayKey("changed", key)]}</span>
              {changed && <ConditionChange direction={data.conditionDirections[key]} />}
            </div>
          </div>
        );
      })}
      {data.scenarios.map(({ scenario, achievementChange, assetDirection, gapDirection }) => (
        <div key={scenario} data-testid="chat-comparison-scenario" data-scenario={scenario} className={ROW}>
          <span className={`${LABEL_CELL} font-semibold text-text`}>{COPY[`scenario.${scenario}` as keyof typeof COPY] ?? scenario}</span>
          <div className={VALUES}>
            <span data-testid="chat-comparison-current" className={CURRENT_CELL}>{data.display[`current.${scenario}.afterTaxAsset`]}</span>
            <span aria-hidden="true">→</span>
            <span data-testid="chat-comparison-changed" className={CHANGED_CELL}>{data.display[`changed.${scenario}.afterTaxAsset`]}</span>
            <ResultChange
              testId="chat-comparison-delta"
              direction={assetDirection}
              text={data.display[`diff.${scenario}.afterTaxAssetDelta`]}
            />
            <span data-testid="chat-comparison-gap" className="basis-full text-xs text-text-muted">{data.display[`changed.${scenario}.gapToGoal`]}</span>
            {/* 목표 대비 차이는 서버 값(#285). 키가 없으면 목표가 그대로일 때만 같은 값인 예상 자산 차이를 쓰고, 아니면 방향만 */}
            <ResultChange
              testId="chat-comparison-gap-delta"
              direction={gapDirection}
              text={data.display[`diff.${scenario}.gapToGoalDelta`] ?? (goalChanged ? undefined : data.display[`diff.${scenario}.afterTaxAssetDelta`])}
            />
            <span data-testid="chat-comparison-achievement" className="text-xs text-text-muted">{ACHIEVEMENT_CHANGE_LABELS[achievementChange] ?? ""}</span>
          </div>
        </div>
      ))}
      {data.exceeded !== null && <ExceededSides data={data} exceeded={data.exceeded} />}
      {note !== null && <p data-testid="chat-comparison-goal-note" className="mt-1 text-xs leading-relaxed text-text-muted">{note}</p>}
      {nameOnly(data) && <p data-testid="chat-comparison-goal-name-note" className="mt-1 text-xs leading-relaxed text-text-muted">{COPY["chat.goalNameOnlyNote"]}</p>}
    </>
  );
}

/**
 * RECALC_RESULT 말풍선 뒤의 비교 자리. F-4 응답 전·404·400·401이면 아무것도 그리지 않는다.
 * 가장 최근이 아닌 비교는 상세를 숨긴 '이전 결과' 카드로 접고 버튼을 비활성으로 둔다(P-3, P-72).
 */
export function ComparisonResult({
  comparison,
  message,
  currentGoalName = null,
}: {
  comparison: ComparisonState;
  message: ChatMessage;
  /** 지금 계획의 목표 이름. 목표 이름을 바꾼 비교(#345)의 왼쪽 칸이다. */
  currentGoalName?: string | null;
}) {
  if (message.messageType !== "RECALC_RESULT" || message.status !== "COMPLETED") return null;
  const { snapshot, latestId } = comparison;
  const entry = snapshot.entries.get(message.id);
  if (entry?.kind === "failed") {
    return (
      <p data-testid="chat-comparison-failed" data-message-id={message.id}>
        {LOAD_FAILED}
      </p>
    );
  }
  if (entry?.kind === "empty") return null;

  if (message.id !== latestId) {
    // 이전 비교의 F-4가 응답 전이면 최신 F-4가 성공했을 때만 접힌 카드를 보인다. 최신이 응답 전·404·500이어도 이전 자리는 비어 있다(P-72).
    // 이미 받은 이전 비교는 최신이 실패해도 그대로 접힌다.
    const latestKind = latestId === null ? undefined : snapshot.entries.get(latestId)?.kind;
    if (entry?.kind === "loading" && latestKind !== "ready") return null;
    return (
      <div data-testid="chat-comparison" data-message-id={message.id} data-collapsed="true" className="flex items-center justify-between gap-2 rounded-xl border border-border bg-surface-muted px-3 py-2 text-xs text-text-muted">
        <p>{COLLAPSED_LABEL}</p>
        <ApplyButton comparison={comparison} messageId={message.id} active={false} />
      </div>
    );
  }
  if (entry?.kind !== "ready") return null;
  return (
    <div data-testid="chat-comparison" data-message-id={message.id} data-collapsed="false" className="soft-card-sm flex flex-col gap-1 p-4 text-sm">
      <Details data={entry.data} currentGoalName={currentGoalName} />
      <ApplyButton comparison={comparison} messageId={message.id} active={isApplyActive(snapshot, latestId, message.id)} />
    </div>
  );
}
