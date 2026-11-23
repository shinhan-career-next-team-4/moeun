// copy/copy.ko.yml 값을 옮긴 상수. 프론트에는 문구표 로더가 없어 값을 복사해 두고, 테스트가 파일과 비교해 어긋남을 잡는다.
export const COPY = {
  disclaimer:
    "본 결과는 입력하신 조건과 과거 데이터를 바탕으로 한 모의 계산이며, 실제 수령액이나 수익을 보장하지 않습니다. 특정 금융상품의 가입을 권유하지 않으며, 환율 변동은 반영하지 않았습니다. 투자 결정은 본인의 판단과 책임하에 이루어져야 합니다. AI 설명은 참고용이며 계산 결과가 기준이에요.",
  alreadyExceeded: "이미 가진 자산이 목표 금액을 넘었어요. 대화로 목표 금액을 더 높게 바꿔 볼 수 있어요.",
  pollingFailed: "답변을 불러오지 못하고 있어요. 잠시 후 다시 시도해 주세요",
  sendFailed: "메시지를 보내지 못했어요. 다시 시도해 주세요",
  panelTitle: "AI 설명 · 대화",
  panelNotice: "숫자는 계산 엔진이 만들고, AI는 설명만 해요. 계산 결과가 먼저 보이고 설명은 뒤이어 도착해요.",
  panelOpen: "AI 설명",
  panelClose: "패널 접기",
  panelUnread: "새 설명이 도착했어요",
  emptyGuide: "바꿔 보고 싶은 조건을 말해 주세요. 예를 들어 이렇게 물어볼 수 있어요.",
  typing: "답변을 쓰는 중이에요",
  inputPlaceholder: "궁금한 점을 입력해 주세요",
  send: "보내기",
  retryExplanation: "설명 다시 요청",
  retryFailed: "다시 시도",
  validationRequired: "값을 입력해 주세요",
  validationOutOfRange: "{min}~{max} 사이로 입력해 주세요",
  unitChar: "{n}자",
} as const;

export const unitChar = (n: number): string => COPY.unitChar.replace("{n}", String(n));
