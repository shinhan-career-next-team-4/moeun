"use client";

import { createContext, useContext } from "react";

import type { MeResponse } from "@/lib/api/types";

export const SessionContext = createContext<MeResponse | null>(null);

/** AuthGate 안에서만 쓴다. 로그인한 사용자의 아이디와 계획 보유 여부. */
export function useSession(): MeResponse {
  const session = useContext(SessionContext);
  if (session === null) {
    throw new Error("useSession은 AuthGate 안에서만 쓸 수 있어요.");
  }
  return session;
}
