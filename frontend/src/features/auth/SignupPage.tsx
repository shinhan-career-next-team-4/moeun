// 회원가입 화면(FR-AUTH-04, D-107, #460). 계약 P-7 POST /api/auth/signup. 성공은 로그인 성공과 같은 흐름이다.
import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../../api";
import { ApiError } from "../../api/errors";
import { INPUT_PATH, LOGIN_PATH } from "../../app/routes";
import { COPY } from "../../shared/copy/copy";
import { Button } from "../../shared/ui";
import { formatFieldError, type ServerFieldError } from "../../shared/units/units";
import { AuthField, AuthFormCard, AuthSwitchLink } from "./AuthForm";
import { enterAfterAuth, mapServerFieldErrors } from "./authFlow";

type Field = "loginId" | "password" | "passwordConfirm";
type FieldErrors = Partial<Record<Field, string>>;

// P-7 422 INVALID_FORMAT과 같은 규칙. 서버가 최종 판단이고 여기서는 보내기 전에 걸러 준다.
const LOGIN_ID_PATTERN = /^[A-Za-z0-9]{4,20}$/;
const PASSWORD_MIN = 8;
const PASSWORD_MAX = 64;
// BCrypt는 72바이트까지만 쓴다. 서버도 넘으면 422 INVALID_FORMAT(한글은 24자까지)
const PASSWORD_MAX_UTF8_BYTES = 72;

function isValidPassword(password: string): boolean {
  if (password.length < PASSWORD_MIN || password.length > PASSWORD_MAX) return false;
  return new TextEncoder().encode(password).length <= PASSWORD_MAX_UTF8_BYTES;
}

const INVALID_FORMAT_COPY: Record<"loginId" | "password", string> = {
  loginId: COPY["auth.loginIdInvalidFormat"],
  password: COPY["auth.passwordInvalidFormat"],
};

function formatSignupFieldError(error: ServerFieldError): string {
  if (error.reason === "INVALID_FORMAT" && (error.field === "loginId" || error.field === "password")) {
    return INVALID_FORMAT_COPY[error.field];
  }
  return formatFieldError(error);
}

function validate(loginId: string, password: string, passwordConfirm: string): FieldErrors {
  const errors: FieldErrors = {};
  if (!loginId) errors.loginId = COPY["validation.REQUIRED"];
  else if (!LOGIN_ID_PATTERN.test(loginId)) errors.loginId = INVALID_FORMAT_COPY.loginId;

  if (!password) errors.password = COPY["validation.REQUIRED"];
  else if (!isValidPassword(password)) errors.password = INVALID_FORMAT_COPY.password;

  if (!passwordConfirm) errors.passwordConfirm = COPY["validation.REQUIRED"];
  else if (passwordConfirm !== password) errors.passwordConfirm = COPY["auth.passwordMismatch"];
  return errors;
}

export function SignupPage() {
  const navigate = useNavigate();
  const [loginId, setLoginId] = useState("");
  const [password, setPassword] = useState("");
  const [passwordConfirm, setPasswordConfirm] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;

    const trimmedId = loginId.trim();
    const nextFieldErrors = validate(trimmedId, password, passwordConfirm);
    setFieldErrors(nextFieldErrors);
    setFormError(null);
    if (Object.keys(nextFieldErrors).length > 0) return;

    setSubmitting(true);
    try {
      // 비밀번호 확인 값은 화면 안에서만 쓰고 보내지 않는다.
      await api.request("POST", "/api/auth/signup", { loginId: trimmedId, password });
    } catch (caught) {
      setSubmitting(false);
      showSignupError(caught);
      return;
    }

    // 여기부터는 가입이 끝나 로그인 상태다. 실패해도 다시 가입하게 두지 않는다(같은 아이디는 409).
    setPassword("");
    setPasswordConfirm("");
    try {
      await enterAfterAuth(trimmedId, navigate);
    } catch (caught) {
      // 401이면 래퍼가 이미 로그인 화면으로 보냈다. 그 밖의 조회 실패는 입력 화면이 다시 불러 안내한다.
      if (caught instanceof ApiError && caught.code === "UNAUTHENTICATED") return;
      navigate(INPUT_PATH, { replace: true });
    }
  }

  function showSignupError(caught: unknown) {
    if (!(caught instanceof ApiError)) {
      setFormError(COPY["auth.signupFailed"]);
    } else if (caught.kind === "network") {
      setFormError(COPY["common.networkError"]);
    } else if (caught.code === "DUPLICATE_LOGIN_ID") {
      setFieldErrors({ loginId: COPY["auth.duplicateLoginId"] });
    } else if (caught.code === "VALIDATION_FAILED" && caught.problem) {
      const mapped = mapServerFieldErrors(caught.problem.errors, ["loginId", "password"], formatSignupFieldError);
      if (Object.keys(mapped).length > 0) setFieldErrors(mapped);
      else setFormError(COPY["auth.signupFailed"]);
    } else {
      setFormError(COPY["auth.signupFailed"]);
    }
  }

  return (
    <AuthFormCard title="회원가입" description={COPY["auth.signupDescription"]} onSubmit={handleSubmit}>
      <AuthField
        id="loginId"
        label="아이디"
        type="text"
        autoComplete="username"
        placeholder="아이디를 입력해 주세요"
        value={loginId}
        onChange={setLoginId}
        hint={COPY["auth.loginIdRule"]}
        error={fieldErrors.loginId}
      />
      <AuthField
        id="password"
        label="비밀번호"
        type="password"
        autoComplete="new-password"
        placeholder="비밀번호를 입력해 주세요"
        value={password}
        onChange={setPassword}
        hint={COPY["auth.passwordRule"]}
        error={fieldErrors.password}
      />
      <AuthField
        id="passwordConfirm"
        label="비밀번호 확인"
        type="password"
        autoComplete="new-password"
        placeholder="비밀번호를 한 번 더 입력해 주세요"
        value={passwordConfirm}
        onChange={setPasswordConfirm}
        error={fieldErrors.passwordConfirm}
      />
      {formError && (
        <p role="alert" className="text-sm text-danger">
          {formError}
        </p>
      )}
      <Button type="submit" loading={submitting}>
        가입하기
      </Button>
      <AuthSwitchLink prompt={COPY["auth.toLoginPrompt"]} to={LOGIN_PATH} label="로그인" />
    </AuthFormCard>
  );
}
