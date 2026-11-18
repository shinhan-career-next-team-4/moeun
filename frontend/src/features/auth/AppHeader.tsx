"use client";

import { Button } from "@/components/ui";
import { copy } from "@/copy/ko";
import { logout } from "@/lib/api/endpoints";

import { goToLogin } from "./goToLogin";
import { useSession } from "./session";

export function AppHeader() {
  const { loginId } = useSession();

  async function handleLogout() {
    try {
      await logout();
    } finally {
      goToLogin();
    }
  }

  return (
    <header className="border-b border-border bg-surface">
      <div className="mx-auto flex h-14 max-w-5xl items-center justify-between px-6">
        <span className="text-base font-bold text-text">{copy.appName}</span>
        <div className="flex items-center gap-3 text-sm text-text-muted">
          <span>
            {loginId} {copy.auth.userSuffix}
          </span>
          <Button variant="ghost" className="h-9 px-3" onClick={handleLogout}>
            {copy.auth.logout}
          </Button>
        </div>
      </div>
    </header>
  );
}
