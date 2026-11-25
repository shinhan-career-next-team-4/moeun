// 로그인(P-1)·회원가입(P-7)이 함께 쓰는 흐름. 두 화면이 같은 진입 분기와 서버 422 처리를 갖게 한다.
import type { NavigateFunction } from "react-router-dom";
import { planStore } from "../../api";
import { RESULT_PATH } from "../../app/routes";
import { formatFieldError, type ServerFieldError } from "../../shared/units/units";
import { rememberDisplayName } from "./displayName";

/**
 * 인증 성공 뒤 진입을 가른다. 다른 탭 알림(D-078)은 래퍼가 이미 보냈다.
 * 계획이 있으면 결과 화면으로 가고, 없으면(409 NO_PLAN) 공유 store가 입력 화면으로 보낸다.
 * 조회 실패는 그대로 던진다(호출 화면이 처리한다).
 */
export async function enterAfterAuth(loginId: string, navigate: NavigateFunction): Promise<void> {
  rememberDisplayName(loginId);
  const view = await planStore.loadPlan({ fresh: true });
  if (view !== null) {
    navigate(RESULT_PATH, { replace: true });
  }
}

/** 서버 422 errors[]를 칸별 첫 안내 하나로 모은다. 모르는 칸은 버린다. */
export function mapServerFieldErrors<F extends string>(
  errors: readonly unknown[],
  fields: readonly F[],
  format: (error: ServerFieldError) => string = formatFieldError,
): Partial<Record<F, string>> {
  const mapped: Partial<Record<F, string>> = {};
  for (const raw of errors) {
    const error = raw as ServerFieldError;
    const field = fields.find((name) => name === error.field);
    if (field !== undefined && mapped[field] === undefined) mapped[field] = format(error);
  }
  return mapped;
}
