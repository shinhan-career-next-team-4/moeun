# AGENTS.md

Codex 등 AGENTS.md를 읽는 코딩 에이전트용 안내. **규칙의 원본은 `CLAUDE.md`다.** 작업 전에 루트 `CLAUDE.md`와 작업할 폴더의 `CLAUDE.md`(`backend/CLAUDE.md`, `ai-server/CLAUDE.md`)를 읽고 그대로 따른다.

핵심만 다시 적는다:

1. **모든 코드 변경은 GitHub Issue에서 시작해 PR로 끝난다.** Issue가 없으면 코드를 고치지 않고 먼저 Issue를 만든다.
2. 브랜치: 최신 `develop`에서 `<type>/<issue번호>-<짧은-영문-설명>`.
3. Issue의 수용 기준을 **테스트로 먼저** 쓰고, 그 테스트를 통과하도록 구현한다. Issue의 변경 범위(paths) 밖은 고치지 않는다.
4. 해당 폴더 테스트·린트를 통과시킨다 (`CLAUDE.md`의 "명령").
5. 커밋은 Conventional Commits, 끝에 `(#issue번호)`. PR은 `develop`으로, 본문에 `Closes #번호`.
6. **머지하지 않는다.** `develop`·`main`에 직접 push하지 않는다. 강제 push·`reset --hard` 금지.
7. 금액·수익률·기간 계산은 Spring 계산 엔진에서만 한다. "추천" 표현과 본문의 특정 ETF 상품명은 쓰지 않는다.
8. 수용 기준이 모호하거나, 선행 Issue가 안 끝났거나, 다른 팀 코드를 고쳐야 하면 멈추고 Issue에 댓글로 이유를 남긴다.

상세 절차: `.claude/workflows/work-issue.md`. 사람용 가이드: `AI_GUIDE.md`.
