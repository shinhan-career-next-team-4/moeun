import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../../api";
import { ApiError } from "../../api/errors";
import { SIGNUP_PATH } from "../../app/routes";
import { COPY } from "../../shared/copy/copy";
import { Button } from "../../shared/ui";
import { AuthField, AuthFormCard, AuthSwitchLink } from "./AuthForm";
import { enterAfterAuth, mapServerFieldErrors } from "./authFlow";

type Field = "loginId" | "password";
const FIELDS: readonly Field[] = ["loginId", "password"];

export function LoginPage() {
  const navigate = useNavigate();
  const [loginId, setLoginId] = useState("");
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<Field, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;

    const nextFieldErrors: Partial<Record<Field, string>> = {};
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
      await enterAfterAuth(loginId.trim(), navigate);
    } catch (caught) {
      setPassword("");
      setSubmitting(false);
      if (caught instanceof ApiError) {
        if (caught.kind === "network") {
          setFormError(COPY["common.networkError"]);
        } else if (caught.code === "INVALID_CREDENTIALS") {
          setFormError(COPY["auth.invalidCredentials"]);
        } else if (caught.code === "VALIDATION_FAILED" && caught.problem) {
          setFieldErrors(mapServerFieldErrors(caught.problem.errors, FIELDS));
        } else {
          setFormError(COPY["auth.loginFailed"]);
        }
      } else {
        setFormError(COPY["auth.loginFailed"]);
      }
    }
  }

  return (
    <AuthFormCard title="로그인" description="모은 목표 저축 시뮬레이션을 시작해요." onSubmit={handleSubmit}>
      <AuthField
        id="loginId"
        label="아이디"
        type="text"
        autoComplete="username"
        placeholder="아이디를 입력해 주세요"
        value={loginId}
        onChange={setLoginId}
        error={fieldErrors.loginId}
      />
      <AuthField
        id="password"
        label="비밀번호"
        type="password"
        autoComplete="current-password"
        placeholder="비밀번호를 입력해 주세요"
        value={password}
        onChange={setPassword}
        error={fieldErrors.password}
      />
      {formError && (
        <p role="alert" className="text-sm text-danger">
          {formError}
        </p>
      )}
      <Button type="submit" loading={submitting}>
        로그인
      </Button>
      <AuthSwitchLink prompt={COPY["auth.toSignupPrompt"]} to={SIGNUP_PATH} label="회원가입" />
    </AuthFormCard>
  );
}
