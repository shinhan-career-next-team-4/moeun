import { useState } from "react";
import { COPY, type CopyMap } from "../../shared/copy/copy";
import { Button, UnitInput } from "../../shared/ui";
import { isBlank, manwonToWon, normalizeNumberInput, wonToManwon } from "../../shared/units/units";
import { CompareTable } from "./CompareTable";
import type { ComparisonView, ConditionChanges, ConditionKey } from "./types";

/** ② 탭에서 고를 수 있는 조건. 비중은 ①에서 따로 받는다(와이어프레임 03) */
const PICKABLE: { key: Exclude<ConditionKey, "savingsRatioPct">; labelKey: string; unit: string }[] = [
  { key: "goalAmount", labelKey: "condition.goalAmount", unit: "만원" },
  { key: "monthlySavingAmount", labelKey: "condition.monthlySavingAmount", unit: "만원" },
  { key: "savingsRateInputPct", labelKey: "condition.savingsRateInputPct", unit: "%" },
  { key: "targetPeriodMonths", labelKey: "condition.targetPeriodMonths", unit: "개월" },
];

interface CompareSectionProps {
  /** 현재 계획 조건의 display 문자열. `현재 …` 표기에 쓴다 */
  currentDisplay: Record<string, string>;
  comparison: ComparisonView | null;
  loading?: boolean;
  /** 비교를 못 했을 때 보여 줄 안내(서버 미연결·실패) */
  error?: string | null;
  onCompare(changes: ConditionChanges): void;
  onReset(): void;
  /** 대안 카드의 '비교하기'가 채워 넣은 조건. 바뀌면 ResultPage가 key로 다시 그린다 */
  initialChanges?: ConditionChanges;
  /** 지금 보이는 비교 결과를 만든 조건. 입력이 이것과 같을 때만 적용할 수 있다(C12a-9-2) */
  comparedChanges?: ConditionChanges | null;
  /** 부모가 적용을 허용하는지(비교한 뒤 내 계획이 그대로이고 적용이 거절되지 않음) */
  applyEnabled?: boolean;
  onApply?(changes: ConditionChanges): void;
}

type Inputs = Partial<Record<ConditionKey, string>>;

interface RatioInput {
  savings: string;
  etf: string;
}

/** 입력칸 값을 요청 값으로 바꾼다. 단위 변환만 하고 금액을 계산하지 않는다(C12a-5-6). */
function toChanges(inputs: Inputs, ratio: RatioInput, useRatio: boolean): ConditionChanges | { error: string } {
  const changes: ConditionChanges = {};

  for (const { key } of PICKABLE) {
    const raw = inputs[key];
    if (raw === undefined || isBlank(raw)) continue;
    if (key === "goalAmount" || key === "monthlySavingAmount") {
      const won = manwonToWon(raw);
      if (!won.ok) return { error: COPY["input.invalidAmountFormat"] };
      changes[key] = won.won;
      continue;
    }
    const value = Number(normalizeNumberInput(raw));
    if (!Number.isFinite(value)) return { error: COPY["input.invalidAmountFormat"] };
    changes[key] = value;
  }

  if (useRatio) {
    const savings = Number(normalizeNumberInput(ratio.savings));
    const etf = Number(normalizeNumberInput(ratio.etf));
    const bothInteger = Number.isInteger(savings) && Number.isInteger(etf);
    if (!bothInteger || savings < 0 || etf < 0 || savings + etf !== 100) {
      return { error: COPY["compare.ratioSumError"] };
    }
    changes.savingsRatioPct = savings;
  }

  if (Object.keys(changes).length === 0) return { error: COPY["compare.noChanges"] };
  return changes;
}

function sameChanges(a: ConditionChanges, b: ConditionChanges): boolean {
  const keys = Object.keys(a) as ConditionKey[];
  return keys.length === Object.keys(b).length && keys.every((key) => a[key] === b[key]);
}

/** 넘겨받은 조건을 입력칸 문자열로 되돌린다. 단위 변환만 한다(C12a-5-6). */
function toInputs(changes: ConditionChanges): Inputs {
  const inputs: Inputs = {};
  for (const { key } of PICKABLE) {
    const value = changes[key];
    if (value === undefined) continue;
    inputs[key] = key === "goalAmount" || key === "monthlySavingAmount" ? wonToManwon(value) : String(value);
  }
  return inputs;
}

function toRatio(changes: ConditionChanges): RatioInput {
  const savings = changes.savingsRatioPct;
  if (savings === undefined) return { savings: "", etf: "" };
  return { savings: String(savings), etf: String(100 - savings) };
}

/** 비중 직접 입력(①, W-13). 한쪽을 적으면 반대쪽은 사용자가 적는다(자동 보정은 Q-105 결정 대기) */
function RatioInputs({ value, onChange }: { value: RatioInput; onChange: (next: RatioInput) => void }) {
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="text-sm font-semibold text-text">{COPY["compare.ratioTitle"]}</legend>
      <div className="grid gap-3 sm:grid-cols-2">
        <UnitInput
          id="compare-ratio-savings"
          label={COPY["compare.ratioSavings"]}
          unit="%"
          value={value.savings}
          onChange={(savings) => onChange({ ...value, savings })}
        />
        <UnitInput
          id="compare-ratio-etf"
          label={COPY["compare.ratioEtf"]}
          unit="%"
          value={value.etf}
          onChange={(etf) => onChange({ ...value, etf })}
        />
      </div>
      <p className="text-xs text-text-muted">{COPY["compare.ratioHelp"]}</p>
    </fieldset>
  );
}

