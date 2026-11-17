import type { InputRules } from "@/lib/api/types";

/** 테스트용: 백엔드 rules.yml과 같은 값(원 단위) */
export const testRules: InputRules = {
  fields: {
    goalCustomName: { required: false, min: 1, max: 100, unit: "CHAR" },
    goalAmount: { required: true, min: 10_000, max: 1_000_000_000, unit: "KRW", step: 10_000 },
    targetPeriodMonths: { required: false, min: 1, max: 600, unit: "MONTH" },
    heldAssets: { required: true, min: 0, max: 1_000_000_000, unit: "KRW", step: 10_000 },
    monthlySaving: { required: false, min: 10_000, max: 10_000_000, unit: "KRW", step: 10_000 },
    savingsRatePct: { required: false, min: 0, max: 50, unit: "PERCENT", scale: 2 },
    existingSavingsMonthly: { required: false, min: 0, max: 10_000_000, unit: "KRW", step: 10_000 },
    existingEtfMonthly: { required: false, min: 0, max: 10_000_000, unit: "KRW", step: 10_000 },
    savingsRatioPct: { required: false, min: 0, max: 100, unit: "PERCENT", scale: 0 },
  },
  goalTypes: [
    { code: "JEONSE", defaultAmount: 100_000_000, defaultPeriodMonths: 24 },
    { code: "WEDDING", defaultAmount: 30_000_000, defaultPeriodMonths: 12 },
    { code: "INDEPENDENCE", defaultAmount: 30_000_000, defaultPeriodMonths: 36 },
    { code: "TRAVEL", defaultAmount: 2_000_000, defaultPeriodMonths: 6 },
    { code: "CUSTOM" },
  ],
  periodPresets: [3, 6, 12, 24, 36],
  baseSavingsRatioPct: 50,
};
