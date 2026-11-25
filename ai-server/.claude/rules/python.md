---
paths:
  - "**/*.py"
---

# 파이썬 스타일

- `mypy --strict`와 `ruff check`를 통과해야 한다. `# type: ignore`는 서드파티 타입 부재일 때만, 코드를 붙여 쓴다.
- 의존성은 `Protocol`로 받고 생성자로 주입한다(`Calculator`, `DocumentSearch` 참고). 전역 싱글턴 금지. FastAPI에서는 `Annotated[..., Depends(...)]`.
- 데이터: 외부 경계(요청·응답)는 pydantic `ApiModel`, 내부 상태는 `dataclass`, 값 목록은 `StrEnum`/`Literal`.
- 상태가 없으면 클래스 대신 모듈 함수. 분기는 `match`가 읽기 쉬우면 `match`.
- I/O는 전부 async(httpx, psycopg async, LangChain의 `ainvoke`/`aembed_*`). 동시 호출은 `asyncio.gather`.
- 예외는 의미 있는 도메인 예외(`AgentError(code)`, `CalcError`)로 바꿔 올린다. 맨 `except Exception` 금지.
- 비밀값은 `SecretStr`, 설정은 `Settings`(pydantic-settings)에만 추가하고 `.env.example`도 같이 고친다.
- 주석은 이유(왜)와 요구사항 ID만 적는다. 빈 패키지·placeholder 파일을 만들지 않는다.
