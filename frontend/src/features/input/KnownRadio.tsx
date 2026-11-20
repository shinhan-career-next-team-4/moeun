interface KnownRadioProps {
  name: string;
  label: string;
  yesLabel: string;
  noLabel: string;
  value: boolean;
  onChange: (known: boolean) => void;
}

/** "정해져 있나요?"·"알고 있나요?" 두 갈래 라디오(와이어프레임 02). 값을 모르면 화면이 대략값을 채워 준다(D-084). */
export function KnownRadio({ name, label, yesLabel, noLabel, value, onChange }: KnownRadioProps) {
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="text-sm font-medium text-text">{label}</legend>
      <div className="flex flex-wrap gap-4">
        {[
          { known: true, text: yesLabel },
          { known: false, text: noLabel },
        ].map((option) => (
          <label key={option.text} className="flex items-center gap-1.5 text-sm text-text">
            <input
              type="radio"
              name={name}
              className="size-4 accent-primary"
              checked={value === option.known}
              onChange={() => onChange(option.known)}
            />
            {option.text}
          </label>
        ))}
      </div>
    </fieldset>
  );
}
