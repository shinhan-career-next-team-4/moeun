# DB에서 코드로 옮긴 것

최소 저장 원칙(ADR-002, `final/decision.md`)에 따라 v1 스키마(`final/v1/schema.sql`)에서 삭제한 테이블·컬럼·제약을 정리한다. 각 항목이 어디로 갔는지와 관련 REQ-ID를 적었다.

- enum·클래스·설정 키 이름은 구현 시 이 이름을 쓰자는 제안이다. 아직 코드가 없으므로 구현할 때 이 문서를 같이 고친다.
- 공통 설정 파일은 두 개다. 두 서버(Spring, FastAPI)가 함께 읽는다(NFR-INT-03).
  - `config/rules.yml`: 입력 규칙·경로 규칙
  - `copy/copy.ko.yml`: 문구표 (CP-05)
- 계산은 전부 Spring 계산 엔진 `CalcEngine`에서 한다(NFR-INT-01). 같은 입력, 같은 기준 데이터 버전, 같은 `engine_version`이면 같은 결과가 나와야 한다. 계산 로직을 바꾸면 반드시 `engine_version`을 올린다.

## 1. 삭제한 테이블

| 무엇을 | 어디로 | 관련 REQ-ID |
|---|---|---|
| `input_field` (필수 여부, 허용 범위, 단위) | enum `InputField` + 설정 `rules.yml › input.fields.<FIELD>.{required,min,max,unit}`. 검증은 `ConditionValidator.validate(path, values)` | CP-02, FR-INP-09, CP-04 |
| `change_path` (경로, 최대 변경 개수) | enum `ChangePath {INPUT_EDIT, ALTERNATIVE, COMPARE, CHAT}` + 설정 `rules.yml › change-paths.<PATH>.max-fields` | CP-07 |
| `change_path_field` (경로별로 바꿀 수 있는 항목) | 설정 `rules.yml › change-paths.<PATH>.fields`. 검사는 `ConditionValidator.validate(path, values)` | CP-07, CP-06, FR-ALT-03, FR-CMP-02, FR-AI-06, FR-AI-09 |
| `goal_type` (목표 종류, 직접 입력 여부, 정렬) | enum `GoalType`(직접 입력은 `CUSTOM`), 순서는 enum 선언 순서, 표시명은 `copy.ko.yml › goal.<CODE>` | FR-INP-01, FR-INP-02, CP-05 |
| `calc_param_key` (파라미터 항목, 단위, 필수 여부, 설명) | enum `CalcParamKey(unit, required)`. 설명은 `copy.ko.yml › assumption.<KEY>`. 필수 키 검사는 적재 스크립트 `CalcParamLoader.validateRequiredKeys()`가 버전을 활성화하기 전에 수행 | CP-03, FR-RES-06 |
| `embedding_model` (모델 사전, 차원) | 문자열 컬럼 `chunk_embedding.model_code`. 현재 모델과 차원은 설정 `rag.embedding.model`, `rag.embedding.dimensions=1536` | NFR-INT-04 |
| `calc_scenario_result` (시나리오별 세후 자산, 목표 대비, 달성 여부, 도달 시점, 월별 시계열) | 계산 함수 `CalcEngine.calculate(input, dataVersions) → CalcResult.scenarios[GOOD/AVG/BAD]`. 화면을 열 때마다 `calc_run`에 기록된 버전으로 다시 계산 | CP-01, FR-RES-02, FR-RES-03, FR-RES-04, FR-RES-09, FR-CMP-01, FR-AI-02 |
| `calc_alternative` (대안 카드 3종, READY/UNAVAILABLE, 사유 문구 키) | 계산 함수 `AlternativeFinder.find(input, result, baseline)`. 변경 단위는 `calc_param`(ALT_STEP_*), 사유 문구는 `copy.ko.yml › alt.unavailable.<FIELD>` | FR-ALT-01, FR-ALT-02, FR-ALT-03, FR-ALT-04 |
| `calc_run_override` (비교·대화로 바꾼 조건) | 비교: 요청 본문 `ConditionOverride`로만 존재하고 저장하지 않음. 대화: `chat_message.condition_changes`(재계산에 쓴 값) | FR-CMP-02, FR-CMP-03, FR-AI-07, FR-PLAN-01 |
| `plan_application` (적용·되돌리기 채택 이력) | 앱 감사 로그 이벤트 `plan.changed {memberId, action: INPUT/APPLY/UNDO, beforeVersionId, afterVersionId, revision}` | FR-PLAN-01, FR-PLAN-03, FR-PLAN-04 |
| `user_session` (서버 세션) | 무상태 JWT. `JwtTokenService`(만료 `auth.jwt.ttl=30m`). `TokenRenewalInterceptor`가 사용자 조작 요청에만 새 토큰을 발급하고, 폴링 엔드포인트는 `@SkipRenewal`로 제외. 로그아웃은 클라이언트가 토큰을 삭제 | FR-AUTH-02, FR-AUTH-03, NFR-SEC-02 |

