import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { setNavigate } from "../api";

// 라우터 밖(래퍼·store)에서 쓰는 navigate를 라우터 안의 것으로 연결한다.
export default function NavigateBridge() {
  const navigate = useNavigate();
  useEffect(() => {
    setNavigate((to) => navigate(to));
    return () => setNavigate(null);
  }, [navigate]);
  return null;
}
