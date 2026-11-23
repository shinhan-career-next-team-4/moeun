## 목적

`app/llm/agent.py`를 **바뀌는 이유(책임)별로 나눠서** 다음 세 가지를 얻는다.

1. **읽기 쉬움**: 기능 하나를 고치려고 1,100줄짜리 파일 전체를 읽지 않아도 된다.
2. **변경 범위 축소**: 세금 문구, 발화 해석, 응답 보정을 서로 다른 사람이 고쳐도 같은 파일에서 충돌하지 않는다.
3. **LangGraph 전환 준비**: CLAUDE.md에 있는 LangGraph 전환을 할 때 루프(`agent.py`)만 갈아 끼울 수 있게 한다.

이번 작업은 **동작을 바꾸지 않는 순수 이동(리팩터링)**이다. 기능 추가, 로직 수정, 이름 변경은 하지 않는다.

## 배경

`agent.py` 한 파일에 바뀌는 이유가 서로 다른 코드가 섞여 있다.

| 들어 있는 것 | 바뀌는 이유 |
|---|---|
| `ChatAgent.run()` 루프, `_retry` | Claude 호출 흐름이 바뀔 때 (LangGraph 전환 등) |
| `TOOLS`, `_CONDITION_FIELDS`, `FINAL_FORMAT` | 모델에게 주는 도구·출력 형식이 바뀔 때 |
| 한국어 수사·조건 정규식, `drop_carried_conditions` | 사용자 발화 해석 규칙이 바뀔 때 |
| `_search_etf_codes`, `_plan_product` 등 | ETF 상품 정책이 바뀔 때 |
| `with_tax_note` 등 | 세금 안내 문구 정책이 바뀔 때 |
| `assemble()`, `_cover_orgs`, `_checked_body` | 응답 보정 규칙이나 DB 제약이 바뀔 때 |

또 `app/llm/assemble.py` 파일이 따로 있는데 정작 `assemble()` 함수는 `agent.py`에 있어서, 처음 보는 사람이 위치를 헷갈린다.

## 할 일

| 새 위치 | 옮길 것 |
|---|---|
| `app/llm/turn.py` (신규) | `_Turn`, `FinalAnswer`, `AgentError`, `_ForbiddenAnswer`, `MAX_CITATIONS` |
| `app/llm/tools.py` (신규) | `TOOLS`, `_CONDITION_FIELDS`, `_nullable`, `FINAL_FORMAT` |
| `app/llm/conditions.py` (신규) | 한국어 수사·조건 정규식(`_KO_*`, `_GOAL`, `_MONTHLY` 등), `_parse_ko_numeral`, `_candidate_krw_amounts`, `check_amount_units`, `_count_numbers`, `_stated_conditions`, `drop_carried_conditions` |
| `app/llm/assemble.py` (기존) | `assemble()`, `_cover_orgs`, 세금 단서(`with_tax_note`, `_note_pattern`, `_quotes_rate`, `_mask_notes`), `_checked_body`, `_fixed_copy`, `_check_numbers`, `_number_evidence` |
| `app/llm/agent.py` (남김) | `ChatAgent`, `LlmStockJudge`, `build_messages`, `check_plan_context`, `plan_model_input`, `_correction`, ETF 계획 판단 함수 |

진행 순서 (단계마다 `uv run pytest` 통과 후 커밋):

1. `turn.py` 분리 (공유 타입을 먼저 빼서 순환 import를 끊는다)
2. `tools.py` 분리
3. `conditions.py` 분리
4. `assemble()`과 보정 함수를 `assemble.py`로 이동
5. CLAUDE.md `## 구조` 표 갱신

## 제약

- **테스트 파일은 수정하지 않는다.** `uv run pytest`가 변경 전과 같은 결과로 전부 통과해야 한다.
- 테스트와 `main.py`가 `app.llm.agent`에서 import하는 이름은 `agent.py`에서 재수출해 기존 경로를 유지한다.
  - 대상: `ChatAgent`, `AgentError`, `LlmStockJudge`, `TOOLS`, `MAX_CITATIONS`, `build_messages`, `drop_carried_conditions`, `out_of_scope_content`, `plan_model_input`
  - mypy strict는 암시적 재수출을 허용하지 않는다. `from x import Name as Name` 형식이나 `__all__`로 명시한다.
- `ChatAgent.run()`과 `_retry()`는 `assemble`을 **`agent` 모듈의 전역 이름으로** 호출한다. `test_forbidden_retry.py`가 `agent_module.assemble`을 monkeypatch로 바꿔 끼운다. `assemble_module.assemble(...)`처럼 부르면 테스트가 깨진다.
- `_correction()`은 `agent.py`에 둔다. 테스트(`test_etf_name_correction.py`, `test_etf_review_round2.py`)가 `agent.ALLOWED_ETF_NAMES`와 `assemble.ALLOWED_ETF_NAMES`를 패치하므로, 제3의 모듈로 옮기면 패치가 닿지 않는다.
- 순환 import가 없어야 한다. `turn.py`는 `app.llm` 안의 다른 모듈을 import하지 않는다.
- `app/llm`은 FastAPI나 `app/api`를 import하지 않는다 (CLAUDE.md 규칙 유지).
- 범위 밖: 함수 이름 변경(`_` 제거 등), 로직 수정, LangGraph 전환. 필요하면 후속 이슈로 남긴다.

