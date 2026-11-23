## 목적

#199에서 파일을 나누면서 **모듈 밖에서 쓰이게 된 `_` 이름**을 공개 이름으로 바꾼다. 이름이 실제 사용 범위를 정확히 말하게 해서, 읽는 사람이 "이건 이 파일 안에서만 쓰는 거구나"라고 잘못 판단하지 않게 한다.

#199에서 diff를 작게 하려고 일부러 미뤄 둔 작업이다.

## 배경

지금 다른 모듈에서 import되는 `_` 이름:

| 이름 | 정의 위치 | 쓰는 곳 |
|---|---|---|
| `_plan_etf_name` | `app/llm/plan.py` | `agent.py` (+ #199 이후 `assemble.py`) |
| `_plan_without_etf` | `app/llm/plan.py` | `agent.py` (+ #199 이후 `assemble.py`) |
| `_ForbiddenAnswer` | `app/llm/errors.py` | `agent.py` (+ #199 이후 `assemble.py`) |

#199가 끝난 뒤 `_Turn`, `_cover_orgs`, `_checked_body` 등 다른 이름도 모듈을 넘나들 수 있으니, 시작할 때 다시 grep해서 목록을 확정한다.

## 할 일

1. `grep`으로 "다른 모듈에서 import되는 `_` 이름"을 전부 찾는다.
2. 각 이름에서 `_`를 뗀다 (`_plan_etf_name` → `plan_etf_name`).
3. 모듈 안에서만 쓰는 `_` 이름은 그대로 둔다.

## 제약

- 동작 변화 없음. 이름만 바꾼다.
- **테스트 파일은 수정하지 않는다.** 테스트가 옛 이름을 import하거나 monkeypatch하면, 정의 모듈에 옛 이름을 별칭으로 남긴다(`_plan_etf_name = plan_etf_name`). 별칭이 필요한 이름은 PR 본문에 적는다.
- mypy strict 재수출 규칙(`from x import Name as Name` 또는 `__all__`)을 지킨다.
- #199 머지 후 시작한다.

## 완료 조건

- [ ] 다른 모듈에서 import되는 `_` 이름이 없음 (별칭 제외)
- [ ] `uv run pytest`, `uv run mypy`, `uv run ruff check .` 통과
- [ ] `git diff --stat main -- tests/` 비어 있음
- [ ] PR 본문에 "바꾼 이름 / 남긴 별칭" 표

## 참고 개념

- **`_` 접두사의 의미**: 파이썬에서 `_`로 시작하는 이름은 "이 모듈 안에서만 쓴다"는 관례다. 자바의 `private`과 달리 실제로 막지는 않는다. 그래서 이름이 실제 사용 범위와 어긋나도 아무 에러가 나지 않고, 사람만 헷갈린다.
- **`from x import *`와 `_`**: `import *`는 `_` 이름을 가져오지 않는다. `_`가 단순한 장식이 아니라 언어가 일부 인정하는 규칙인 이유다.
- **별칭으로 하위 호환 유지**: `old_name = new_name`처럼 이름을 하나 더 두면 기존 import가 깨지지 않는다. 자바에서 `@Deprecated` 메서드를 남겨 두고 새 메서드로 위임하는 것과 비슷하다.
