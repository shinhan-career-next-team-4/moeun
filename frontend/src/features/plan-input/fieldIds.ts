import type { FormField } from "./validateForm";

/** 오류 칸 포커스용: 검증 필드 → 화면 요소 id */
export const FIELD_ELEMENT_ID: Record<FormField, string> = {
  goalType: "goalType",
  goalCustomName: "goalCustomName",
  goalAmount: "goalAmount",
  heldAssets: "heldAssets",
  targetPeriodMonths: "periodYears",
  monthlySaving: "monthlySaving",
  savingsRatePct: "savingsRatePct",
  existingSavingsMonthly: "existingSavings",
  existingEtfMonthly: "existingEtf",
  ratioChoice: "ratioChoice-BASE",
};
