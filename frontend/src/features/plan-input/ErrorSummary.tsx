import { copy } from "@/copy/ko";

export function ErrorSummary({ count }: { count: number }) {
  if (count === 0) {
    return null;
  }
  return (
    <p role="alert" className="rounded-lg border border-danger bg-white px-4 py-3 text-sm text-danger">
      {copy.input.errorSummary(count)}
    </p>
  );
}
