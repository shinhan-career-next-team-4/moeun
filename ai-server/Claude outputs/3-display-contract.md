## 목적

Spring과 주고받는 **display 키 계약을 한 모듈에 모은다.** Spring이 키 하나를 바꿨을 때 ai-server에서 고칠 곳을 한 군데로 줄인다.

## 배경

display 키에 대한 규칙이 지금 세 군데에 따로 있다.

| 위치 | 하는 일 |
|---|---|
| `app/schemas.py` `DISPLAY_KEY` 정규식 | Spring이 보낸 `planContext.display`에서 **받아도 되는 키**만 남긴다 |
| `app/llm/agent.py` `_CALCULATED_DISPLAY_KEYS`, `_SCENARIO_DISPLAY_KEYS`, `_EXCEEDED_DISPLAY_KEYS` | 계획 설명에 **꼭 있어야 하는 키**를 검사한다 |
| `app/llm/assemble.py` `_DISPLAY_KEY` 정규식 | 답변 본문에 **키 이름이 새어 나가면** 거부한다 |
| `app/llm/plan.py` `TAX_RATE_KEY`, `ETF_TAX_RATE_KEY`, `ETF_NAME_KEY` | 특정 키를 이름으로 읽는다 |
| `app/tools/calc.py` `_SIDES`, `_check_display` | 재계산 응답의 display 키를 검사한다 |

세 정규식·목록이 같은 키 집합을 서로 다른 방식으로 표현하고 있어서, 하나만 고치고 나머지를 빠뜨려도 테스트가 바로 잡지 못할 수 있다.

## 할 일

1. `app/contract.py`(이름은 계획 단계에서 결정)를 만든다. `app.llm`, `app.api`를 import하지 않는 아래층 모듈이다.
2. 키 구성 요소(시나리오 `GOOD/AVG/BAD`, 시나리오 필드, `conditions.*`, `assumptions.*` 필드, 최상위 키)를 **한 번만** 정의한다.
3. 위 표의 정규식과 목록을 그 정의에서 **만들어 내도록** 바꾼다. 예: 허용 키 정규식을 필드 목록으로 조립한다.
4. 기존 이름(`schemas.DISPLAY_KEY`, `plan.TAX_RATE_KEY` 등)은 재수출해 유지한다.
5. "세 규칙이 같은 키 집합을 본다"는 걸 확인하는 테스트를 **새 파일로** 추가한다.

## 제약

- **동작 변화 없음.** 받는 키, 요구하는 키, 막는 키 이름이 지금과 정확히 같아야 한다. 정규식을 조립식으로 바꾼 뒤 기존 정규식과 같은 문자열을 매칭하는지 새 테스트로 비교한다.
- **기존 테스트 파일은 수정하지 않는다.** 새 테스트는 새 파일에 둔다.
- `app/contract.py`는 `app.schemas`보다 아래층이다(`schemas.py`가 `contract.py`를 import한다). 순환 import 금지.
- mypy strict 재수출 규칙을 지킨다.
- #2(에러 코드)와 같은 파일을 건드리므로 동시에 진행하지 않는다.

## 완료 조건

- [ ] display 키 이름 문자열이 `app/contract.py` 밖에 직접 쓰이지 않음 (재수출 별칭 제외)
- [ ] 새 테스트: 조립한 정규식이 기존 정규식과 같은 키 목록에서 같은 결과를 냄
- [ ] `uv run pytest`, `uv run mypy`, `uv run ruff check .` 통과
- [ ] `git diff --stat main -- tests/`에 새 파일 추가만 있음
- [ ] CLAUDE.md `## 구조` 표에 `contract.py` 추가

## 참고 개념

- **계약(contract)과 응집도**: 다른 팀과 맞춘 약속은 "상대가 바꾸면 같이 바뀌는 것"이다. 바뀌는 이유가 같으니 한곳에 두는 게 응집도가 높다. Spring에서 API 스펙 DTO를 별도 모듈로 공유하는 것과 같은 발상이다.
- **단일 진실 공급원(Single Source of Truth)**: 같은 정보를 여러 곳에 적으면 언젠가 어긋난다. 한곳에 정의하고 나머지는 거기서 **만들어 쓰면** 어긋날 수가 없다.
- **모듈 계층**: `contract.py`는 아무것도 import하지 않는 가장 아래층에 둔다. `schemas.py` → `llm/*` → `api/*` 순으로 위로 쌓인다. 아래층이 위층을 import하면 순환이 생긴다.
- **정규식 조립**: `"|".join(re.escape(k) for k in keys)`처럼 목록으로 정규식을 만들면, 키를 추가할 때 정규식을 직접 고칠 필요가 없다.
