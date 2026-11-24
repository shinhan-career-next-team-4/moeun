# moeun

목표 달성 시뮬레이션 서비스(목표 기반 개인 자산관리 AI Agent). 3주 해커톤 MVP.

| 폴더 | 내용 | 세부 규칙 |
|---|---|---|
| `backend/` | Spring Boot. 계산 엔진, 계획 저장·적용, 인증, 대화 저장 | `backend/CLAUDE.md` |
| `ai-server/` | FastAPI. AI 설명, 대화, RAG. Spring만 호출 | `ai-server/CLAUDE.md` |
| `frontend/` | React | (작성 예정) |
| `db/` | DB 설계 결정(ADR), 테이블 설명 | `db/decision.md` |

요구사항과 결정 기록은 기획 repo [project-workspace](https://github.com/shinhan-career-next-team-4/project-workspace)에 있다: `docs/srs.md`(요구사항, 담당 A팀/B팀), `docs/decisions.md`(D-번호), `docs/design/`(계약). Issue 본문의 요구사항 ID(FR-xxx, D-xxx)는 이 문서를 가리킨다.

## 작업 규칙: Issue → 브랜치 → PR (항상)

**모든 코드 변경은 GitHub Issue에서 시작해서 PR로 끝난다.** 사람이 따로 말하지 않아도 이 순서를 따른다.

1. **Issue 확인**: 작업할 Issue 번호가 없으면 먼저 찾거나(`gh issue list --label status:ready`) 사람에게 묻는다. Issue 없이 코드를 고치지 않는다. 작은 수정이라도 Issue가 없으면 `.github/ISSUE_TEMPLATE/task.yml` 형식으로 먼저 만든다.
2. **시작 표시**: `gh issue edit <번호> --add-label status:in-progress --remove-label status:ready` 그리고 `--add-assignee @me`.
3. **브랜치**: 최신 `develop`에서 만든다. 이름은 `<type>/<issue번호>-<짧은-영문-설명>`. type은 `feature`, `fix`, `chore`, `docs`, `test`, `refactor`. 예: `feature/12-chat-polling-api`
4. **개발**: Issue의 수용 기준을 테스트로 먼저 옮기고, 그 테스트를 통과하도록 구현한다. Issue의 `paths`는 동시 작업의 충돌을 피하기 위한 힌트일 뿐이다. 필요하면 `paths` 밖 파일(다른 팀 영역 포함)도 고치고, PR 본문 "범위 밖 변경"에 파일과 이유를 적는다.
   **PR이 크면 쪼갠다.** 변경 파일이 15개를 넘거나 diff가 500줄을 넘으면(테스트·목·생성 파일 제외) 한 Issue를 여러 PR로 나눈다. 각 PR은 단독으로 테스트가 통과하고 머지할 수 있어야 한다. 마지막 PR만 `Closes #<번호>`, 앞의 PR은 `Part of #<번호>`로 적는다.
5. **검증**: 해당 폴더의 테스트·린트를 모두 통과시킨다(아래 "명령"). 폴더별 리뷰어 에이전트(`backend-reviewer`, `ai-server-reviewer`)로 규칙 위반을 점검한다.
6. **커밋**: Conventional Commits. `feat(chat): 메시지 폴링 API 추가 (#12)`처럼 끝에 Issue 번호.
7. **PR**: `develop`으로 연다. 본문은 `.github/pull_request_template.md` 형식이고 `Closes #<번호>`(PR을 나눴다면 마지막 PR만, 나머지는 `Part of #<번호>`)를 넣는다. 그 뒤 `gh issue edit <번호> --add-label status:review --remove-label status:in-progress`.
8. **머지는 사람이 한다.** 에이전트는 `gh pr merge`를 실행하지 않고, `develop`·`main`에 직접 push하지 않는다.

명세에 빠진 세부사항이나 모호한 수용 기준만으로 멈추지 않는다. Issue, 연결된 Figma, 기존 계약·코드·테스트를 확인하고, 남은 부분은 기존 계약과 확정 정책을 유지하는 기본값으로 정해 검증 가능한 수용 기준과 테스트로 옮긴다. 결과에 영향을 준 가정과 근거는 PR에 남긴다.

필수 산출물이 없어 작업을 진행할 수 없는 선행 Issue가 남았거나, 확정 요구사항끼리 충돌해 진행할 수 없으면 Issue에 이유를 남기고 `status:blocked`로 바꾼다. 백엔드 미구현만으로 목 데이터로 진행 가능한 프론트 작업을 막지 않는다.

Issue 하나를 끝까지 처리하는 상세 절차는 `.claude/workflows/work-issue.md`. 사람이 직접 하든 에이전트에게 맡기든 같은 절차를 따른다. 여러 Issue를 동시에 처리할 때는 Issue마다 별도 git worktree를 쓰고, 본문의 `paths`가 겹치는 Issue는 동시에 하지 않는다.

## 화면 기능의 작업 순서

1. **Figma → 프론트:** Issue에 연결된 Figma의 화면과 사용자 흐름을 바탕으로 프론트를 구현한다. 백엔드가 아직 없으면 목으로 정상·로딩·빈 상태·오류 중 해당하는 상태를 검증한다.
2. **프론트 → API 명세:** 화면에 실제로 필요한 데이터를 바탕으로 요청·응답, 상태 코드·오류, 예시를 적는다. 이미 합의된 API가 있으면 재사용한다. 새 계약이 필요하면 문서나 프론트 PR에 기록하고 백엔드 Issue에 링크한다.
3. **API 명세 → 백엔드:** 백엔드는 연결된 명세를 수용 테스트로 옮겨 구현하고, 연동 시 프론트 목과 실제 응답이 일치하는지 확인한다.

## 라벨

| 종류 | 라벨 |
|---|---|
| 상태 | `status:ready` → `status:in-progress` → `status:review` → (머지되면 Issue 닫힘). 막히면 `status:blocked` |
| 팀 | `team:A`(계산·입력·결과·계획 저장·인증·데이터), `team:B`(대안·비교·AI 대화·설명·RAG), `team:common` |
| 영역 | `area:backend`, `area:ai-server`, `area:frontend`, `area:db`, `area:docs` |
| 우선순위 | `priority:must`, `priority:should`(2주차 말 판단) |
| 주차 | `week:1`, `week:2`, `week:3` |

## 명령

- backend: `cd backend && ./gradlew checkstyleMain checkstyleTest test` (Docker 필요. 빠른 검사는 `--tests ModularityTests --tests ArchitectureTests`)
- ai-server: `cd ai-server && uv run ruff check . && uv run mypy && uv run pytest`
- frontend: (프로젝트 초기 설정 Issue에서 정함)

## 브랜치

- 기본 브랜치는 `develop`. 기능 PR은 모두 `develop`으로.
- `main`은 `develop`에서 온 PR만 받는다(`.github/workflows/main-source-guard.yml`).
