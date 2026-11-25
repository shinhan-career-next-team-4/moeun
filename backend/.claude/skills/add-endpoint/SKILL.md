---
name: add-endpoint
description: 백엔드에 REST 엔드포인트를 추가할 때 사용한다. 모듈 선택, 소유권 조건, REST Docs 테스트까지 한 번에 처리한다.
---

# 엔드포인트 추가

1. 어느 모듈인지 정한다. `CLAUDE.md`의 모듈 표에서 다루는 테이블을 기준으로 고른다. 다른 모듈의 데이터가 필요하면 그 모듈의 공개 API(루트 패키지)를 쓰고, `allowedDependencies`에 그 모듈이 있는지 확인한다.
2. 컨트롤러와 요청·응답 DTO는 `<모듈>/web`에 둔다. 사용자는 `@AuthenticationPrincipal Jwt jwt`의 subject로 식별한다. 요청 본문에서 memberId를 받지 않는다.
3. 입력은 Bean Validation으로 검증한다. 계획 조건 값은 `ConditionValidator.validate(path, values)`를 거친다.
4. 리포지토리 쿼리에 `memberId` 조건을 붙인다.
5. `src/test/.../docs/`에 REST Docs 테스트를 추가한다(`HealthDocsTest` 패턴, tag는 모듈 이름).
6. `./gradlew test --tests ModularityTests`를 돌리고, 이어서 새 테스트를 돌린다.
