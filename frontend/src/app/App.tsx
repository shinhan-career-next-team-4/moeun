import { useState } from "react";
import { Link, Navigate, Outlet, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { api } from "../api";
import { ApiError } from "../api/errors";
import { LoginPage } from "../features/auth";
import { PlanInputPage } from "../features/input";
import { ResultPage } from "../features/result";
import { COPY } from "../shared/copy/copy";
import { Button } from "../shared/ui";
import { INPUT_PATH, LOGIN_PATH, RESULT_PATH } from "./routes";

function Layout() {
  const location = useLocation();
  const navigate = useNavigate();
  const [logoutError, setLogoutError] = useState<string | null>(null);
  const isLogin = location.pathname === LOGIN_PATH;

  async function handleLogout() {
    setLogoutError(null);
    try {
      await api.request("POST", "/api/auth/logout");
      navigate(LOGIN_PATH, { replace: true });
    } catch (caught) {
      if (caught instanceof ApiError && caught.code === "UNAUTHENTICATED") return;
      setLogoutError(COPY["auth.logoutFailed"]);
    }
  }

  return (
    <>
      <header className="border-b border-border bg-surface">
        <div className="mx-auto flex h-14 max-w-5xl items-center justify-between px-6">
          <Link to={INPUT_PATH} className="text-base font-bold text-text">
            모은
          </Link>
          {!isLogin && (
            <Button variant="ghost" className="h-9 px-3" onClick={handleLogout}>
              로그아웃
            </Button>
          )}
        </div>
      </header>
      <main>
        {logoutError && (
          <p role="alert" className="mx-auto max-w-5xl px-6 pt-4 text-sm text-danger">
            {logoutError}
          </p>
        )}
        <Outlet />
      </main>
    </>
  );
}

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path={INPUT_PATH} element={<PlanInputPage />} />
        <Route path={LOGIN_PATH} element={<LoginPage />} />
        <Route path={RESULT_PATH} element={<ResultPage />} />
        <Route path="*" element={<Navigate to={INPUT_PATH} replace />} />
      </Route>
    </Routes>
  );
}
