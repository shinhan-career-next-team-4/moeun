#!/usr/bin/env bash
# Stop 훅: 파이썬 파일이 바뀌었을 때만 ruff, mypy, pytest를 돌린다.
# 실패하면 exit 2로 Claude에게 오류를 돌려줘 고치게 한다.
set -u

input=$(cat)
# 이미 이 훅 때문에 다시 돈 경우에는 멈춘다(무한 반복 방지)
if echo "$input" | grep -q '"stop_hook_active": *true'; then
    exit 0
fi

cd "$CLAUDE_PROJECT_DIR" || exit 0

if ! git status --porcelain --untracked-files=all -- app tests | grep -q '\.py$'; then
    exit 0
fi

if ! output=$( (uv run ruff check . && uv run mypy && uv run pytest -q) 2>&1 ); then
    echo "파이썬 검사 실패 (ruff / mypy / pytest). 고친 뒤 다시 끝내세요." >&2
    echo "$output" | tail -30 >&2
    exit 2
fi
exit 0
