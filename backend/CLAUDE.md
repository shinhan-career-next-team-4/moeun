# moeun backend

목표 달성 시뮬레이션 서비스의 Spring 서버. 금액 계산(`CalcEngine`)은 이 서버에서만 한다(NFR-INT-01). AI 설명과 RAG 적재·검색은 `../ai-server`가 맡는다.

Java 21 · Spring Boot 4.1 · Spring Modulith 2.1(이벤트 기반) · PostgreSQL 16 + pgvector · JPA · JWT(Resource Server) · REST Docs → OpenAPI

## 명령

- 모듈 경계 검증: `./gradlew test --tests ModularityTests` (Docker 불필요, 빠름)
- 코드 스타일 검사: `./gradlew checkstyleMain checkstyleTest`
- 전체 테스트: `./gradlew test` (Docker 필요: Testcontainers `pgvector/pgvector:pg16`)
- API 문서 생성: `./gradlew openapi3` → `build/api-spec/openapi3.yaml`

## 모듈 (`com.moeun.backend.*`)

| 모듈 | 책임 | 테이블 | 의존 가능 |
|---|---|---|---|
| `auth` | 데모 계정, JWT 발급·검증 | member | — |
| `plan` | 계획 조건 스냅샷, 적용·되돌리기, 조건 검증 | plan_version, plan | — |
| `refdata` | 기준 데이터와 버전(ETF 가격은 종목별) | data_version, savings_avg_rate, etf_monthly, etf_product_version, calc_param | — |
| `calc` | 계산 엔진, 계산 기록, 내 계획 조회·입력 저장 REST, `/internal/calc` | calc_run | plan, refdata, config(copy) |
| `rag` | 인용 출처 조회(읽기 전용) | source_org, rag_document, rag_chunk, chunk_embedding | — |
| `chat` | 대화 저장, ai-server 비동기 호출 | chat_message, chat_message_citation | calc, rag, plan, auth, config :: copy |

- REST 위치: 내 계획 조회·입력 저장(`GET`/`PUT /api/plan`)은 `calc.web`, 되돌리기(`POST /api/plan/undo`)와 대안·비교 적용(`POST /api/plan/apply`, #68)은 `plan.web`이다. 저장과 계산을 한 트랜잭션으로 묶으려고 부르는 쪽(calc)에 둔다. 대화 적용은 chat 모듈이 `PlanApplyService`를 직접 부른다.

`config`는 보안 같은 앱 전역 설정이다. 의존 방향의 기준은 각 모듈의 `package-info.java`이며, 바꿀 때는 이 표도 같이 고친다.

## 규칙

- 모듈 경계와 이벤트: `.claude/rules/modulith.md`
- DB와 영속성: `.claude/rules/persistence.md`
- 테스트: `.claude/rules/testing.md`
- 코드 스타일: `.claude/rules/code-style.md`
- 작업을 끝낼 때 Java가 바뀌었으면 훅(`.claude/hooks/check-java.sh`)이 checkstyle과 ModularityTests를 돌린다. 실패하면 고친 뒤 끝낸다.
- 기능 구현 후나 PR 전에는 `backend-reviewer` 에이전트로 규칙 위반을 점검한다.
- 개인 설정은 `.claude/settings.local.json`, `CLAUDE.local.md`에 둔다(git 제외).

## 근거 문서

- 스키마 원본: `src/main/resources/db/schema.sql`
- DB 설계 결정: `../db/`
  - `decision.md`: ADR-002 최소 저장 원칙, JWT 전환
  - `tables.md`: 테이블별 저장 이유
  - `moved-to-code.md`: DB에서 코드로 옮긴 규칙과 클래스 이름. 클래스 이름은 이 문서를 따른다.
- ai-server 계약: `../ai-server/README.md`
