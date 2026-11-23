## 목적

여기저기 문자열로 흩어진 **에러 코드를 `StrEnum`으로 모아서** 다음을 얻는다.

1. 오타를 mypy가 잡는다. 지금은 `AgentError("MAX_TOKEN")`처럼 틀려도 아무도 모른다.
2. "ai-server가 Spring에 돌려줄 수 있는 에러 코드 전체 목록"을 한 파일에서 볼 수 있다.
3. HTTP 상태 매핑(`main.py`)이 문자열 비교 대신 타입에 기대게 된다.

팀 규칙(`.claude/rules/python.md`)의 "값 목록은 `StrEnum`/`Literal`"을 에러 코드에도 적용하는 작업이다.

## 배경

지금 코드에 문자열 리터럴로 쓰인 에러 코드:

| 종류 | 코드 |
|---|---|
| `AgentError` | `BAD_REQUEST`, `BAD_OUTPUT`, `MAX_TOKENS`, `TOO_MANY_ROUNDS`, `NUMBER_MISMATCH`, `FORBIDDEN_EXPRESSION`, `CALC_FAILED` |
| `CalcFailed` | `CALC_FAILED`, `CALC_BAD_REQUEST`, `CALC_UNAUTHORIZED`, `CALC_UNAVAILABLE`, `CALC_DISPLAY_MISSING` |
| `main.py` 핸들러 | `LLM_RATE_LIMIT`, `LLM_UNAVAILABLE` |
| `_ForbiddenAnswer` 사유 | `stock_recommendation`, `internal_key`, `internal_leak`, `product_name`, `empty_body` |

이 코드들은 `{"error": CODE}`로 Spring에 나간다(README §`/chat` 실패). 즉 **Spring과의 계약 값**이다.

## 할 일

1. `app/llm/errors.py`(또는 `app/errors.py`)에 `ErrorCode(StrEnum)`를 만든다. 위 표의 Spring으로 나가는 코드를 전부 넣는다.
2. `AgentError`, `CalcFailed`의 `code` 타입을 `ErrorCode`로 바꾸고, 호출하는 곳을 `ErrorCode.MAX_TOKENS` 형태로 바꾼다.
3. `main.py`의 상태 매핑을 `ErrorCode` 기준으로 바꾼다. 400으로 갈 코드 집합을 상수로 둔다.
4. `_ForbiddenAnswer`의 사유는 Spring으로 나가지 않는 내부 값이므로 `Literal`(또는 별도 `StrEnum`)로 둔다.
5. Spring이 보내는 코드(`VALIDATION_FAILED`, `FIELD_NOT_CHANGEABLE`, `NO_PLAN`, `ALREADY_EXCEEDED`)는 **받는 쪽 계약**이라 `ErrorCode`와 섞지 않는다. 필요하면 `SpringRejectCode` 같은 별도 타입으로 둔다.

## 제약

- **에러 코드의 문자열 값은 하나도 바꾸지 않는다.** Spring이 이 값으로 판단한다.
- **테스트 파일은 수정하지 않는다.** `StrEnum`은 `str`의 하위 타입이라 `e.code == "MAX_TOKENS"` 같은 기존 비교가 그대로 통과해야 한다. 통과하지 않는 테스트가 있으면 멈추고 보고한다.
- JSON 응답 본문(`{"error": "MAX_TOKENS"}`)이 바뀌지 않아야 한다.
- 범위 밖: 새 에러 코드 추가, 상태 코드 정책 변경.

## 완료 조건

- [ ] 앱 코드에 에러 코드 문자열 리터럴이 남아 있지 않음 (`grep -rn 'AgentError("' app` 결과 없음)
- [ ] `uv run pytest`, `uv run mypy`, `uv run ruff check .` 통과
- [ ] `git diff --stat main -- tests/` 비어 있음
- [ ] README의 에러 코드 설명이 `ErrorCode` 정의 위치를 가리킴

## 참고 개념

- **`StrEnum`**: 파이썬 3.11부터 있는 enum으로, 멤버가 곧 문자열이다. `ErrorCode.MAX_TOKENS == "MAX_TOKENS"`가 `True`이고, JSON으로 내보내면 그냥 `"MAX_TOKENS"`가 된다. 자바 enum과 달리 `.name()`을 부를 필요가 없다.
- **`StrEnum` vs `Literal`**: `Literal["a", "b"]`는 타입 검사 때만 존재하는 "허용 값 목록"이고, `StrEnum`은 실행 중에도 있는 객체다. 목록을 순회하거나 다른 곳에서 이름으로 참조해야 하면 `StrEnum`, 함수 인자 하나의 허용 값만 제한하면 `Literal`이 간단하다.
- **보내는 계약과 받는 계약 분리**: ai-server가 Spring에 보내는 코드와 Spring이 ai-server에 보내는 코드는 바뀌는 이유가 다르다(각자 상대 팀이 바꾼다). 한 enum에 섞으면 응집도가 떨어진다.
- **Spring 비교**: Spring에서 `ErrorCode` enum과 `@ControllerAdvice`로 상태를 매핑하는 것과 같은 구조다. FastAPI에서는 `@app.exception_handler`가 `@ControllerAdvice` 역할을 한다.
