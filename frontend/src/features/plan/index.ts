// 결과 화면 공개 API. 계산 담당(결과 본문)과 B팀(대화)이 이 모듈을 가져다 쓴다. 사용법은 README.md
export { ApplyConfirmModal, type ApplyRow } from "./ApplyConfirmModal";
export { Disclaimer } from "./Disclaimer";
export { PlanConditionBar } from "./PlanConditionBar";
export { PlanProvider, usePlan, type ApplyResult, type UndoResult } from "./PlanProvider";
