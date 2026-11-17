import { FieldError } from "./FieldError";

interface RadioOption<T extends string> {
  value: T;
  label: string;
}

interface RadioGroupProps<T extends string> {
  name: string;
  legend: string;
  options: readonly RadioOption<T>[];
  value: T | null;
  onChange: (value: T) => void;
  error?: string;
  errorId?: string;
}

export function RadioGroup<T extends string>({ name, legend, options, value, onChange, error, errorId }: RadioGroupProps<T>) {
  return (
    <fieldset className="flex flex-col gap-2" aria-describedby={error ? errorId : undefined}>
      <legend className="mb-1 text-sm font-medium text-text">{legend}</legend>
      <div className="flex flex-wrap gap-4">
        {options.map((option) => (
          <label key={option.value} className="inline-flex cursor-pointer items-center gap-2 text-sm text-text">
            <input
              type="radio"
              name={name}
              value={option.value}
              checked={value === option.value}
              onChange={() => onChange(option.value)}
              className="h-4 w-4 accent-primary"
            />
            {option.label}
          </label>
        ))}
      </div>
      {error && errorId && <FieldError id={errorId} message={error} />}
    </fieldset>
  );
}
