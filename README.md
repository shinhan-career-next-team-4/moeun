# moeun

목표 달성 시뮬레이션 서비스.

| 폴더 | 역할 | 스택 | 로컬 포트 |
|---|---|---|---|
| `frontend/` | 웹 클라이언트 | Next.js 16 · React 19 · TypeScript | 5173 |
| `backend/` | API 서버, 금액 계산 | Java 21 · Spring Boot 4 · Spring Modulith | 8080 |
| `ai-server/` | AI 설명, RAG | Python · FastAPI · uv | 8000 |
| `db/` | DB 설계 문서 | PostgreSQL 16 + pgvector | 5432 |

호출 방향: `frontend → backend → ai-server`. 금액 계산은 backend에서만 한다.

## 준비물

Java 21, Node 24, [uv](https://docs.astral.sh/uv/), Docker Desktop

## 로컬 실행

```bash
# 1. DB (pgvector/pg16). 스키마는 backend가 기동할 때 만든다
docker compose up -d

# 2. backend (backend/.env에 DEMO_ACCOUNTS=demo01:비밀번호 를 넣으면 기동 시 데모 계정을 만든다)
cd backend && ./gradlew bootRun

# 3. ai-server (처음 한 번: cp .env.example .env 후 API 키 입력)
cd ai-server && uv sync && uv run uvicorn app.main:app --reload --port 8000

# 4. frontend
cd frontend && npm install && npm run dev
```

`backend/.env`와 `ai-server/.env`는 커밋하지 않는다. 두 파일의 `INTERNAL_API_KEY`는 같아야 한다.

DB 중지: `docker compose stop` · 데이터까지 삭제: `docker compose down -v`

`schema.sql`의 컬럼이 바뀌면 `docker compose down -v`로 DB를 다시 만든다(`CREATE TABLE IF NOT EXISTS`라 기존 테이블은 바뀌지 않는다).

## 검사

| 모듈 | 명령 |
|---|---|
| frontend | `npm run lint && npm run type-check && npm run test:run` |
| backend | `./gradlew checkstyleMain checkstyleTest test` (Docker 필요) |
| ai-server | `uv run ruff check . && uv run mypy && uv run pytest` |

모듈별 규칙은 각 폴더의 `CLAUDE.md`를 본다.

## 브랜치

`develop`에서 작업 브랜치를 만들어 PR한다. `main`에는 `develop`에서 온 PR만 머지된다.