## 완료 조건

- [ ] `uv run pytest` 전부 통과
- [ ] `git diff --stat main -- tests/` 결과가 비어 있음 (테스트 파일 변경 없음)
- [ ] `uv run mypy`(strict), `uv run ruff check .` 통과
- [ ] `agent.py`가 루프와 호출 흐름 위주로 줄어듦
- [ ] CLAUDE.md `## 구조` 표에 새 모듈 반영
- [ ] `ai-server-reviewer` 에이전트로 규칙 위반 점검
- [ ] PR 본문에 "옮긴 것 / 남긴 것 / 재수출한 이름" 표 포함

## 참고 개념

### 설계 원칙
- **단일 책임 원칙(SRP)**: 한 모듈은 바뀌는 이유가 하나여야 한다. "이 코드가 바뀌는 이유가 몇 가지인가"로 판단한다. 위 배경 표가 그 기준으로 나눈 것이다.
- **응집도와 결합도**: 같이 바뀌는 것은 한 곳에 모으고(응집도↑), 모듈끼리는 서로의 내부를 몰라도 되게 한다(결합도↓). 응집도는 어떤 단위로 보느냐에 따라 평가가 달라진다. `agent.py`는 "채팅 요청 한 건 처리"라는 목적으로는 묶여 있지만, 단위가 너무 커서 안쪽이 섞였다.
- **의존 방향**: 공유 타입(`turn.py`)은 아무것도 import하지 않는 가장 아래층에 둔다. 위층(`agent.py`)이 아래층을 import하고, 반대 방향은 없게 한다. Spring에서 여러 서비스가 같이 쓰는 도메인 객체를 별도 패키지로 빼는 것과 같다.

### 파이썬 모듈 구조 (Spring과 비교)
- **파일 = 모듈, 폴더 = 패키지**: 자바는 클래스 하나가 파일 하나지만, 파이썬은 파일 하나에 클래스·함수·상수가 같이 들어간다. 파이썬에서 책임을 나눈다는 건 주로 **파일을 나누는 것**이다.
- **import는 모듈을 한 번 실행한다**: 처음 import할 때 파일을 위에서부터 실행하고 결과를 캐시한다.
- **순환 import**: A가 B를 import하고 B가 A를 import하면, 한쪽이 덜 실행된 상태에서 이름을 찾다가 `ImportError`가 난다. Spring은 빈을 나중에 연결해 이런 문제가 잘 안 보이지만 파이썬은 바로 터진다. 공유 타입을 아래층 모듈로 빼는 이유다.
- **`_` 접두사**: "이 모듈 안에서만 쓴다"는 관례다. 자바의 `private`처럼 막지는 않는다. 다른 모듈에서 쓰게 되면 `_`를 빼는 게 관례지만, 이번에는 diff를 작게 하려고 이름을 그대로 둔다.
- **재수출(re-export)**: `agent.py`에 `from app.llm.turn import AgentError as AgentError`를 남겨 두면 바깥에서는 계속 `from app.llm.agent import AgentError`로 쓸 수 있다. 파일을 옮겨도 기존 import 경로가 깨지지 않는다.
- **mypy strict의 명시적 재수출**: strict 모드는 그냥 import한 이름을 다시 내보내는 걸 막는다. `as Name` 형식이나 `__all__`로 "의도적으로 내보낸다"고 표시해야 한다.
- **monkeypatch는 모듈의 이름을 바꾼다**: `monkeypatch.setattr(agent, "X", ...)`는 `agent` 모듈 안의 이름 `X`만 바꾼다. `X`를 쓰는 함수가 다른 모듈로 옮겨 가면 그 함수는 자기 모듈의 `X`를 보므로 패치가 닿지 않는다. 그래서 `_correction()`은 옮기지 않고, `assemble`은 `agent` 모듈의 이름으로 호출한다.
- **`from x import y`는 이름을 복사한다**: `from app.llm.assemble import assemble`을 하면 `agent` 모듈에 `assemble`이라는 이름이 하나 더 생긴다. 함수 본체는 같지만 이름은 두 개다. 위 monkeypatch 동작이 이것 때문에 생긴다.

### 이 프로젝트에서 이미 쓰는 패턴
- **`Protocol`**: 자바의 인터페이스에 해당한다. `Calculator`, `DocumentSearch`가 `Protocol`이라 테스트에서 `FakeCalc` 같은 대역으로 바꿔 끼울 수 있다.
- **`@dataclass`**: 내부 상태(`_Turn`)를 담는 값 객체. 외부 경계(요청·응답)는 pydantic `ApiModel`을 쓴다 (`.claude/rules/python.md`).
- **lifespan에서 객체 조립**: `main.py`가 DB 풀, HTTP 클라이언트, `ChatAgent`를 직접 만들어 `app.state`에 넣고, 라우터는 `Depends`로 받는다. Spring 컨테이너가 하는 일을 손으로 하는 셈이다.
