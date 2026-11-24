import { useState } from "react";
import { planStore } from "../../api";
import { COPY } from "../../shared/copy/copy";
import { goalName } from "../../shared/units/units";
import { GOAL_NAME_MAX_LENGTH, requestGoalName } from "./requestGoalName";
import type { ResultPlanView } from "./types";

interface GoalNameEditorProps {
  view: ResultPlanView;
  /** 저장 결과 안내를 결과 화면에 띄운다. 없으면 안내 없이 끝낸다 */
  onFeedback?(message: string | null): void;
}

/**
 * 조건 바의 목표 이름(#341). 이름을 누르면 그 자리에서 입력칸으로 바뀌고, 저장하면 이름만 바뀐 채로 다시 계산한다.
 * 다른 목표 종류(예: 내 집 마련)의 이름을 고치면 종류는 직접 입력(CUSTOM)이 된다.
 */
export function GoalNameEditor({ view, onFeedback = () => {} }: GoalNameEditorProps) {
  const { plan } = view;
  const current = goalName(plan.goalType, plan.goalCustomName);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(current);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  function open() {
    setDraft(current);
    setError(null);
    setEditing(true);
  }

  async function save() {
    if (saving) return;
    const trimmed = draft.trim();
    // 바꾸지 않았으면 서버에 보내지 않고 닫는다
    if (trimmed === current) {
      setEditing(false);
      return;
    }
    setSaving(true);
    setError(null);
    onFeedback(null);
    const outcome = await requestGoalName(plan, draft);
    setSaving(false);
    switch (outcome.kind) {
      case "saved":
        setEditing(false);
        return;
      case "invalid":
        setError(outcome.reason === "REQUIRED" ? COPY["validation.REQUIRED"] : "목표 이름은 100자 이내로 입력해 주세요");
        return;
      case "planChanged":
        setEditing(false);
        onFeedback(COPY["plan.changedElsewhere"]);
        planStore.loadPlan({ fresh: true }).catch(() => {});
        return;
      case "unauthenticated":
        return;
      case "failed":
        setError(COPY["input.saveFailed"]);
        return;
    }
  }

  if (!editing) {
    return (
      <button
        type="button"
        onClick={open}
        aria-label={`목표 이름 수정: ${current}`}
        title="눌러서 목표 이름 수정"
        className="rounded px-1 underline decoration-dotted underline-offset-4 hover:bg-surface-muted focus-visible:outline-2 focus-visible:outline-primary"
      >
        {current}
      </button>
    );
  }

  return (
    <form
      className="inline-flex flex-col gap-1"
      onSubmit={(event) => {
        event.preventDefault();
        void save();
      }}
    >
      <span className="inline-flex items-center gap-1">
        <input
          autoFocus
          aria-label="목표 이름"
          value={draft}
          maxLength={GOAL_NAME_MAX_LENGTH}
          disabled={saving}
          aria-invalid={error ? true : undefined}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Escape") setEditing(false);
          }}
          className={`h-8 w-44 rounded-lg border bg-surface px-2 text-sm text-text outline-none focus-visible:outline-2 focus-visible:outline-primary ${
            error ? "border-danger" : "border-border"
          }`}
        />
        <button
          type="submit"
          disabled={saving}
          className="rounded-lg px-2 py-1 text-xs font-semibold text-primary hover:bg-surface-muted disabled:text-text-muted"
        >
          저장
        </button>
        <button
          type="button"
          disabled={saving}
          onClick={() => setEditing(false)}
          className="rounded-lg px-2 py-1 text-xs text-text-muted hover:bg-surface-muted"
        >
          취소
        </button>
      </span>
      {error && (
        <span role="alert" className="text-xs text-danger">
          {error}
        </span>
      )}
    </form>
  );
}
