// copy/copy.ko.yml 값을 옮긴 상수. 테스트가 copy/copy.ko.yml과 비교해 어긋남을 잡는다.
export const COPY = {
  "common.networkError": "네트워크 연결을 확인하고 다시 시도해 주세요",
  "auth.invalidCredentials": "아이디 또는 비밀번호가 맞지 않아요",
  "auth.loginFailed": "로그인하지 못했어요. 잠시 후 다시 시도해 주세요",
  "auth.logoutFailed": "로그아웃하지 못했어요. 다시 시도해 주세요",
  "input.saveFailed": "입력을 저장하지 못했어요. 다시 시도해 주세요",
  "input.invalidAmountFormat": "금액은 숫자로 입력해 주세요",
  "plan.changedElsewhere": "계획이 다른 곳에서 바뀌었어요. 다시 저장하면 입력한 값으로 바뀌어요",
  "validation.REQUIRED": "값을 입력해 주세요",
  "validation.OUT_OF_RANGE": "{min}~{max} 사이로 입력해 주세요",
  "validation.NOT_INTEGER": "정수로 입력해 주세요",
  "validation.TOO_MANY_DECIMALS": "소수 {scale}자리까지 입력할 수 있어요",
  "unit.manwon": "{v}만원",
  "unit.won": "{v}원",
  "unit.month": "{y}년 {m}개월",
  "unit.pct": "연 {v}%",
  "unit.char": "{n}자",
  "goal.HOUSE": "내 집 마련",
  "goal.CAR": "자동차",
  "goal.WEDDING": "결혼",
  "goal.EDUCATION": "교육",
  "goal.TRAVEL": "여행",
  "goal.EMERGENCY_FUND": "비상금",
  "goal.CUSTOM": "직접 입력",
} as const satisfies Record<string, string>;

export type CopyMap = Record<string, string>;
