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
4. **개발**: Issue의 수용 기준을 테스트로 먼저 옮기고, 그 테스트를 통과하도록 구현한다. Issue의 `paths` 밖은 고치지 않는다. 고쳐야 하면 PR 본문에 이유를 적는다.
5. **검증**: 해당 폴더의 테스트·린트를 모두 통과시킨다(아래 "명령"). 폴더별 리뷰어 에이전트(`backend-reviewer`, `ai-server-reviewer`)로 규칙 위반을 점검한다.
6. **커밋**: Conventional Commits. `feat(chat): 메시지 폴링 API 추가 (#12)`처럼 끝에 Issue 번호.
7. **PR**: `develop`으로 연다. 본문은 `.github/pull_request_template.md` 형식이고 `Closes #<번호>`를 꼭 넣는다. 그 뒤 `gh issue edit <번호> --add-label status:review --remove-label status:in-progress`.
8. **머지는 사람이 한다.** 에이전트는 `gh pr merge`를 실행하지 않고, `develop`·`main`에 직접 push하지 않는다.

막히면 멈춘다: 수용 기준이 모호하거나, 다른 팀(A↔B) 코드를 고쳐야 하거나, 선행 Issue가 안 끝났으면 Issue에 댓글로 이유를 남기고 `status:blocked` 라벨을 붙인 뒤 사람에게 보고한다. 추측으로 채우지 않는다.

Issue 하나를 끝까지 처리하는 상세 절차는 `.claude/workflows/work-issue.md`, 여러 Issue를 herdr로 동시에 돌리는 절차는 `/run-backlog` 스킬(`.claude/skills/run-backlog/SKILL.md`).

## 라벨

| 종류 | 라벨 |
|---|---|
| 상태 | `status:ready` → `status:in-progress` → `status:review` → (머지되면 Issue 닫힘). 막히면 `status:blocked` |
| 팀 | `team:A`(계산·입력·결과·계획 저장·인증·데이터), `team:B`(대안·비교·AI 대화·설명·RAG), `team:common` |
| 영역 | `area:backend`, `area:ai-server`, `area:frontend`, `area:db`, `area:docs` |
| 우선순위 | `priority:must`, `priority:should`(2주차 말 판단) |
| 주차 | `week:1`, `week:2`, `week:3` |

## 명령

- backend: `cd backend && ./gradlew checkstyleMain checkstyleTest test` (Docker 필요. 빠른 검사는 `--tests ModularityTests`)
- ai-server: `cd ai-server && uv run ruff check . && uv run mypy && uv run pytest`
- frontend: (프로젝트 초기 설정 Issue에서 정함)

## 브랜치

- 기본 브랜치는 `develop`. 기능 PR은 모두 `develop`으로.
- `main`은 `develop`에서 온 PR만 받는다(`.github/workflows/main-source-guard.yml`).
