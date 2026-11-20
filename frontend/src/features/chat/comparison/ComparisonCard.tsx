import type { ChatMessage } from "../types";
import { ACHIEVEMENT_CHANGE_LABELS, APPLY_LABEL, COLLAPSED_LABEL, CONDITION_LABELS, LOAD_FAILED } from "./copy";
import { isApplyActive } from "./controller";
import { CONDITION_KEYS, type Comparison } from "./types";
import type { ComparisonState } from "./useComparison";
import { COPY } from "../../../shared/copy/copy";

const ROW = "flex flex-wrap gap-2";
// 바뀐 조건은 굵게, 배경색으로 눈에 띄게 한다(조건 없는 class라 평소 화면에 바로 적용된다).
const CHANGED_ROW = `${ROW} font-bold bg-primary-soft`;

function ApplyButton({ comparison, messageId, active }: { comparison: ComparisonState; messageId: number; active: boolean }) {
  if (comparison.hideApply) return null;
  return (
    <button
      type="button"
      data-testid="chat-comparison-apply"
      disabled={!active}
      onClick={() => comparison.controller.apply(messageId)}
    >
      {APPLY_LABEL}
    </button>
  );
}

function Details({ data }: { data: Comparison }) {
  return (
    <>
      {CONDITION_KEYS.map((key) => {
        const changed = data.changedKeys.has(key);
        return (
          <div
            key={key}
            data-testid="chat-comparison-condition"
            data-key={key}
            data-changed={changed ? "true" : "false"}
            className={changed ? CHANGED_ROW : ROW}
          >
            <span data-testid="chat-comparison-label">{CONDITION_LABELS[key]}</span>
            <span data-testid="chat-comparison-current">{data.display[`conditions.current.${key}`]}</span>
            <span aria-hidden="true">→</span>
            <span data-testid="chat-comparison-changed">{data.display[`conditions.changed.${key}`]}</span>
          </div>
        );
      })}
      {data.scenarios.map(({ scenario, achievementChange }) => (
        <div key={scenario} data-testid="chat-comparison-scenario" data-scenario={scenario} className={ROW}>
          <span>{COPY[`scenario.${scenario}` as keyof typeof COPY] ?? scenario}</span>
          <span data-testid="chat-comparison-current">{data.display[`current.${scenario}.afterTaxAsset`]}</span>
          <span aria-hidden="true">→</span>
          <span data-testid="chat-comparison-changed">{data.display[`changed.${scenario}.afterTaxAsset`]}</span>
          <span data-testid="chat-comparison-delta">{data.display[`diff.${scenario}.afterTaxAssetDelta`]}</span>
          <span data-testid="chat-comparison-gap">{data.display[`changed.${scenario}.gapToGoal`]}</span>
          <span data-testid="chat-comparison-achievement">{ACHIEVEMENT_CHANGE_LABELS[achievementChange] ?? ""}</span>
        </div>
      ))}
    </>
  );
}

/**
 * RECALC_RESULT 말풍선 뒤의 비교 자리. F-4 응답 전·404·400·401이면 아무것도 그리지 않는다.
 * 가장 최근이 아닌 비교는 상세를 숨긴 '이전 결과' 카드로 접고 버튼을 비활성으로 둔다(P-3, P-72).
 */
export function ComparisonCard({ comparison, message }: { comparison: ComparisonState; message: ChatMessage }) {
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
      <div data-testid="chat-comparison" data-message-id={message.id} data-collapsed="true">
        <p>{COLLAPSED_LABEL}</p>
        <ApplyButton comparison={comparison} messageId={message.id} active={false} />
      </div>
    );
  }
  if (entry?.kind !== "ready") return null;
  return (
    <div data-testid="chat-comparison" data-message-id={message.id} data-collapsed="false">
      <Details data={entry.data} />
      <ApplyButton comparison={comparison} messageId={message.id} active={isApplyActive(snapshot, latestId, message.id)} />
    </div>
  );
}
