# moeun frontend

목표 달성 시뮬레이션 서비스의 웹 클라이언트. 호출 대상은 Spring(`../backend`, 기본 `http://localhost:8080`)뿐이다. ai-server는 직접 부르지 않는다.

Next.js 16 (App Router) · React 19 · TypeScript strict · Tailwind CSS 4 · Vitest + Testing Library

## 절대 원칙
1. 금액·수익률·기간을 프론트에서 계산하지 않는다. 숫자는 Spring 응답을 그대로 보여 준다(NFR-INT-01).
2. `any` 금지. 타입 추론을 최대한 활용한다.

## 명령
```bash
npm run dev          # http://localhost:5173 (backend CORS 기본값과 같은 포트)
npm run lint
npm run type-check   # next typegen && tsc --noEmit
npm run test:run     # 한 번 실행. 감시 모드는 npm test
npm run build
```

## 구조
| 위치 | 역할 |
|---|---|
| `src/app/` | App Router 라우트, 레이아웃, 전역 CSS |
| `src/**/*.test.{ts,tsx}` | Vitest 테스트(대상 파일 옆에 둔다) |

경로 별칭: `@/*` → `src/*`

## 규칙과 참조
- 코드 작성 규칙, 리뷰 체크리스트, Definition of Done: `.claude/AGENT.md`
- 코드 철학(Frontend Fundamentals 4대 원칙): `.claude/SOUL.md`
- 코드 품질 판단 기준과 예시: `.claude/knowledge/code_quality.md`. 코드를 쓰기 전에 읽는다.
- API 계약: backend의 `./gradlew openapi3` 결과 `../backend/build/api-spec/openapi3.yaml`

## 완료 조건
작업을 끝내기 전에 `lint`, `type-check`, `test:run`이 모두 통과해야 한다. 새 컴포넌트와 유틸에는 테스트를 같이 작성한다.
