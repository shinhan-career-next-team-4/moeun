import { EXAMPLE_QUESTIONS } from "./copy";

export function Suggestions({ disabled, onPick }: { disabled: boolean; onPick: (question: string) => void }) {
  return (
    <div data-testid="chat-suggestions">
      {EXAMPLE_QUESTIONS.map((question) => (
        <button key={question} type="button" data-testid="chat-suggestion" disabled={disabled} onClick={() => onPick(question)}>
          {question}
        </button>
      ))}
    </div>
  );
}
