---
paths:
  - "src/test/**"
---

# 테스트 규칙

- `ModularityTests`는 모듈 경계를 검증하고, 결과 다이어그램을 `build/spring-modulith-docs`에 만든다. 패키지나 의존을 바꾸면 이 테스트부터 돌린다.
- `ArchitectureTests`는 모듈 내부 책임(`ARCHITECTURE.md`)을 ArchUnit으로 검증한다. 기존 위반은 `src/test/resources/archunit_store/`에 동결되어 있고, 위반을 고쳐 저장소가 줄면 그 변경을 같이 커밋한다. 규칙 표본은 `archfixture` 패키지에 둔다.
- 모듈 통합 테스트는 `@ApplicationModuleTest`에 `@Import(TestcontainersConfig.class)`를 붙여 쓴다. 이벤트는 `Scenario` 또는 `AssertablePublishedEvents`로 검증한다.
- DB가 필요하면 `TestcontainersConfig`(pgvector/pg16)를 쓴다. H2는 쓰지 않는다. pgvector와 부분 인덱스를 지원하지 않기 때문이다.
- 엔드포인트마다 REST Docs 테스트를 둔다(`docs/HealthDocsTest` 패턴). 이 테스트가 `openapi3.yaml`의 원천이라, 테스트가 없는 엔드포인트는 API 문서에 나오지 않는다.
- `CalcEngine`에는 "같은 입력 + 같은 데이터 버전 → 같은 결과" 회귀 테스트를 둔다.
