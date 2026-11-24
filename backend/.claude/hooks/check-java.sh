#!/usr/bin/env bash
# Stop 훅: Java 파일이 바뀌었을 때만 코드 스타일, 모듈 경계, 모듈 내부 책임(ARCHITECTURE.md)을 검사한다.
# 실패하면 exit 2로 Claude에게 오류를 돌려줘 고치게 한다.
set -u

input=$(cat)
# 이미 이 훅 때문에 다시 돈 경우에는 멈춘다(무한 반복 방지)
if echo "$input" | grep -q '"stop_hook_active": *true'; then
    exit 0
fi

cd "$CLAUDE_PROJECT_DIR" || exit 0

if ! git status --porcelain --untracked-files=all -- src | grep -q '\.java$'; then
    exit 0
fi

if ! output=$(./gradlew checkstyleMain checkstyleTest test --tests ModularityTests --tests ArchitectureTests -q 2>&1); then
    echo "Java 검사 실패 (checkstyle / ModularityTests / ArchitectureTests). 고친 뒤 다시 끝내세요." >&2
    echo "$output" | grep -E 'WARN|ERROR|FAILED|Violation|violat|expected|error:|Rule ' | head -30 >&2
    exit 2
fi
exit 0
