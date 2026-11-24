# moeun backend

Spring Boot 서버. 계산 엔진, 계획 저장·적용·되돌리기, 로그인, 대화 저장과 ai-server 호출을 맡는다. 구조는 [ARCHITECTURE.md](ARCHITECTURE.md), 작업 규칙은 [CLAUDE.md](CLAUDE.md).

## 로컬 실행

순서: DB → backend → ai-server([../ai-server/README.md](../ai-server/README.md)) → frontend.

### 1. DB

저장소 루트에서 띄운다. pgvector가 들어 있는 PostgreSQL 16이다(DB·계정·비밀번호 모두 `moeun`).

```bash
docker compose up -d
```

테이블은 서버가 뜰 때 `src/main/resources/db/schema.sql`로 만든다(`SQL_INIT_MODE` 기본 `always`).

### 2. `backend/.env`

서버는 **`backend/`에서 실행할 때만** `backend/.env`를 읽는다(`application.yml`의 `optional:file:.env`). 파일은 커밋하지 않는다.

| 변수 | 필수 | 뜻 |
|---|---|---|
| `JWT_SECRET` | 예 | 로그인 토큰 서명 키. 32바이트 이상. 없으면 기동에 실패한다 |
| `DEMO_ACCOUNTS` | 예 | 로그인 계정 `아이디:비밀번호,아이디:비밀번호`. 시연 계정과 E2E 테스트 계정을 따로 둔다 |
| `INTERNAL_API_KEY` | 아니오 | ai-server 호출 키(기본 `local-internal-key`). **`ai-server/.env`의 값과 같아야 한다.** 다르면 대화가 "답변을 만들지 못했어요"로 끝나고 ai-server 로그에 `POST /chat 401`이 찍힌다 |
| `AI_SERVER_URL` | 아니오 | 기본 `http://localhost:8000` |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | 아니오 | 기본은 위 docker compose DB |
| `CORS_ALLOWED_ORIGINS` | 아니오 | 기본 `http://localhost:5173`(frontend 개발 서버) |
| `SERVER_PORT` | 아니오 | 기본 `8080` |
| `MOEUN_SP500_FILE`, `MOEUN_ETF_069500_FILE`, `MOEUN_ETF_133690_FILE` | 아니오 | ETF 가격 CSV 위치. 기본은 아래 `data/seed/` |

### 3. ETF 가격 CSV(각자 준비)

ETF가 섞인 계산(좋았던 때·평균·나빴던 때)에는 월말 종가 CSV가 필요하다. 저장소에는 없으니 팀에서 받아 아래에 둔다. 출처·형식은 [src/main/resources/seed/README.md](src/main/resources/seed/README.md).

```
backend/data/seed/sp500-monthly.csv         # S&P500 추종(대표)
backend/data/seed/etf-069500-monthly.csv    # 코스피200 추종
backend/data/seed/etf-133690-monthly.csv    # 나스닥100 추종
```

적금 평균금리·계산 파라미터는 저장소에 있다(`src/main/resources/seed/`).

### 4. 실행

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

`local` 프로필은 http에서도 로그인 쿠키가 동작하도록 Secure 쿠키를 끄고 SQL 로그를 켠다. 코드를 바꾸면 다시 띄워야 반영된다.

### 5. 확인

```bash
curl -s localhost:8080/actuator/health          # {"status":"UP"}
curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"loginId":"<아이디>","password":"<비밀번호>"}' -i | head -1   # HTTP/1.1 200
```

## 테스트

Docker가 켜져 있어야 한다(Testcontainers가 pgvector DB를 띄운다).

```bash
cd backend
./gradlew checkstyleMain checkstyleTest test     # 전체(몇 분)
./gradlew test --tests ModularityTests --tests ArchitectureTests   # 모듈 경계·내부 책임만(빠름, Docker 불필요)
./gradlew openapi3                               # API 문서 → build/api-spec/openapi3.yaml
```

실서버로 화면 흐름과 대화를 확인하는 E2E는 [../scripts/e2e/README.md](../scripts/e2e/README.md).

## 자주 막히는 곳

| 증상 | 원인과 해결 |
|---|---|
| 기동 실패 `JWT_SECRET` | `backend/.env`에 32바이트 이상 값을 넣는다. `backend/` 밖에서 실행하면 `.env`를 못 읽는다 |
| 로그인은 되는데 다음 요청이 401 | `local` 프로필 없이 띄우면 로그인 쿠키가 Secure라 http 요청에 붙지 않을 수 있다. `--spring.profiles.active=local`로 띄운다 |
| 대화가 "답변을 만들지 못했어요" | ai-server가 꺼져 있거나 `INTERNAL_API_KEY`가 두 서버에서 다르다 |
| 결과가 평균 하나만 나온다 | ETF 가격 CSV가 없다(위 3번) |
