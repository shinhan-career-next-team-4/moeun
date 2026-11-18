"use client";

import { useEffect, useState, type ReactNode } from "react";

import { copy } from "@/copy/ko";
import { ApiError } from "@/lib/api/client";
import { me } from "@/lib/api/endpoints";
import type { MeResponse } from "@/lib/api/types";

import { goToLogin } from "./goToLogin";
import { SessionContext } from "./session";

type GateState = { status: "checking" } | { status: "ok"; session: MeResponse } | { status: "error" };

/** 로그인한 사용자만 자식을 본다(FR-AUTH-02). 확인 중에는 아무것도 그리지 않아 보호 화면이 깜빡이지 않는다. */
export function AuthGate({ children }: { children: ReactNode }) {
  const [state, setState] = useState<GateState>({ status: "checking" });

  useEffect(() => {
    let active = true;
    me()
      .then((session) => active && setState({ status: "ok", session }))
      .catch((error: unknown) => {
        if (!active) return;
        if (error instanceof ApiError && error.status === 401) {
          goToLogin();
        } else {
          setState({ status: "error" });
        }
      });
    return () => {
      active = false;
    };
  }, []);

  if (state.status === "error") {
    return (
      <p role="alert" className="p-8 text-center text-sm text-danger">
        {copy.common.networkError}
      </p>
    );
  }
  if (state.status === "checking") {
    return null;
  }
  return <SessionContext.Provider value={state.session}>{children}</SessionContext.Provider>;
}
