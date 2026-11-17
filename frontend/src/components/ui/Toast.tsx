"use client";

import { useEffect } from "react";

interface ToastProps {
  message: string;
  actionLabel?: string;
  onAction?: () => void;
  onClose: () => void;
  durationMs?: number;
}

export function Toast({ message, actionLabel, onAction, onClose, durationMs = 5000 }: ToastProps) {
  useEffect(() => {
    const timer = setTimeout(onClose, durationMs);
    return () => clearTimeout(timer);
  }, [onClose, durationMs]);

  return (
    <div role="status" className="fixed bottom-6 left-1/2 z-50 flex -translate-x-1/2 items-center gap-4 rounded-xl bg-text px-5 py-3 text-sm text-white shadow-lg">
      <span>{message}</span>
      {actionLabel && onAction && (
        <button type="button" onClick={onAction} className="font-semibold text-primary-soft underline-offset-2 hover:underline">
          {actionLabel}
        </button>
      )}
    </div>
  );
}
