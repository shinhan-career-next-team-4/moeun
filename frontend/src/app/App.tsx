import { Link, Outlet, Route, Routes } from "react-router-dom";
import { LOGIN_PATH, INPUT_PATH } from "./routes";

function Layout() {
  return (
    <>
      <header>
        <Link to={INPUT_PATH}>모은</Link>
      </header>
      <main>
        <Outlet />
      </main>
    </>
  );
}

// 라우터는 main.tsx가 감싼다. 화면 Issue가 각 경로의 element를 채운다.
export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path={INPUT_PATH} element={null} />
        <Route path={LOGIN_PATH} element={null} />
        <Route path="*" element={null} />
      </Route>
    </Routes>
  );
}
