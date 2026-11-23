import { EXAMPLE_QUESTIONS } from "./copy";

// 빈 대화창에는 계획 값을 바꾸는 질문 앞쪽 세 개만 보인다(#224).
const VISIBLE_COUNT = 3;

export function Suggestions({ disabled, onPick }: { disabled: boolean; onPick: (question: string) => void }) {
  return (
    <div data-testid="chat-suggestions" className="flex flex-col items-start gap-2">
      {EXAMPLE_QUESTIONS.slice(0, VISIBLE_COUNT).map((question) => (
        <button
          key={question}
          type="button"
          data-testid="chat-suggestion"
          disabled={disabled}
          onClick={() => onPick(question)}
          className="rounded-full border border-border bg-surface px-3 py-1.5 text-left text-sm text-text hover:bg-surface-muted disabled:cursor-not-allowed disabled:text-text-muted"
        >
          {question}
        </button>
      ))}
    </div>
  );
}
