import type { InputRules } from "@/lib/api/types";

import type { PlanForm } from "../formState";
import type { FormErrors } from "../validateForm";

export interface SectionProps {
  form: PlanForm;
  errors: FormErrors;
  rules: InputRules;
  update: (patch: Partial<PlanForm>) => void;
}
