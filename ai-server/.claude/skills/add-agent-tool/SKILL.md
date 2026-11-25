---
name: add-agent-tool
description: ChatAgent에 새 도구(tool use)를 추가할 때 사용. 도구 스키마, 핸들러, 서버 사실 기록, 프롬프트, 테스트를 함께 고친다.
---

# 에이전트 도구 추가

`app/llm/agent.py`의 SDK 루프 기준이다. LangGraph로 전환한 뒤에는 이 절차를 다시 쓴다(`.claude/rules/langchain.md`).

네 곳을 모두 고쳐야 끝난다.

1. **스키마**: `TOOLS`에 `"strict": True`, 모든 속성 `required`, `additionalProperties: False`. 선택값은 `_nullable(...)`로 null 허용. description은 모델이 언제 쓸지 판단할 수 있게 한 줄로.
2. **핸들러**: `async def _<name>(self, turn: Turn, args: dict[str, Any]) -> str`를 만들고 `self._tools`에 등록한다.
   - 입력은 pydantic으로 검증한다. 외부 호출은 `Protocol` 의존성으로 받는다(생성자 주입, `app/main.py` lifespan에서 연결).
   - 응답 보정에 필요한 결과(id 등)는 `Turn`에 필드로 기록한다. 모델이 돌려준 값을 믿지 않는다.
   - 모델에게 되돌려 줄 실패는 `_TOOL_ERRORS`에 속하는 예외로 올린다.
3. **프롬프트**: `app/llm/prompts.py`의 `SYSTEM_PROMPT`에 언제 쓰고 언제 쓰지 않는지 적는다.
4. **테스트**: `tests/test_agent.py`에 `tool_resp(...)` → `text_resp(...)` 순서의 `FakeClaude`로 성공 1건, 실패(is_error) 1건.

응답 필드에 영향이 있으면 `assemble`과 `ChatResponse`를 함께 고치고 `chat_message` CHECK(`../db/erd.md`)를 확인한다.

마지막에 `uv run pytest && uv run ruff check . && uv run mypy`.
