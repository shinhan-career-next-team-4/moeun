import type { ChangeEvent } from "react";
import { FieldError } from "./FieldError";

interface UnitInputProps {
  id: string;
  label: string;
  unit: string;
  value: string;
  onChange: (value: string) => void;
  error?: string;
  required?: boolean;
  help?: string;
  inputMode?: "numeric" | "decimal";
  placeholder?: string;
  maxLength?: number;
  /** 비활성(예: '지금 하고 있는 저축' 토글이 OFF일 때의 납입액 칸, C12a-5-6 (h)) */
  disabled?: boolean;
}

/** 라벨 + 입력칸 + 단위. 오류와 도움말은 aria-describedby로 입력칸에 연결한다. */
export function UnitInput({
  id,
  label,
  unit,
  value,
  onChange,
  error,
  required,
  help,
  inputMode = "numeric",
  placeholder,
  maxLength,
  disabled = false,
}: UnitInputProps) {
  const helpId = `${id}-help`;
  const errorId = `${id}-error`;
  const describedBy = [error ? errorId : null, help ? helpId : null].filter(Boolean).join(" ") || undefined;

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium text-text">
        {label}
        {required && (
          <span aria-hidden className="ml-0.5 text-danger">
            *
          </span>
        )}
      </label>
      <div
        className={`flex h-11 items-center rounded-lg border px-3 focus-within:outline-2 focus-within:outline-primary ${
          error ? "border-danger" : "border-border"
        } ${disabled ? "bg-surface-muted" : "bg-surface"}`}
      >
        <input
          id={id}
          type="text"
          inputMode={inputMode}
          value={value}
          placeholder={placeholder}
          maxLength={maxLength}
          disabled={disabled}
          aria-invalid={error ? true : undefined}
          aria-required={required || undefined}
          aria-describedby={describedBy}
          onChange={(event: ChangeEvent<HTMLInputElement>) => onChange(event.target.value)}
          className="w-full min-w-0 bg-transparent text-right text-base text-text outline-none placeholder:text-text-muted disabled:cursor-not-allowed disabled:text-text-muted"
        />
        {unit && <span className="ml-2 shrink-0 text-sm text-text-muted">{unit}</span>}
      </div>
      {error && <FieldError id={errorId} message={error} />}
      {help && (
        <p id={helpId} className="text-xs text-text-muted">
          {help}
        </p>
      )}
    </div>
  );
}
