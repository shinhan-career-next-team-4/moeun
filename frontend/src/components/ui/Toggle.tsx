interface ToggleProps {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}

/**
 * 스위치. 손잡이는 `left-0.5`로 트랙 왼쪽에 붙여 두고 translate로만 움직인다.
 * `left`를 주지 않으면 button의 `text-align: center`를 물려받아 트랙 가운데에서 출발하고,
 * 그 상태로 translate가 더해져 손잡이가 트랙 밖으로 나간다.
 */
export function Toggle({ label, checked, onChange }: ToggleProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={() => onChange(!checked)}
      className="inline-flex cursor-pointer items-center gap-3 text-left text-sm font-medium text-text focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
    >
      <span aria-hidden className={`relative h-6 w-11 shrink-0 rounded-full transition-colors ${checked ? "bg-primary" : "bg-border"}`}>
        <span className={`absolute top-0.5 left-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform ${checked ? "translate-x-5" : "translate-x-0"}`} />
      </span>
      {label}
    </button>
  );
}
