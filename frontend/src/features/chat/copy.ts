// copy/copy.ko.yml 값을 옮긴 상수. 프론트에는 문구표 로더가 없어 값을 복사해 두고, 테스트가 파일과 비교해 어긋남을 잡는다.
export const COPY = {
  disclaimer:
    "이 서비스의 결과는 과거 데이터를 바탕으로 한 시뮬레이션이며 미래의 수익을 보장하지 않습니다. AI 설명은 참고용이며 계산 결과가 기준이에요.",
  alreadyExceeded: "이미 가진 자산이 목표 금액을 초과했어요. 목표 금액은 입력 수정에서 바꿀 수 있어요",
  pollingFailed: "답변을 불러오지 못하고 있어요. 잠시 후 다시 시도해 주세요",
  sendFailed: "메시지를 보내지 못했어요. 다시 시도해 주세요",
  validationRequired: "값을 입력해 주세요",
  validationOutOfRange: "{min}~{max} 사이로 입력해 주세요",
  unitChar: "{n}자",
} as const;

export const unitChar = (n: number): string => COPY.unitChar.replace("{n}", String(n));
