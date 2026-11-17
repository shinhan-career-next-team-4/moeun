import { Suspense } from "react";

import { ResultScreen } from "@/features/plan/ResultScreen";

export default function ResultPage() {
  return (
    <Suspense>
      <ResultScreen />
    </Suspense>
  );
}