## 2. 삭제한 컬럼

| 테이블.컬럼 | 어디로 | 관련 REQ-ID |
|---|---|---|
| `member.display_name` | 삭제. SRS가 요구하지 않음 | FR-AUTH-01 |
| `member.last_login_at` | 로그 `auth.login {memberId, at}` | FR-AUTH-01 |
| `member.is_active`, `created_at` | 삭제. 데모 계정은 미리 발급하며 비활성화 요구가 없음. 계정을 막으려면 행을 지움 | FR-AUTH-01 |
| `plan_version.version_no` | `id` 순서로 대체 | FR-PLAN-04 |
| `plan_version.goal_is_custom` | `goal_type_code == GoalType.CUSTOM` | FR-INP-02 |
| `plan_version.has_deadline` | `target_period_months != null` | FR-INP-04 |
| `plan_version.ratio_source` | `RatioResolver.resolve(pv, params)`의 분기로 결정: `savings_ratio_pct`가 있으면 USER, 기존 납입액이 있으면 DERIVED_EXISTING, 둘 다 없으면 DEFAULT | FR-INP-08 |
| `plan_version.savings_ratio_pct`의 산출값 (NOT NULL → NULL 허용) | 사용자가 직접 정한 비중만 저장. 저장값이 없으면 `RatioResolver`가 `calc_param.DEFAULT_SAVINGS_RATIO`를 쓴다. 기존 납입액(적금·ETF)은 비중 선택에만 쓰고 계산에 직접 쓰지 않는다 | FR-INP-08, CP-04 |
| `plan_version.applied_from_calc_run_id` | 삭제. 버전의 출처는 `plan_version.source`로 충분함. COMPARE 실행은 저장하지 않음 | FR-ALT-04, FR-PLAN-03 |
| `plan.current_calc_run_id`, `current_run_type` | 조회 `CalcRunRepository.findLatest(planVersionId, PLAN)` (인덱스 `ix_calc_run_latest`) | FR-PLAN-05, FR-RES-05 |
| `plan.updated_at` | 로그 `plan.changed` | FR-PLAN-03 |
| `plan_application.request_key` (멱등 키) | `plan.revision` 낙관적 락으로 대체. 같은 적용을 두 번 보내면 두 번째는 `revision`이 맞지 않아 0행 갱신이 되고, 앱은 현재 계획을 그대로 돌려줌 | FR-PLAN-03 |
| `calc_run.member_id` | `plan_version.member_id`로 조인 | NFR-SEC-02 |
| `calc_run.result_status` | `CalcEngine`: `held_assets >= goal_amount`이면 `AlreadyExceeded` | FR-RES-01 |
| `calc_run.goal_amount`, `held_assets` (복사값) | `plan_version`에서 읽음 | FR-INP-03, FR-INP-05 |
| `calc_run.applied_monthly_saving`, `applied_period_months`, `applied_savings_rate_pct`, `savings_ratio_pct` | `ConditionResolver.resolve(planVersion, changes, dataVersions)`: 기본금리, 비중 규칙, 대화 변경값을 반영한 실제 투입값 | FR-INP-06, FR-INP-07, FR-INP-08, CP-03 |
| `calc_run.has_etf` | 해석된 비중 `< 100` | FR-RES-03 |
| `calc_run.calc_start_month` | `plan_version.calc_start_month` | FR-RES-04, FR-PLAN-05 |
| `calc_run.point_count`, `monthly_principal` | `CalcEngine` 결과 시계열 | FR-RES-04 |
| `calc_run.required_monthly_saving` | `CalcEngine.requiredMonthlySaving(input)` | FR-RES-08 |
| `calc_run` 행 중 `run_type='COMPARE'` | 저장하지 않음. 비교는 요청마다 계산 | FR-CMP-01, FR-CMP-02 |
| `data_version.*` 종류 확인용 `data_kind` 생성 컬럼 (`savings_avg_rate`, `sp500_monthly`, `calc_param`, `calc_run.*_kind`) | `DataVersionService.requireKind(id, kind)`로 적재·기록 시 검사 | NFR-INT-02 |
| `rag_document.org_type` (복사값) | `source_org.org_type` 조인 | FR-AI-03 |
| `rag_document.license_status` | 적재 스크립트 `RagIngestor`가 이용 허락된 문서만 넣음. 허락 목록은 `rag.allowlist` | FR-AI-10 |
| `rag_chunk.token_count` | 청크를 나눌 때 토크나이저로 계산. 적재 로그 | NFR-INT-04 |
| `rag_chunk.doc_type`, `source_org_id`, `is_active` (반정규화) | `rag_document` 조인 | FR-AI-03, FR-AI-10 |
| `chunk_embedding.doc_type`, `source_org_id`, `created_at` | `rag_chunk → rag_document` 조인. 적재 시각은 로그 | FR-AI-03, NFR-INT-04 |
| `chat_message.prompt_version`, `model_version`, `copy_version` | 로그 `ai.attempt {messageId, promptVersion, modelVersion, copyVersion, latencyMs}`. 근거는 아래 '판단 근거'의 1번 | FR-AI-04, FR-AI-05, CP-05 |
| `chat_message.input_source` (직접 입력 / 질문 예시 버튼) | 로그 `ai.question {messageId, source: TYPED/SUGGESTION}`. 근거는 '판단 근거'의 2번 | FR-AI-08 |
| `chat_message.error_code` | 로그 `ai.attempt.error {messageId, code}`. 대화창의 실패 안내는 `status='FAILED'`, `message_type='FAILURE'`, `content`로 복원 | FR-AI-04 |
| `chat_message.completed_at` | 로그 `ai.attempt.latencyMs` | NFR-PERF-01 |
| `chat_message.plan_version_id` | 재계산·설명 메시지는 `calc_run.plan_version_id`로 조인. 그 밖의 메시지는 계획 참조가 필요 없음 | FR-AI-06, FR-AI-07 |