/** 비교 영역(FR-CMP-01~03, 와이어프레임 03). 적용 전까지 내 계획은 바뀌지 않는다. */
export function CompareSection({
  currentDisplay,
  comparison,
  loading = false,
  error = null,
  onCompare,
  onReset,
  initialChanges,
  comparedChanges = null,
  applyEnabled = false,
  onApply,
}: CompareSectionProps) {
  const copy: CopyMap = COPY;
  const initial = initialChanges ?? {};
  const [picked, setPicked] = useState<ConditionKey[]>(Object.keys(initial) as ConditionKey[]);
  const [inputs, setInputs] = useState<Inputs>(() => toInputs(initial));
  const [ratio, setRatio] = useState<RatioInput>(() => toRatio(initial));
  const [inputError, setInputError] = useState<string | null>(null);
  const useRatio = picked.includes("savingsRatioPct");
  // 고른 조건만 요청 값이 된다. 입력을 고치면 다시 비교할 때까지 적용할 수 없다
  const pickedInputs = Object.fromEntries(Object.entries(inputs).filter(([key]) => picked.includes(key as ConditionKey)));
  const currentChanges = toChanges(pickedInputs, ratio, useRatio);
  // 비교 요청 중에는 지금 보이는 결과가 곧 바뀔 수 있어 적용하지 않는다
  const canApply =
    !loading &&
    applyEnabled &&
    comparison?.applicable === true &&
    comparedChanges !== null &&
    !("error" in currentChanges) &&
    sameChanges(currentChanges, comparedChanges);

  function toggle(key: ConditionKey) {
    setPicked((prev) => (prev.includes(key) ? prev.filter((item) => item !== key) : [...prev, key]));
  }

  function compare() {
    const result = currentChanges;
    if ("error" in result) {
      setInputError(result.error);
      return;
    }
    setInputError(null);
    onCompare(result);
  }

  function reset() {
    setPicked([]);
    setInputs({});
    setRatio({ savings: "", etf: "" });
    setInputError(null);
    onReset();
  }

  return (
    <section aria-label="조건 변경 비교" className="soft-card flex flex-col gap-4 p-6">
      <div>
        <h2 className="text-base font-semibold text-text">{COPY["compare.title"]}</h2>
        <p className="mt-1 text-xs leading-relaxed text-text-muted">{COPY["compare.notice"]}</p>
      </div>

      <fieldset className="flex flex-col gap-3">
        <legend className="text-sm font-semibold text-text">{COPY["compare.conditionTitle"]}</legend>
        <div className="flex flex-wrap gap-2">
          {[...PICKABLE.map(({ key, labelKey }) => ({ key: key as ConditionKey, labelKey })), {
            key: "savingsRatioPct" as ConditionKey,
            labelKey: "condition.savingsRatioPct",
          }].map(({ key, labelKey }) => (
            <label
              key={key}
              className={`flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-1.5 text-sm ${
                picked.includes(key) ? "border-primary bg-primary-soft text-primary-strong" : "border-border text-text"
              }`}
            >
              <input
                type="checkbox"
                className="h-4 w-4"
                checked={picked.includes(key)}
                onChange={() => toggle(key)}
              />
              {copy[labelKey]}
            </label>
          ))}
        </div>
        <p className="text-xs text-text-muted">{COPY["compare.conditionHelp"]}</p>
      </fieldset>

      {picked.length > 0 && (
        <div className="grid gap-3 sm:grid-cols-2">
          {PICKABLE.filter(({ key }) => picked.includes(key)).map(({ key, labelKey, unit }) => (
            <UnitInput
              key={key}
              id={`compare-${key}`}
              label={copy[labelKey]}
              unit={unit}
              inputMode={key === "savingsRateInputPct" ? "decimal" : "numeric"}
              value={inputs[key] ?? ""}
              onChange={(next) => setInputs((prev) => ({ ...prev, [key]: next }))}
              help={
                currentDisplay[`conditions.${key}`] || currentDisplay[key]
                  ? COPY["compare.currentValue"].replace(
                      "{value}",
                      currentDisplay[`conditions.${key}`] ?? currentDisplay[key],
                    )
                  : undefined
              }
            />
          ))}
        </div>
      )}

      {useRatio && <RatioInputs value={ratio} onChange={setRatio} />}

      {inputError && (
        <p role="alert" className="text-sm text-danger">
          {inputError}
        </p>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <Button onClick={compare} loading={loading} disabled={picked.length === 0}>
          {COPY["compare.submit"]}
        </Button>
        <Button variant="secondary" onClick={reset}>
          {COPY["compare.reset"]}
        </Button>
        <Button
          variant="ghost"
          disabled={!canApply}
          onClick={() => {
            if (canApply && comparedChanges) onApply?.(comparedChanges);
          }}
        >
          {COPY["compare.apply"]}
        </Button>
      </div>

      {error && (
        <p role="status" className="text-sm text-text-muted">
          {error}
        </p>
      )}

      {comparison &&
        (comparison.applicable ? (
          <CompareTable view={comparison} />
        ) : (
          <p role="status" className="text-sm text-text-muted">
            {COPY["compare.notApplicable"]}
          </p>
        ))}
    </section>
  );
}
