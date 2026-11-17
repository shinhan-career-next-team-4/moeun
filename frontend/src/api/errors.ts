export interface ProblemBody {
  type?: string;
  title?: string;
  status?: number;
  code?: string;
  errors: unknown[];
  [key: string]: unknown;
}

export const NETWORK_ERROR_KEY = "common.networkError";

/**
 * 래퍼가 던지는 유일한 오류. 분기는 HTTP 상태가 아니라 code로 한다.
 * - problem: application/problem+json 본문을 읽었다(status, code, problem.errors).
 * - network: 연결 실패·프록시 오류·JSON이 아닌 본문. code가 없고 messageKey로 안내한다.
 */
export class ApiError extends Error {
  readonly kind: "problem" | "network";
  readonly status: number | null;
  readonly code: string | null;
  readonly problem: ProblemBody | null;
  readonly messageKey: string | null;

  private constructor(
    kind: "problem" | "network",
    message: string,
    fields: { status: number | null; code: string | null; problem: ProblemBody | null; messageKey: string | null },
    cause?: unknown,
  ) {
    super(message, { cause });
    this.name = "ApiError";
    this.kind = kind;
    this.status = fields.status;
    this.code = fields.code;
    this.problem = fields.problem;
    this.messageKey = fields.messageKey;
  }

  static fromProblem(status: number, problem: ProblemBody): ApiError {
    const code = typeof problem.code === "string" ? problem.code : null;
    return new ApiError("problem", `${status} ${code ?? ""}`.trim(), { status, code, problem, messageKey: null });
  }

  static network(cause?: unknown): ApiError {
    return new ApiError(
      "network",
      NETWORK_ERROR_KEY,
      { status: null, code: null, problem: null, messageKey: NETWORK_ERROR_KEY },
      cause,
    );
  }
}