## 3. 삭제한 제약·트리거

| 무엇을 | 어디로 | 관련 REQ-ID |
|---|---|---|
| 모든 `(member_id, id)` 소유권 복합 FK | 모든 쿼리에 `WHERE member_id = :currentUser` 조건(리포지토리 공통 규칙). `calc_run`은 `plan_version` 조인으로 검사 | NFR-SEC-02 |
| `fk_cr_goal` (복사값 일치 FK), `fk_plan_current_calc_run`, `fk_csr_run`, `fk_alt_base` | 복사값과 결과 테이블이 없어져 불필요 | FR-RES-02, FR-ALT-01 |
| `trg_calc_param_required` | `CalcParamLoader.validateRequiredKeys()` | CP-03 |
| `trg_plan_application` | 적용·되돌리기 서비스가 한 트랜잭션에서 `plan_version` INSERT와 `plan` UPDATE(revision 조건)를 함께 처리 | FR-PLAN-03, FR-PLAN-04 |
| `trg_override_rule` (바꾼 값의 범위, 경로별 개수) | `ConditionValidator.validate(path, values)`. 입력·대안·비교·대화 모든 경로가 같은 함수를 거친다 | CP-02, CP-07, FR-INP-09 |
| `trg_calc_run_complete` (시나리오 행 수 검사) | 결과를 저장하지 않으므로 불필요. 결과는 `CalcEngine`이 한 번에 만든다 | FR-RES-03 |
| `trg_rag_chunk_immutable`, `trg_chunk_embedding_immutable` | `RagIngestor`는 기존 행의 본문·임베딩을 UPDATE하지 않는다. 바꾸는 것은 `chunk_embedding.is_active`(검색 제외)뿐이다. 개정본은 새 `rag_document` 행으로 넣는다(`content_sha256`). 청킹 규칙만 바뀐 경우는 재청킹으로 같은 문서에 새 청크를 이어 넣고 이전 임베딩을 끈다(`erd.md` §5-E). DB 계정 권한으로 막는다면 `rag_chunk`는 UPDATE 전체, `chunk_embedding`은 `is_active` 외 컬럼의 UPDATE를 막는다 | FR-AI-03, FR-AI-10 |
| `ck_pv_custom_name`, `ck_pv_deadline`, `ck_pv_existing_le`, `ck_pv_ratio_source`, `ck_pv_ratio_derived`, `ck_pv_applied_from` | `PlanInputValidator`: 직접 입력이면 목표명 필수, 기존 납입액 ≤ 월 저축액 | FR-INP-02, FR-INP-04, FR-INP-08 |
| `ck_rag_disclosure_org`, `ck_rag_active_licensed` | `RagIngestor`: 공시 문서는 `org_type=ASSET_MANAGER` 기관만, 허락된 문서만 적재 | FR-AI-03, FR-AI-10 |
| `ck_cm_input_source`, `ck_cm_failed`, `ck_cm_attempt_versions` | 해당 컬럼이 로그로 이동 | FR-AI-04, FR-AI-08 |
| `ck_session_expiry`, `ix_user_session_*` | JWT `exp` 클레임 검증(`JwtTokenService.verify`) | FR-AUTH-02, NFR-SEC-02 |
| `ck_cr_already`, `ck_cr_required`, `ck_cr_etf_version`, `ck_csr_*`, `ck_alt_*` | `CalcEngine` 단위 테스트로 보장한다(0원 = 달성, ETF 0%는 AVG 한 가지, 미달 시나리오에만 대안) | CP-01, FR-RES-01, FR-RES-08, FR-ALT-01 |
| `ix_calc_run_cleanup` (비교 실행 정리) | 비교 실행을 저장하지 않으므로 불필요 | NFR-AVL-01 |

