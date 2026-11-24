# moeun backend

목표 달성 시뮬레이션 서비스의 Spring 서버. 금액 계산(`CalcEngine`)은 이 서버에서만 한다(NFR-INT-01). AI 설명과 RAG 적재·검색은 `../ai-server`가 맡는다.

Java 21 · Spring Boot 4.1 · Spring Modulith 2.1(이벤트 기반) · PostgreSQL 16 + pgvector · JPA · JWT(Resource Server) · REST Docs → OpenAPI

## 명령

- 모듈 경계·내부 책임 검증: `./gradlew test --tests ModularityTests --tests ArchitectureTests` (Docker 불필요, 빠름)
- 코드 스타일 검사: `./gradlew checkstyleMain checkstyleTest`
- 전체 테스트: `./gradlew test` (Docker 필요: Testcontainers `pgvector/pgvector:pg16`)
- API 문서 생성: `./gradlew openapi3` → `build/api-spec/openapi3.yaml`

## 구조

모듈 책임·의존, 모듈 내부 패키지(루트·`domain`·`application`·`infrastructure`·`web`)의 책임, 클래스 이름 규칙은 아래 문서가 기준이다.

@ARCHITECTURE.md

## 규칙

- 모듈 경계와 이벤트: `.claude/rules/modulith.md`
- 모듈 내부 책임과 이름: `ARCHITECTURE.md`(`ArchitectureTests`가 검사한다)
- DB와 영속성: `.claude/rules/persistence.md`
- 테스트: `.claude/rules/testing.md`
- 코드 스타일: `.claude/rules/code-style.md`
- 작업을 끝낼 때 Java가 바뀌었으면 훅(`.claude/hooks/check-java.sh`)이 checkstyle과 ModularityTests·ArchitectureTests를 돌린다. 실패하면 고친 뒤 끝낸다.
- 기능 구현 후나 PR 전에는 `backend-reviewer` 에이전트로 규칙 위반을 점검한다.
- 개인 설정은 `.claude/settings.local.json`, `CLAUDE.local.md`에 둔다(git 제외).

## 근거 문서

- 스키마 원본: `src/main/resources/db/schema.sql`
- DB 설계 결정: `../db/`
  - `decision.md`: ADR-002 최소 저장 원칙, JWT 전환
  - `tables.md`: 테이블별 저장 이유
  - `moved-to-code.md`: DB에서 코드로 옮긴 규칙과 클래스 이름. 클래스 이름은 이 문서를 따른다.
- ai-server 계약: `../ai-server/README.md`
