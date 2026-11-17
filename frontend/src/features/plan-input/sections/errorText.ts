import { copy } from "@/copy/ko";

import type { FormErrorCode } from "../validateForm";

export function errorText(code: FormErrorCode | undefined): string | undefined {
  return code === undefined ? undefined : copy.errors[code];
}
