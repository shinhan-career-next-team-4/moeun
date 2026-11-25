import type { ComponentPropsWithRef } from "react";

type Variant = "primary" | "secondary" | "ghost";

const VARIANT_CLASS: Record<Variant, string> = {
  primary: "rounded-xl bg-primary text-white shadow-[0_6px_14px_rgb(20_30_97/0.28)] hover:bg-primary-strong disabled:bg-border disabled:text-text-muted",
  secondary: "soft-pill text-text hover:bg-surface-subtle disabled:text-text-muted",
  ghost: "text-primary hover:bg-surface-muted disabled:text-text-muted",
};

interface ButtonProps extends ComponentPropsWithRef<"button"> {
  variant?: Variant;
  loading?: boolean;
}

export function Button({
  variant = "primary",
  loading = false,
  disabled,
  className = "",
  type = "button",
  ...rest
}: ButtonProps) {
  return (
    <button
      type={type}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={`inline-flex h-11 items-center justify-center rounded-lg px-5 text-sm font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary disabled:cursor-not-allowed ${VARIANT_CLASS[variant]} ${className}`}
      {...rest}
    />
  );
}
