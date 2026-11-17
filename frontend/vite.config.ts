import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// 같은 사이트 전제(README): 브라우저는 /api/... 상대 경로만 부르고, 개발 서버가 Spring으로 넘겨 준다.
const apiTarget = process.env.VITE_API_PROXY_TARGET ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": { target: apiTarget },
    },
  },
  test: {
    // frontend/src/mocks/mocks.test.mjs 는 node --test 용이라 vitest가 집지 않게 좁힌다.
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
