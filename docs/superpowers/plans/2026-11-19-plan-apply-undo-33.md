# #33 적용·되돌리기 서비스와 되돌리기 REST — 착수 계획

조사일 2026-11-19. 기준 브랜치 `develop`(PR #116 머지 반영). 선행 #12·#19·#29는 닫혔고, #20은 PR #121로 리뷰 중(머지 전에는 `db/erd.md`를 건드리지 않는다).

## 1. 이미 develop에 있는 것

| 수용 기준 | 상태 | 위치 |
|---|---|---|
| 적용 시 새 버전 + 시작월 복사 | 있음 | `PlanService.apply` (`current.getCalcStartMonth()` 복사) |
| revision 불일치 → 409 PLAN_CHANGED | 있음 | `replaceCurrent(...) == 0` → `PlanConflictException` |
| `plan` 없음 → 409 NO_PLAN | 있음 | `apply` 첫 줄 |
| 허용 밖 키 → 422 FIELD_NOT_CHANGEABLE, 범위·정수·소수 → 422 VALIDATION_FAILED(min/max/unit/scale) | 있음 | `ConditionValidator`, `PlanValidationException.codeOf` |
| 되돌리기 = 직전 버전 복사 INSERT + `previous=NULL` + revision+1 + source 복사 | 있음 | `PlanService.undo`, `copyVersion` |
| 되돌리기 순서(읽기 → NOTHING_TO_UNDO → PLAN_CHANGED → INSERT → 조건부 UPDATE) | 있음 | `PlanService.undo` |
| `POST /api/plan/undo` `{expectedRevision}` → `200 {revision}`, `undoAvailable` 없음 | 있음 | `PlanController.undo`, `RevisionResponse` |
| 0행 롤백으로 `plan_version` 고아 행 없음 | 있음(런타임 예외 + `@Transactional`) | `PlanService` |
| `PlanChanged(APPLY/UNDO)` 발행 | 있음 | `PlanService` |
| `persistence.md:14` '0행이면 롤백 + 409' (X-10) | 있음 | PR #116에서 반영 |

## 2. 남은 작업 (차이)

1. **공개 API 모양** — `PlanApplyService.apply(long, ChangePath, ConditionOverride, long)` → `apply(long memberId, ChangePath path, Map<String, JsonNode> conditionChanges, long expectedRevision, Long expectedBaseVersionId)`, 반환 `PlanSaveResult` → 새 record `PlanApplyResult(planVersionId, previousVersionId, revision)`. 키는 camelCase.
2. **기준 버전 검사(검사 9)** — `expectedBaseVersionId != null && plan.currentVersionId != expectedBaseVersionId` → 새 `StaleBaseVersionException`(STALE_COMPARISON). 검사 8(revision)보다 뒤에 두어 둘 다 걸리면 `PLAN_CHANGED`가 이긴다.
3. **경로 제한** — 서비스에서 `INPUT_EDIT`이면 `IllegalArgumentException`(ALTERNATIVE·COMPARE·CHAT만).
4. **예외 재배치** — plan 루트에 `NoPlanException`, `ConditionViolationException`, `PlanChangedException`, `StaleBaseVersionException`. `ErrorResponseException`을 상속해 `code`·`errors: []`를 직접 담고, `config` 전역 advice는 손대지 않는다. `plan.application.PlanConflictException`/`PlanValidationException`은 이들로 흡수. `plan.web.PlanExceptionHandler`의 역할이 줄거나 사라지는지 확인(입력 저장 경로 #30 호출자 포함 회귀 확인 필요).
5. **`ConditionOverride` → Map** — `ConditionValidator.validate(path, Map<String, JsonNode>)`로 바꾸면 calc 모듈 4개 파일(`ConditionResolver`, `CompareConditionsUseCase`, `PlanContextProviderImpl`, `InternalCalcController`)과 테스트 3개가 영향권. 공개 API에서 `ConditionOverride`를 없앨지, 적용 경로만 Map으로 받고 `ConditionOverride`는 비교 요청 본문 타입으로 남길지 **착수 시 결정**(Issue는 '`ConditionOverride` → Map'만 말하고 calc 쪽은 범위 밖이므로, 범위를 넘지 않는 쪽 = plan 공개 API에 Map 오버로드를 추가하고 `ConditionOverride`는 그대로 두는 안을 1안으로 둔다).
6. **P-6 제거** — `POST /api/plan/apply` 컨트롤러 메서드와 `ApplyRequest` 삭제(#68 Should). `PlanApplyService`는 ALTERNATIVE·COMPARE를 계속 지원.
7. **`PlanUndoService`** — `plan.application`에 인터페이스(공개 API 아님), 호출자는 `plan.web`뿐. 현재는 `PlanService.undo` public 메서드.
8. **문서** — `db/moved-to-code.md` 멱등 키 행(#20 PR에서 이미 '롤백 + 409'로 고침 → 확인만), `db/erd.md` Q4-b(기준 버전 검사)·Q4-c(복사 INSERT), `db/tables.md` `source` 한 줄, `backend/src/main/resources/db/schema.sql` 81행 주석을 '복사한 원본의 경로'로. CHECK(`ck_pv_source`, 91행)는 그대로.

## 3. 순서

1. 브랜치 `feature/33-plan-apply-service`를 최신 develop에서 다시 딴다.
2. 테스트 먼저: `PlanServiceTest`에 STALE_COMPARISON, INPUT_EDIT 거부, revision+기준버전 동시 불일치, 0행 롤백 시 `plan_version` 행 수 불변, 되돌리기 후 V1 기준 적용 → STALE_COMPARISON. `PlanApiTest`에 `POST /api/plan/undo` 성공·NOTHING_TO_UNDO·PLAN_CHANGED·NO_PLAN, `/api/plan/apply` 404(없어짐).
3. 구현: 예외 재배치 → 공개 API 시그니처 → 검사 9 → P-6 제거 → `PlanUndoService`.
4. `./gradlew checkstyleMain checkstyleTest test`, `--tests ModularityTests`.
5. **#20(PR #121) 머지 뒤** `db/erd.md` Q4-b·Q4-c와 `db/tables.md`·`schema.sql` 주석 정정을 같은 브랜치에 올린다. 머지 전에는 `db/erd.md`를 건드리지 않는다.
6. PR을 develop으로, `Closes #33`.

## 4. 막히면

- 5번(Map 전환 범위)에서 calc 모듈 파일을 고쳐야 한다고 판단되면 Issue의 paths 밖이므로 멈추고 사람에게 묻는다.
