import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { api, planStore } from "../../api";
import { ApiError } from "../../api/errors";
import { RESULT_PATH } from "../../app/routes";
import { COPY } from "../../shared/copy/copy";
import { Button, FieldError } from "../../shared/ui";
import { formatFieldError, type ServerFieldError } from "../../shared/units/units";

export function LoginPage() {
  const navigate = useNavigate();
  const [loginId, setLoginId] = useState("");
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<{ loginId?: string; password?: string }>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;

    const nextFieldErrors: { loginId?: string; password?: string } = {};
    if (!loginId.trim()) nextFieldErrors.loginId = COPY["validation.REQUIRED"];
    if (!password) nextFieldErrors.password = COPY["validation.REQUIRED"];
    if (nextFieldErrors.loginId || nextFieldErrors.password) {
      setFieldErrors(nextFieldErrors);
      setFormError(null);
      return;
    }

    setFieldErrors({});
    setFormError(null);
    setSubmitting(true);
    try {
      await api.request("POST", "/api/auth/login", { loginId: loginId.trim(), password });
      setPassword("");
      const view = await planStore.loadPlan({ fresh: true });
      if (view !== null) {
        navigate(RESULT_PATH, { replace: true });
      }
    } catch (caught) {
      setPassword("");
      setSubmitting(false);
      if (caught instanceof ApiError) {
        if (caught.kind === "network") {
          setFormError(COPY["common.networkError"]);
        } else if (caught.code === "INVALID_CREDENTIALS") {
          setFormError(COPY["auth.invalidCredentials"]);
        } else if (caught.code === "VALIDATION_FAILED" && caught.problem) {
          const mapped: { loginId?: string; password?: string } = {};
          for (const raw of caught.problem.errors) {
            const err = raw as ServerFieldError;
            if (err.field === "loginId" && !mapped.loginId) mapped.loginId = formatFieldError(err);
            if (err.field === "password" && !mapped.password) mapped.password = formatFieldError(err);
          }
          setFieldErrors(mapped);
        } else {
          setFormError(COPY["auth.loginFailed"]);
        }
      } else {
        setFormError(COPY["auth.loginFailed"]);
      }
    }
  }

  return (
    <div className="mx-auto flex min-h-[calc(100vh-3.5rem)] max-w-5xl items-center justify-center px-6 py-10">
      <form
        onSubmit={handleSubmit}
        noValidate
        className="flex w-full max-w-sm flex-col gap-4 rounded-2xl bg-surface p-8 shadow-sm"
      >
        <h1 className="text-xl font-bold text-text">로그인</h1>
        <p className="text-sm text-text-muted">모은 목표 저축 시뮬레이션을 시작해요.</p>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="loginId" className="text-sm font-medium text-text">
            아이디
          </label>
          <input
            id="loginId"
            type="text"
            autoComplete="username"
            placeholder="아이디를 입력해 주세요"
            value={loginId}
            aria-invalid={fieldErrors.loginId ? true : undefined}
            aria-describedby={fieldErrors.loginId ? "loginId-error" : undefined}
            onChange={(event) => setLoginId(event.target.value)}
            className="h-11 rounded-lg border border-border bg-surface px-3 text-base text-text outline-none focus:outline-2 focus:outline-primary"
          />
          {fieldErrors.loginId && <FieldError id="loginId-error" message={fieldErrors.loginId} />}
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="password" className="text-sm font-medium text-text">
            비밀번호
          </label>
          <input
            id="password"
            type="password"
            autoComplete="current-password"
            placeholder="비밀번호를 입력해 주세요"
            value={password}
            aria-invalid={fieldErrors.password ? true : undefined}
            aria-describedby={fieldErrors.password ? "password-error" : undefined}
            onChange={(event) => setPassword(event.target.value)}
            className="h-11 rounded-lg border border-border bg-surface px-3 text-base text-text outline-none focus:outline-2 focus:outline-primary"
          />
          {fieldErrors.password && <FieldError id="password-error" message={fieldErrors.password} />}
        </div>
        {formError && (
          <p role="alert" className="text-sm text-danger">
            {formError}
          </p>
        )}
        <Button type="submit" loading={submitting}>
          로그인
        </Button>
      </form>
    </div>
  );
}
