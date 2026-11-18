"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";

import { Button } from "@/components/ui";
import { copy } from "@/copy/ko";
import { ApiError } from "@/lib/api/client";
import { login } from "@/lib/api/endpoints";

import { entryPath } from "./entryPath";

export function LoginForm() {
  const router = useRouter();
  const expired = useSearchParams().get("expired") === "1";
  const [loginId, setLoginId] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!loginId.trim() || !password) {
      setError(copy.auth.required);
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const session = await login(loginId.trim(), password);
      // 떠난 화면이 숨은 채 남으므로, 비우지 않으면 비밀번호가 세션 내내 DOM에 남는다
      setPassword("");
      router.replace(entryPath(session.hasPlan));
    } catch (caught) {
      setPassword("");
      setError(loginErrorMessage(caught));
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} noValidate className="flex w-full max-w-sm flex-col gap-4 rounded-2xl bg-surface p-8 shadow-sm">
      <h1 className="text-xl font-bold text-text">{copy.auth.title}</h1>
      <p className="text-sm text-text-muted">{copy.auth.lead}</p>
      {expired && !error && <p className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-primary-strong">{copy.auth.expired}</p>}
      <LabeledField id="loginId" label={copy.auth.loginId} placeholder={copy.auth.loginIdPlaceholder} type="text" autoComplete="username" value={loginId} onChange={setLoginId} />
      <LabeledField id="password" label={copy.auth.password} placeholder={copy.auth.passwordPlaceholder} type="password" autoComplete="current-password" value={password} onChange={setPassword} />
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
      <Button type="submit" loading={submitting}>
        {copy.auth.submit}
      </Button>
      <p className="text-xs text-text-muted">{copy.auth.guard}</p>
    </form>
  );
}

function loginErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 401) {
    return error.detail ?? copy.auth.wrong;
  }
  return copy.common.networkError;
}

interface LabeledFieldProps {
  id: string;
  label: string;
  placeholder: string;
  type: "text" | "password";
  autoComplete: string;
  value: string;
  onChange: (value: string) => void;
}

function LabeledField({ id, label, placeholder, type, autoComplete, value, onChange }: LabeledFieldProps) {
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
        onChange={(event) => onChange(event.target.value)}
        className="h-11 rounded-lg border border-border bg-surface px-3 text-base text-text outline-none focus:outline-2 focus:outline-primary"
      />
    </div>
  );
}