## 4. 처음부터 DB 대상이 아니던 요구사항 (연결 위치)

| REQ-ID | 위치 |
|---|---|
| CP-04 | 표시 형식 `Formatter`('약 N만원', 부호와 달성/미달 라벨, 'n년 n개월', '적금 a : ETF b') |
| CP-05 | 문구표 `copy/copy.ko.yml` 하나. 프론트와 AI 서버가 함께 읽음 |
| CP-06 | 상품·종목 테이블 없음. `rules.yml › change-paths`에 상품 선택 항목 없음. 추천 금지 표현은 `copy.ko.yml`과 AI 프롬프트 |
| FR-AI-01 | 비동기 처리: 계산 응답 후 AI 서버 호출. DB는 `chat_message.status=PENDING` |
| FR-PLAN-02 | 취소는 쓰기 없음 |
| FR-RES-07 | 면책 문구 `copy.ko.yml › disclaimer` |
| FR-CMP-03 | '원래대로'는 프론트 상태 초기화. 저장 없음 |
| NFR-AVL-01 | 운영(배포, 모니터링) |
| NFR-CMP-01, NFR-CMP-02, NFR-UI-01 | 프론트 |
| NFR-INT-01 | `CalcEngine`은 Spring에만 있음. DB는 `calc_run.engine_version` 기록 |
| NFR-INT-03 | FastAPI가 Spring 계산 API를 도구로 호출(`POST /calc`) |
| NFR-PERF-01 | 목표치는 TBD-33. 측정은 로그 `ai.attempt.latencyMs`, `calc.latencyMs` |
| NFR-USA-01 | 쉬운 말 안내는 `copy.ko.yml`과 AI 프롬프트. 계획 없이 대화하는 경우는 `chat_message.member_id`가 지원 |
| NFR-USA-02 | 추천 금지 문구는 `copy.ko.yml`과 AI 프롬프트에 함께 적용 |

## 판단 근거 (항목 6)

1. **`prompt_version / model_version / copy_version` → 로그.** SRS가 버전 기록을 요구하는 곳은 NFR-INT-02 하나다. 그 대상은 "계산에 사용한 기준 데이터(금리, 지수 등)"이고, AI 프롬프트·모델·문구표 버전은 포함하지 않는다. CP-05는 문구표를 파일 하나로 관리하라는 요구이고 버전 기록 요구가 아니다. FR-AI-04/05가 요구하는 것은 실패 안내 표시와 다시 요청이다. 이는 `status`, `message_type`, `retry_of_message_id`로 충족된다. 감사용 버전 추적은 메시지 id로 로그와 이어 붙이면 된다.
2. **`input_source` → 로그.** FR-AI-08은 질문 예시를 버튼으로 보여주라는 요구다. 사용자가 버튼을 눌렀는지 직접 입력했는지는 대화 복원이나 계산에 쓰이지 않는다.
