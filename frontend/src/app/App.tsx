import { useState } from "react";
import { Link, Navigate, Outlet, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { api, planStore } from "../api";
import { ApiError } from "../api/errors";
import { forgetDisplayName, LoginPage, readDisplayName } from "../features/auth";
import { PlanInputPage } from "../features/input";
import { ProjectionDemoPage } from "../features/projection";
import { ResultPage } from "../features/result";
import { COPY } from "../shared/copy/copy";
import { Button } from "../shared/ui";
import { INPUT_PATH, LOGIN_PATH, PROJECTION_DEMO_PATH, RESULT_PATH } from "./routes";

function Layout() {
  const location = useLocation();
  const navigate = useNavigate();
  const [logoutError, setLogoutError] = useState<string | null>(null);
  // 데모 초기화는 확인을 한 번 더 받는다. 계획과 대화가 모두 지워지고 되돌릴 수 없다
  const [confirmingReset, setConfirmingReset] = useState(false);
  const isLogin = location.pathname === LOGIN_PATH;
  const displayName = isLogin ? null : readDisplayName();

  async function handleLogout() {
    setLogoutError(null);
    try {
      await api.request("POST", "/api/auth/logout");
      forgetDisplayName();
      navigate(LOGIN_PATH, { replace: true });
    } catch (caught) {
      if (caught instanceof ApiError && caught.code === "UNAUTHENTICATED") return;
      setLogoutError(COPY["auth.logoutFailed"]);
    }
  }

  async function handleDemoReset() {
    setLogoutError(null);
    try {
      await api.request("POST", "/api/auth/demo-reset");
      setConfirmingReset(false);
      planStore.clear();
      navigate(INPUT_PATH, { replace: true });
    } catch (caught) {
      if (caught instanceof ApiError && caught.code === "UNAUTHENTICATED") return;
      setConfirmingReset(false);
      setLogoutError(COPY["demo.resetFailed"]);
    }
  }

  return (
    <>
      <header className="border-b border-border bg-surface">
        <div className="relative mx-auto flex h-14 items-center justify-between px-6 md:block md:px-0">
          <Link
            to={INPUT_PATH}
            aria-label="모은"
            className="text-2xl font-black leading-none tracking-tight text-text md:absolute md:left-[13.8%] md:top-1/2 md:-translate-x-1/2 md:-translate-y-1/2">
            모은
            {/* 로고의 점은 메인 컬러. 읽기 이름은 "모은" 그대로 */}
            <span aria-hidden="true" className="ml-px text-[1.2em] text-primary">
              .
            </span>
          </Link>
          {!isLogin && (
            <div className="flex items-center gap-3 md:absolute md:left-[87.5%] md:top-1/2 md:-translate-x-1/2 md:-translate-y-1/2">
              {displayName && (
                // 숨은 동작: 아이디를 세 번 연달아 누르면 데모 초기화 확인이 뜬다(브라우저의 연속 클릭 수 detail 사용)
                <span
                  className="select-none text-sm text-text-muted"
                  onClick={(event) => {
                    if (event.detail === 3) setConfirmingReset(true);
                  }}>
                  {displayName} 님
                </span>
              )}
              {confirmingReset && (
                <span role="group" aria-label={COPY["demo.reset"]} className="flex items-center gap-2 text-sm">
                  <span className="text-text-muted">{COPY["demo.resetConfirm"]}</span>
                  <Button variant="secondary" className="h-9 px-3" onClick={handleDemoReset}>
                    {COPY["demo.resetYes"]}
                  </Button>
                  <Button variant="ghost" className="h-9 px-3" onClick={() => setConfirmingReset(false)}>
                    {COPY["demo.resetCancel"]}
                  </Button>
                </span>
              )}
              <Button variant="ghost" className="h-9 px-3" onClick={handleLogout}>
                로그아웃
              </Button>
            </div>
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
        {/* 예상 자산 그래프 데모(#298): mock 데이터라 개발 환경에서만 연다 */}
        {import.meta.env.DEV && <Route path={PROJECTION_DEMO_PATH} element={<ProjectionDemoPage />} />}
        <Route path="*" element={<Navigate to={INPUT_PATH} replace />} />
      </Route>
    </Routes>
  );
}
