---
paths:
  - "tests/**"
---

# 테스트

- 테스트는 네트워크를 쓰지 않는다. 모델은 fake(`FakeClaude`: LangChain `AIMessage`를 돌려준다), Spring은 `respx`, 검색은 fake `DocumentSearch`·fake retriever. 그래프 테스트는 `langchain.md`를 따른다.
- mock보다 fake 객체. `Protocol`을 만족하는 작은 클래스를 쓴다.
- 라우터 테스트는 `app.dependency_overrides`로 의존성을 바꾸고 `finally`에서 `clear()`.
- async 테스트는 그냥 `async def`(asyncio_mode=auto).
- 응답 JSON은 camelCase 키로 검증한다(Spring 계약).
- 청킹·검색 로직을 바꾸면 대표 재질문 → 기대 청크 케이스를 테스트에 추가한다.
