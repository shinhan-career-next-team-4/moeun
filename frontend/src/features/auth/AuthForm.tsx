// 로그인·회원가입 화면이 함께 쓰는 카드와 입력 칸. 두 화면의 모양을 같게 둔다.
import type { FormEvent, ReactNode } from "react";
import { Link } from "react-router-dom";
import { FieldError } from "../../shared/ui";

export function AuthFormCard({
  title,
  description,
  onSubmit,
  children,
}: {
  title: string;
  description: string;
  onSubmit(event: FormEvent<HTMLFormElement>): void;
  children: ReactNode;
}) {
  return (
    <div className="mx-auto flex min-h-[calc(100vh-3.5rem)] max-w-5xl items-center justify-center px-6 py-10">
      <form
        onSubmit={onSubmit}
        noValidate
        className="flex w-full max-w-sm flex-col gap-4 rounded-2xl bg-surface p-8 shadow-sm"
      >
        <h1 className="text-xl font-bold text-text">{title}</h1>
        <p className="text-sm text-text-muted">{description}</p>
        {children}
      </form>
    </div>
  );
}

export function AuthField({
  id,
  label,
  type,
  autoComplete,
  placeholder,
  value,
  onChange,
  hint,
  error,
}: {
  id: string;
  label: string;
  type: "text" | "password";
  autoComplete: string;
  placeholder: string;
  value: string;
  onChange(value: string): void;
  /** 칸 아래 규칙 안내. 오류가 있어도 함께 보인다. */
  hint?: string;
  error?: string;
}) {
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ");
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium text-text">
        {label}
      </label>
      <input
        id={id}
        type={type}
        autoComplete={autoComplete}
        placeholder={placeholder}
        value={value}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        onChange={(event) => onChange(event.target.value)}
        className="h-11 rounded-lg border border-border bg-surface px-3 text-base text-text outline-none focus:outline-2 focus:outline-primary"
      />
      {hint && (
        <p id={hintId} className="text-xs text-text-muted">
          {hint}
        </p>
      )}
      {error && <FieldError id={errorId} message={error} />}
    </div>
  );
}

/** 카드 아래 "다른 화면으로" 한 줄(로그인 ↔ 회원가입). */
export function AuthSwitchLink({ prompt, to, label }: { prompt: string; to: string; label: string }) {
  return (
    <p className="text-center text-sm text-text-muted">
      {prompt}{" "}
      <Link to={to} className="font-semibold text-primary hover:underline">
        {label}
      </Link>
    </p>
  );
}
