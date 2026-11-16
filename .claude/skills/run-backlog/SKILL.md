---
name: run-backlog
description: herdr 안에서 준비된(status:ready) GitHub Issue 여러 개를 골라, Issue마다 git worktree와 herdr pane을 만들고 Claude 에이전트가 work-issue 절차(기획검토→테스트→구현→검증→PR)를 동시에 돌리게 하는 지휘 명령. 사용자가 /run-backlog를 부르거나 "백로그 돌려", "Issue 병렬로 처리해"라고 할 때 사용.
---

# /run-backlog — Issue 병렬 처리 지휘

너는 지휘자다. **직접 코드를 고치지 않는다.** 작업을 고르고, 작업자 에이전트를 띄우고, 기다리고, 결과를 모은다.

인자(선택): `team:B`처럼 라벨 필터, `max=2`처럼 동시 실행 수, `#12 #15`처럼 Issue 지정. 기본은 사용자의 팀 라벨, `max=2`.

## 0. 전제 확인
- `echo $HERDR_ENV`가 `1`이 아니면 멈추고 "herdr pane 안에서 실행해 주세요"라고 안내한다.
- herdr 명령 문법은 설치된 버전이 기준이다. 모르면 `herdr --skill` 또는 `herdr agent`, `herdr pane`을 먼저 실행해 확인한다.
- `gh auth status`, `git fetch origin develop`.

## 1. 작업 고르기
```bash
gh issue list --label status:ready --label <팀라벨> --state open \
  --json number,title,labels,body --limit 50
```
- **선행 Issue**: 본문 "선행" 칸의 Issue가 모두 닫힌 것만 고른다(`gh issue view <n> --json state`).
- **충돌 방지**: 본문 "변경 범위(paths)"가 서로 겹치는 Issue는 같은 회차에 함께 고르지 않는다. 겹침이 애매하면 하나만 고른다.
- 우선순위: `priority:must` → 낮은 `week:` → 낮은 번호.
- 최대 `max`개. 고른 목록을 사용자에게 한 줄씩 보여 준다.

## 2. Issue마다 작업 공간과 에이전트 띄우기
Issue `N`마다(브랜치 type은 Issue 제목의 영역으로: 기능 `feature`, 문서 `docs`, 설정 `chore`):
```bash
slug=<제목을 짧은 영문 kebab-case로>
git worktree add "../moeun-$N" -b "feature/$N-$slug" origin/develop

split=$(herdr pane split --current --direction right --cwd "$(cd ../moeun-$N && pwd)" --no-focus)
pane=$(printf '%s\n' "$split" | jq -r '.result.pane.pane_id')
herdr agent start "issue-$N" --kind claude --pane "$pane"
herdr agent prompt "issue-$N" "Issue #$N 을 .claude/workflows/work-issue.md 절차대로 끝까지 처리해. 머지는 하지 마. 끝나면 마지막 줄에 보고 한 줄을 써."
```
- 에이전트를 모두 띄운 뒤 기다린다. 한 번에 하나씩 기다리지 말고 상태를 돌아가며 확인한다:
```bash
herdr agent wait "issue-$N" --until done --timeout 1800000   # 형식은 herdr agent wait --help로 확인
```
- 에이전트가 `blocked`(사람 입력 대기) 상태가 되면 그 pane을 읽고(`herdr agent read "issue-$N"`) 사용자에게 무엇을 묻는지 전한다. **지휘자가 대신 답하지 않는다.**

## 3. 결과 모으기
에이전트가 끝나면 각 Issue를 확인한다:
```bash
gh issue view $N --json labels,state
gh pr list --search "$N in:body" --json number,title,url,headRefName
```
사용자에게 표로 보고한다:

| Issue | 결과 | PR | 비고 |
|---|---|---|---|
| #12 | PR 생성 | #30 | 테스트 통과 |
| #15 | blocked | — | 선행 #9 미완료 |

## 4. 정리
- PR이 만들어진 worktree는 그대로 둔다(리뷰 수정용). 사용자가 머지한 뒤 `git worktree remove ../moeun-N`.
- 다음 회차를 바로 돌릴지 사용자에게 묻는다. 묻지 말라고 했으면 1번으로 돌아간다(ready Issue가 없을 때까지).

## 하지 않는 것
- PR 머지(`gh pr merge`), `develop`·`main` 직접 push, 강제 push.
- 다른 사람에게 할당된(assignee가 있는) Issue 가져오기.
- 사람이 답해야 할 질문에 대신 답하기.
