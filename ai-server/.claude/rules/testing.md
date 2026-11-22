---
paths:
  - "tests/**"
---

# 테스트

- 모델은 fake(기존 SDK 루프는 `FakeClaude`, LangChain 코드는 fake 채팅 모델), Spring은 `respx`, 검색은 fake `DocumentSearch`·fake retriever. 그래프 테스트는 `langchain.md`를 따른다.
- mock보다 fake 객체. `Protocol`을 만족하는 작은 클래스를 쓴다.
- 라우터 테스트는 `app.dependency_overrides`로 의존성을 바꾸고 `finally`에서 `clear()`.
- async 테스트는 그냥 `async def`(asyncio_mode=auto).
- 응답 JSON은 camelCase 키로 검증한다(Spring 계약).
- 청킹·검색 로직을 바꾸면 대표 재질문 → 기대 청크 케이스를 테스트에 추가한다.

## 파일 위치와 이름
- 어디에 무슨 테스트가 있는지는 `tests/README.md`의 색인에서 찾는다. 새 테스트 파일을 만들면 색인에도 적는다.
- **새 테스트는 기능 폴더에 둔다**(`tests/<기능>/test_<기능>[_<측면>].py`, 폴더에 `__init__.py`). 이슈 번호·리뷰 회차는 파일 이름이 아니라 docstring에 적는다. 옛 테스트는 옮기지 않는다.
- 새 폴더의 테스트가 저장소 루트를 찾을 때는 `Path(__file__).parents[N]`을 또 쓰지 말고 `tests/paths.py`의 `REPO`를 쓴다(쓰는 테스트가 생길 때 만든다. 빈 파일을 미리 만들지 않는다).
- **테스트 파일을 다른 테스트의 라이브러리로 쓰지 않는다.** 공용 헬퍼·대역은 `tests/support/`나 `tests/fakes/`에 둔다. 옛 허브(`test_out_of_scope`, `test_etf_tax`, `test_plan_context`, `test_etf_name_correction`)에서 새로 import하지 않는다.
- **잠긴 수용 테스트**: 수용 기준을 고정한 기존 테스트의 기대값은 고치지 않고 새 파일에 추가한다. `git diff -M`에서 유사도 100%인 이름 변경·이동은 내용 변경이 아니므로 허용하고, 한 글자라도 바뀌면 변경으로 본다(경로 깊이를 고치는 이동은 허용되지 않는다). 논의는 #215.
