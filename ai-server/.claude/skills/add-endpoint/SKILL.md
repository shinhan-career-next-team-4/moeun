---
name: add-endpoint
description: ai-server에 새 HTTP 엔드포인트를 추가할 때 사용. 라우터, 요청/응답 스키마, 내부 키 검사, 테스트를 한 쌍으로 만든다.
---

# 엔드포인트 추가

1. **스키마**: `app/schemas.py`에 `ApiModel`을 상속한 요청·응답 모델. 파이썬은 snake_case, JSON은 camelCase(자동). 길이·범위는 `Field`로 제한한다.
2. **라우터**: 새 엔드포인트는 `app/api/<기능>.py`의 `APIRouter`에 둔다(목표 구조). `main.py`에서 `app.include_router(...)`.
   - 의존성은 `Annotated[..., Depends(...)]` 별칭으로 받는다. 서비스 객체는 `app.state`에서 꺼내는 getter로 주입한다.
   - `/health` 외에는 반드시 `_: InternalOnly`.
   - 핸들러는 얇게 둔다. 로직은 `llm/`, `rag/`, `tools/`에.
3. **오류**: 도메인 예외를 올리고 `{"error": CODE}` 형태로 응답한다(Spring이 FAILED로 기록).
4. **테스트**: `TestClient` + `dependency_overrides`로 내부 키 없을 때 401, 정상 요청의 camelCase 응답을 검증한다.
5. `README.md`의 엔드포인트 목록과 Spring 계약 표를 고친다.

마지막에 `uv run pytest && uv run ruff check . && uv run mypy`.
