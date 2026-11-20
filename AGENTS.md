# AGENTS.md

Codex 등 AGENTS.md를 읽는 코딩 에이전트용 안내. **규칙의 원본은 `CLAUDE.md`다.** 작업 전에 루트 `CLAUDE.md`와 작업할 폴더의 `CLAUDE.md`(`backend/CLAUDE.md`, `ai-server/CLAUDE.md`)를 읽고 그대로 따른다.

핵심만 다시 적는다:

1. **모든 코드 변경은 GitHub Issue에서 시작해 PR로 끝난다.** Issue가 없으면 코드를 고치지 않고 먼저 Issue를 만든다.
2. 브랜치: 최신 `develop`에서 `<type>/<issue번호>-<짧은-영문-설명>`.
3. Issue의 수용 기준을 **테스트로 먼저** 쓰고, 그 테스트를 통과하도록 구현한다. Issue의 변경 범위(paths)는 충돌 힌트일 뿐이라 필요하면 밖도 고치고 PR 본문 "범위 밖 변경"에 이유를 적는다.
4. 해당 폴더 테스트·린트를 통과시킨다 (`CLAUDE.md`의 "명령").
5. 커밋은 Conventional Commits, 끝에 `(#issue번호)`. PR은 `develop`으로, 본문에 `Closes #번호`.
6. **머지하지 않는다.** `develop`·`main`에 직접 push하지 않는다. 강제 push 금지. PR이 크면(파일 15개·diff 500줄 초과, 테스트·목·생성 파일 제외) 여러 PR로 쪼갠다(마지막만 `Closes`, 앞은 `Part of`).
7. 금액·수익률·기간 계산은 Spring 계산 엔진에서만 한다. 개별 주식 종목을 추천하는 표현만 쓰지 않는다(ETF 추천과 특정 ETF 언급은 허용).
8. 미정 세부사항은 Issue·Figma·기존 계약과 코드에 근거해 정하고 PR에 가정을 남긴다. 미정이라는 이유만으로 멈추지 않는다. 실제로 진행을 막는 선행 작업, 확정 요구사항 충돌은 루트 `CLAUDE.md`의 중단 규칙을 따른다.
9. 화면 기능은 Figma → 프론트(필요하면 목) → API 명세 → 백엔드 순서로 진행한다. 기존 API 계약은 유지한다.

상세 절차: `.claude/workflows/work-issue.md`. 사람용 가이드: `AI_GUIDE.md`.
