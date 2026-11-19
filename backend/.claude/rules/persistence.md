---
paths:
  - "src/main/**"
---

# DB·영속성 규칙 (ADR-002 최소 저장 원칙)

- 스키마 원본은 `src/main/resources/db/schema.sql`이다. `ddl-auto=validate`라서 엔티티가 스키마와 다르면 기동에 실패한다. 테이블을 바꾸면 `../db/` 문서도 같이 고친다.
- DB에는 다시 만들 수 없는 것만 저장한다. 계산 결과, 대안, 비교 결과는 저장하지 않고 `CalcEngine`이 다시 계산한다.
- 바뀔 수 있는 규칙(입력 범위, 경로별 허용 항목, 목표 종류, 파라미터 키)은 enum과 `config/rules.yml`에 둔다. DB CHECK에 넣지 않는다.
- 소유권은 DB가 막지 않는다.
  - 사용자 데이터를 다루는 리포지토리 메서드는 모두 `memberId` 조건을 받는다(예: `findByIdAndMemberId`).
  - `memberId`는 JWT subject에서만 얻는다. 요청 본문에서 받지 않는다(ai-server 전용 `/internal/*`은 예외).
  - 예외: `calc_run`은 회원 키가 없어 plan 공개 API(`PlanSaveService.findVersion(memberId, planVersionId)`)로 소유권을 확인한다. plan 테이블을 JOIN하지 않는다(G-13).
- `plan_version`은 INSERT만 한다. `plan`은 `WHERE revision = :expected`로 갱신하고, 0행이면 `PlanChangedException`으로 롤백한다(409 `PLAN_CHANGED`, 본문에 현재 상태를 싣지 않는다. 계약 X-10).
- `calc_run`의 `*_version_id`는 `DataVersionService.requireKind`로 데이터 종류를 확인한 뒤 기록한다.
- `CalcEngine`의 계산 로직을 바꾸면 `engine_version` 상수를 올린다.
- `rag_*`, `source_org`, `chunk_embedding`은 ai-server가 쓴다. Spring은 읽기만 한다.
- 코드의 enum 값은 `schema.sql`의 CHECK 목록과 일치시킨다(예: `baseline_scenario` GOOD/AVG/BAD).
