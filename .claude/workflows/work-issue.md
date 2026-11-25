# Issue 하나 처리하기 (기획검토 → 테스트 → 구현 → 검증 → PR)

입력: Issue 번호 `N`. 다른 Issue와 동시에 작업한다면 Issue 전용 git worktree에서 한다: `git worktree add ../moeun-N -b <type>/N-<slug> origin/develop`. 하나만 한다면 현재 폴더에서 같은 이름의 브랜치를 만든다.

각 단계가 끝날 때마다 Issue에 짧은 댓글로 진행 상황을 남긴다(`gh issue comment N --body "..."`). 나중에 사람이 Issue만 보고 무슨 일이 있었는지 알 수 있어야 한다.

## 0. 시작
- `gh issue view N --comments`로 본문과 댓글을 읽는다.
- 라벨이 `status:ready`가 아니면 멈추고 보고한다(누가 이미 하고 있거나 막힌 작업).
- `gh issue edit N --add-label status:in-progress --remove-label status:ready --add-assignee @me`

## 1. 기획 검토 (`issue-planner` 에이전트)
- 수용 기준, 요구사항 ID, 선행 Issue, `paths`를 확인하고 구현 계획(바꿀 파일, 추가할 테스트, 위험)을 세운다.
- 수용 기준이 모호하거나 빠진 세부사항은 Issue·Figma·기존 계약·코드·테스트를 기준으로 보완한다. 남은 사항은 기존 계약과 확정 정책을 유지하는 기본값으로 정하고 테스트로 옮긴다. 중요한 가정과 근거는 PR에 남긴다.
- 화면 기능은 Figma를 바탕으로 프론트를 먼저 구현한다. 백엔드가 없으면 목을 사용한다. 화면에 필요한 요청·응답·상태 코드·오류·예시를 기존 계약과 맞춰 명세로 남기고, 백엔드 Issue에서 그 명세를 사용한다.
- 필수 산출물이 없는 선행 Issue, 진행 불가능한 확정 요구사항 충돌, `paths`는 충돌 힌트라 밖(다른 팀 모듈 포함)도 고칠 수 있다. 그 경우 PR 본문 "범위 밖 변경"에 이유를 적고 중단하지 않는다. 목으로 구현·검증 가능한 프론트 작업은 백엔드 미완료만으로 중단하지 않는다. 실제 차단 시 Issue에 이유를 남기고 `status:blocked`로 바꾼다.
- 계획을 Issue 댓글로 남긴다.

## 2. 테스트 먼저 (`test-writer` 에이전트)
- 수용 기준 하나당 테스트 하나 이상. 예외 상황 포함.
- 테스트는 지금 **실패해야** 한다(구현 전). 실패를 확인하고 커밋: `test(<scope>): <내용> (#N)`
- backend는 `backend/.claude/rules/testing.md`, ai-server는 `ai-server/.claude/rules/testing.md`를 따른다.

## 3. 구현
- 테스트를 통과시키는 최소한의 코드를 쓴다. 해당 폴더의 `CLAUDE.md`와 `.claude/rules/`를 따른다.
- **테스트를 고쳐서 통과시키지 않는다.** 테스트가 틀렸다고 판단되면 이유를 Issue에 남기고 사람에게 묻는다.
- 커밋: `feat(<scope>): <내용> (#N)` 또는 `fix(...)`.

## 4. 검증 (최대 3회 반복)
- 폴더의 전체 검사를 돌린다(루트 `CLAUDE.md`의 "명령").
- 실패하면 3번으로 돌아간다. 3회 안에 통과하지 못하면 멈추고 실패 로그 요약을 Issue에 남긴 뒤 `status:blocked`.

## 5. 리뷰 (`code-reviewer` 에이전트)
- 수용 기준 충족, 금지 규칙(계산은 Spring에서만, 개별 주식 종목 추천 표현 금지)을 점검한다. `paths` 밖 변경은 허용되며 PR 본문에 이유가 있는지만 본다.
- backend면 `backend-reviewer`, ai-server면 `ai-server-reviewer` 기준도 함께 적용한다.
- 선택: `codex` CLI가 있으면 다른 모델로 2차 리뷰를 받는다: `codex exec "git diff origin/develop...HEAD 를 리뷰해. Issue #N 수용 기준: ... 실행 가능한 지적만"`.
- "높음" 지적이 있으면 3번으로 돌아간다.

## 6. PR
- `git push -u origin HEAD`
- `gh pr create --base develop --title "<type>(<scope>): <내용> (#N)" --body-file <파일>` — 본문은 `.github/pull_request_template.md` 형식, `Closes #N` 필수. 테스트 체크리스트에는 실제로 돌린 명령을 적는다.
- `gh issue edit N --add-label status:review --remove-label status:in-progress`
- PR이 크면(파일 15개·diff 500줄 초과, 테스트·목·생성 파일 제외) 한 Issue를 여러 PR로 쪼갠다. 각 PR은 단독으로 통과·머지 가능해야 하고, 마지막만 `Closes #N`, 앞은 `Part of #N`.
- 끝. **머지하지 않는다.**

## 끝날 때 보고
한 줄 형식: `#N <결과: PR #M 생성 | blocked: 이유> — 테스트 <통과/실패>, 바꾼 파일 <개수>`
