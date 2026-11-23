# 테스트 찾기

기능에서 파일을 찾는 색인이다. 파일 이름이 작업 이력(Issue·리뷰 회차)을 따르는 경우가 있어서 이름만으로는 찾기 어렵다.
새 파일을 만들면 이 표에도 적는다. 위치·이름 규칙은 `../.claude/rules/testing.md`에 있다.

실행: `cd ai-server && uv run pytest`. 파일 하나는 `uv run pytest tests/<파일>`.

## 기능별

| 기능 | 파일 | 무엇을 본다 |
|---|---|---|
| **문구표** | `test_copy.py` | 로더(필수 키·빈 값·중복 키·환경 변수 경로) |
| | `test_copy_cache.py` | 기본 경로 캐시(읽는 횟수, 파일 수정 반영) |
| | `test_fixed_copy_guard.py` | 모델이 고정 문구를 바꿔 말해도 서버가 문구표 문장으로 되돌림 |
| | `copybook/test_copy_rules.py` | 문구표 말투 규칙(면책만 격식체), 고정 문구 감지 앵커가 문구표와 어긋나지 않는지 |
| **planContext·display 키** | `test_plan_context.py` | planContext 받기, display 허용 키 필터, 모델 입력, fixture |
| | `test_display_contract.py` | display 키 규칙(허용·필수·누출 검사)이 분리 전과 같은지 |
| **계산 연동** | `test_calc_client.py` | 실제 `CalcClient` + respx로 계산 응답·오류 소비 |
| | `test_calc_fake_flow.py` | `FakeCalc`로 계산 흐름과 보고값 = 보낸 값 |
| | `test_number_check.py` | 답변 숫자 자동 대조(`NUMBER_MISMATCH`) |
| | `test_no_accumulate.py` | 이전 대화의 조건을 이번 계산에 누적하지 않음 |
| | `conditions/test_conditions_amount_units.py` | 목표 금액 단위 대조가 억 뒤 만 생략(1억 2천)·숫자+천·백(5천만원)을 읽음 |
| | `conditions/test_conditions_carry_kind.py` | 역할 단어 없는 후속 금액을 직전 대화의 조건으로 계산(프롬프트 규칙, 서버가 막지 않음) |
| | `conditions/test_conditions_carried_amount_evidence.py` | #260 이어받아 계산한 금액의 출처 발화만 숫자 근거로 더함 |
| | `test_agent_tools.py` | 도구 스키마, 조건 4개 축소, 되묻기 규칙 |
| **대화 흐름** | `test_agent.py` | 기본 흐름(조건 변경, 인용 제한, 거절, 입력 검사) |
| | `test_explain.py` | EXPLAIN·QUESTION 흐름, 검사 순서, 프롬프트 규칙, 로그 |
| | `test_explain_review_fixes.py` | EXPLAIN 리뷰 회귀(세금 단서의 이력 유입, 검색 뒤 범위 밖 답, EXPLAIN의 범위 밖 답) |
| | `test_out_of_scope.py` | 질문 3유형 분류, 범위 밖 안내 문구 |
| | `test_out_of_scope_explain.py` | EXPLAIN에서도 범위 밖 보정이 우선 |
| | `test_llm_error_log.py` | LLM 호출 실패의 로그와 응답 코드 |
| **금지 표현·가드레일** | `test_forbidden_retry.py` | 금지 표현 거부 전 1회 재시도, 거부 로그(수용 기준 ac1~ac7) |
| | `test_retry_no_resend.py` | 교정 재시도 호출은 SDK 자동 재전송을 하지 않음 |
| | `test_guardrails.py` | 개별 주식 종목 추천만 거부 |
| | `test_internal_key_guard.py` | 본문에 display 키 이름이 나가지 않음 |
| | `test_internal_leak_guard.py` | 본문에 내부 식별자·시스템 프롬프트 문장이 나가지 않음 |
| **근거 인용·RAG** | `test_citations.py` | 운용사 2곳 이상 인용, 이번 요청에서 검색된 chunk만 |
| | `test_rag_search.py` | 운용사 섞기, 재적재 중복 방지, 이용 허락 게이트 |
| | `test_etf_rag_sources.py` | ETF 종목별 공시가 허락 목록·적재 스크립트에 있는지 |
| | `test_etf_code_filter.py` | 검색에 종목 코드 필터(다른 종목 근거 차단) |
| **ETF 종목·세금 단서** | `test_etf_tax.py` | 종목 이름·세율을 설명에 반영(#155 수용 테스트, 다른 파일이 헬퍼를 가져다 씀) |
| | `test_etf_tax_quote.py` | 세율 인용 판정(단서를 지운 자리를 띄울지, P-6) |
| | `test_etf_tax_quote_punctuation.py` | 세율 표시값 뒤 문장 끝 소수점은 인용(P-6 띄우기 판정) |
| | `test_etf_tax_wrong_note.py` | 모델이 쓴 단서는 계획과 맞든 다르든 지우고 붙이지 않음 |
| | `tax_note/test_tax_note_removed.py` | #233 수용 테스트: 세금 단서를 답에 붙이지 않고 모델이 쓴 두 단서를 지움, P-6 |
| | `chat_response/test_chat_response_prompt.py` | #252 대화 응답 기준표 규칙: 고지 반복 금지, 수익 약속 미언급, 결과 설명 조건 나열 금지·3문장 |
| | `error_log/test_agent_error_log.py` | #256 /chat 실패 때 오류 코드(와 거부 사유)만 로그에 남기고 본문은 남기지 않음 |
| | `test_etf_note_join.py` | 단서를 지워도 양옆 글자가 이어져 새 인용이 생기지 않음 |
| | `test_etf_name_correction.py` | 계획 종목 표기 검사·교정 지시 |
| | `test_etf_plan_name_claims.py` | 2라운드 리뷰 재현: 계획 종목 표기 거부(B3), 세율 인용 경계·줄바꿈 단서 제거·빈 본문 거부(B1·B2·B5), 코스피 계획에서 상품명 통과(B6) |
| | `test_etf_out_of_scope.py` | ETF 0% 계획의 범위 밖 답에서 종목을 언급하면 재시도 없이 거부 |
| | `test_etf0_clarify.py` | ETF 0% 계획의 되묻기 규칙(시스템 프롬프트 문구)과 되묻는 답이 서버 검사를 통과하는지. 모델 행동은 수동 평가 몫 |
| **그 밖** | `test_units.py` | 청커, 운용사 섞기, 계산 클라이언트 요청 모양, 내부 키 검사, `/chat`이 주입된 에이전트로 camelCase 응답을 내는지 |
| | `scenarios/` | 실제 LLM으로 대화·RAG·누출 시나리오 점검(기본은 건너뜀) |
| | `fakes/` | 공용 대역(`FakeCalc`) |
| | `paths.py` | 새 테스트용 저장소 경로 상수(`REPO`, `COPY_FILE`) |

### 주제가 여러 파일에 걸친 것

- **세금 단서**(`tax.note`, #233부터 답에 붙이지 않고 지움): `tax_note/test_tax_note_removed.py`, `test_etf_tax*.py`, `test_etf_note_join.py`, `test_etf_plan_name_claims.py`, `test_explain.py`, `test_explain_review_fixes.py`, `test_plan_context.py`, `test_forbidden_retry.py`, 문구표 키는 `test_copy.py`
- **세금 범위 밖 문구**(`outOfScope.tax`): `test_fixed_copy_guard.py`, `test_explain_review_fixes.py`, `test_explain.py`, `scenarios/test_demo_scenarios.py`
- **재시도**: 본체는 `test_forbidden_retry.py`와 `test_retry_no_resend.py`. 금지 사유별 재시도·거부 동작은 `test_guardrails.py`(종목 추천), `test_internal_key_guard.py`·`test_internal_leak_guard.py`(내부 이름), `test_etf_name_correction.py`·`test_etf_plan_name_claims.py`(종목 표기)에도 있고, `test_etf_out_of_scope.py`는 재시도 없이 거부하는 경로다

## 알아 둘 것

- 일부 파일은 다른 테스트 파일의 헬퍼를 import한다(`test_out_of_scope` 8개 파일, `test_etf_tax` 8개, `test_plan_context` 5개, `test_etf_name_correction` 4개, `test_explain`·`test_forbidden_retry` 각 1개). 이 파일들을 옮기거나 이름을 바꾸면 가져가는 파일이 깨진다.
- 저장소 루트를 `Path(__file__).resolve().parents[N]`으로 찾는 파일이 많다. 폴더 깊이를 바꾸면 N이 달라진다.
- `scenarios/`는 `MOEUN_RUN_LLM=1`과 `ANTHROPIC_API_KEY`가 있을 때만 돈다. `test_rag_scenarios.py`는 추가로 OpenAI 키와 공시 4건이 적재된 `DATABASE_URL`이 필요하다. 사용법은 각 파일 맨 위에 있다.
