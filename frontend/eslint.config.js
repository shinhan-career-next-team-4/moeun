import js from "@eslint/js";
import reactHooks from "eslint-plugin-react-hooks";
import globals from "globals";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist", "node_modules"] },
  {
    files: ["**/*.{ts,tsx}"],
    extends: [js.configs.recommended, ...tseslint.configs.recommended, reactHooks.configs.flat.recommended],
    languageOptions: { globals: globals.browser },
    rules: {
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_", varsIgnorePattern: "^_" }],
    },
  },
  {
    // 설정 파일은 Node에서 돈다.
    files: ["vite.config.ts", "eslint.config.js"],
    languageOptions: { globals: globals.node },
  },
  {
    // 수용 테스트는 any·Node 모듈(node:fs, process)을 쓴다. 앱 빌드(tsc) 대상에서도 빠져 있다.
    files: ["src/**/__tests__/**"],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    rules: { "@typescript-eslint/no-explicit-any": "off" },
  },
);
