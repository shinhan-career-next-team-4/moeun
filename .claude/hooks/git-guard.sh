#!/usr/bin/env bash
# PreToolUse(Bash) 훅: 에이전트가 머지·보호 브랜치 push·파괴적 git 명령을 실행하지 못하게 막는다.
# 머지는 사람이 한다(루트 CLAUDE.md "작업 규칙" 8번). exit 2 = 차단, stderr가 Claude에게 전달된다.

input="$(cat)"
has() { printf '%s' "$input" | grep -Eq "$1"; }
GIT='git[[:space:]]+(-C[[:space:]]+[^[:space:]]+[[:space:]]+)?'

if has 'gh[[:space:]]+pr[[:space:]]+merge' || has 'gh[[:space:]]+api[^"]*/merge'; then
  echo "차단됨: PR 머지는 사람이 합니다. PR을 만든 뒤 멈추고 보고하세요." >&2; exit 2
fi
if has "${GIT}push[^\"]*(--force|[[:space:]]-f([[:space:]]|\"))"; then
  echo "차단됨: 강제 push는 하지 않습니다." >&2; exit 2
fi
if has "${GIT}push[^\"]*[[:space:]:](main|develop)([[:space:]]|\"|$)"; then
  echo "차단됨: main·develop에는 직접 push하지 않습니다. 작업 브랜치에 push하고 PR을 여세요." >&2; exit 2
fi
if has "${GIT}reset[[:space:]]+--hard" || has "${GIT}(branch[[:space:]]+-D|clean[[:space:]]+-[a-z]*f)"; then
  echo "차단됨: reset --hard, branch -D, clean -f는 사람에게 요청하세요." >&2; exit 2
fi
exit 0
