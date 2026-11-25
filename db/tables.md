# 테이블별 역할과 필드 저장 이유 (v2, 최소 저장)

기준: `../backend/src/main/resources/db/schema.sql` (테이블 14개).

> 이전 버전(v1, 테이블 25개)의 스키마·문서는 설계 워크스페이스 산출물로 이 저장소에 포함되지 않는다.
> 본문에서 v1을 언급하는 곳은 기록이며, 이 저장소에서 열 수 있는 파일이 아니다.

DB에는 코드로 다시 만들 수 없는 것만 둔다. 각 필드의 "저장 이유"는 아래 셋 중 하나다.

- **원본**: 사용자가 만든 데이터
- **외부**: 외부에서 가져온 시점 데이터
- **참조**: 다른 테이블이 가리키거나 재현에 필요한 최소 기록

삭제한 것과 그것이 옮겨 간 위치는 `final/moved-to-code.md`에 있다. 소유권은 앱이 모든 쿼리에 `member_id` 조건을 붙여 검증한다.

| 영역 | 테이블 |
|---|---|
| 사용자 | `member` |
| 내 계획 | `plan_version`, `plan` |
| 계산 기록 | `calc_run` |
| 기준 데이터 | `data_version`, `savings_avg_rate`, `etf_monthly`, `etf_product_version`, `calc_param` (`sp500_monthly`는 #138 전 구조, 보정용) |
| RAG 자료 | `source_org`, `rag_document`, `rag_chunk`, `chunk_embedding` |
| AI 대화 | `chat_message`, `chat_message_citation`, `chat_condition_change` |

---

## 1. `member` — 데모 계정 (FR-AUTH-01, NFR-SEC-01)
| 필드 | 저장 이유 |
|---|---|
| `id` | 참조: 계획·대화의 소유자 키 |
| `login_id` (UNIQUE) | 원본: 로그인 아이디. 미리 발급한 계정 |
| `password_hash` | 원본: 비밀번호의 해시. 평문은 저장하지 않는다(NFR-SEC-01) |

세션은 무상태 JWT로 처리하므로 세션 테이블이 없다.

## 2. `plan_version` — 내 계획의 조건 스냅샷 (FR-INP-*, FR-PLAN-*)
INSERT만 하는 이력이다. 적용하거나 입력을 수정할 때마다 새 행이 생긴다.
| 필드 | 저장 이유 |
|---|---|
| `id` | 참조: `plan`의 포인터와 `calc_run`이 가리킨다 |
| `member_id` | 원본: 소유자 |
| `goal_type_code` | 원본: 사용자가 고른 목표 종류(FR-INP-01). 값 목록은 enum `GoalType` |
| `goal_custom_name` | 원본: '직접 입력'한 목표명(FR-INP-02) |
| `goal_amount` | 원본: 목표 금액(FR-INP-03). CHECK > 0 |
| `target_period_months` | 원본: 목표 기간(필수, FR-INP-04) |
| `calc_start_month` | 원본: 계획을 세운 시점의 계산 시작월. 같은 조건이라도 시작월에 따라 결과와 목표일이 달라진다(FR-RES-04, FR-PLAN-05, TBD-38) |
| `held_assets` | 원본: 지금 이 목표에 쓸 수 있는 돈(FR-INP-05) |
| `monthly_saving_amount` | 원본: 월 저축 가능 금액(필수) |
| `savings_rate_input_pct` | 원본: 사용자가 넣은 적금 금리. NULL이면 기본금리(FR-INP-07) |
| `existing_monthly_contribution` | 항상 NULL(계약 C12a-5-1). 컬럼만 남아 있고 앱은 읽지도 쓰지도 않는다 |
| `savings_ratio_pct` | 원본: 사용자가 직접 정한 비중만 저장한다. 대안·비교·대화로 바꿔 적용한 경우다. NULL이면 `RatioResolver`가 규칙으로 산출 |
| `etf_product_code` | 원본: 계산에 쓸 ETF 종목 코드(#154). 사용자가 고른 값이라 다시 만들 수 없다. 새 계획과 칸이 없던 옛 계획은 대표 상품 `SP500`. 종목 이름·세율은 refdata `etf_product_version`에 있고 여기에는 코드만 둔다 |
| `source` | 원본: 이 버전을 만든 경로(INPUT/ALTERNATIVE/COMPARE/CHAT). 적용 출처 표시용. 되돌리기로 복사한 버전은 복사한 원본의 경로를 그대로 쓴다(D-068) |
| `created_at` | 원본: 버전이 생긴 시각 |

## 3. `plan` — 현재 계획 포인터 (사용자당 1행)
| 필드 | 저장 이유 |
|---|---|
| `member_id` (PK) | 참조: 사용자당 계획 1개. 행이 있으면 로그인 후 결과 화면으로 간다(FR-PLAN-06) |
| `current_version_id` | 원본: 사용자가 현재 채택한 버전. 로그아웃 후에도 남는다(FR-PLAN-05, FR-INP-11) |
| `previous_version_id` | 원본: 직전 1단계. 되돌리기 대상(FR-PLAN-04) |
| `baseline_scenario` | 원본: 사용자가 고른 기준 시나리오(FR-RES-09). 버전이 아니라 여기 둬서 보기 설정을 바꿔도 새 버전이 생기지 않는다 |
| `revision` | 참조: 낙관적 락. 적용·되돌리기를 `WHERE revision = $expected`로 처리해 중복 전송과 동시 수정을 막는다(FR-PLAN-03/04) |

## 4. `calc_run` — 계산 기록 (NFR-INT-01/02)
결과값은 저장하지 않는다. 무엇으로 계산했는지만 남겨 같은 결과를 다시 만들 수 있게 한다.
| 필드 | 저장 이유 |
|---|---|
| `id` | 참조: `chat_message`가 설명·재계산 대상으로 가리킨다 |
| `plan_version_id` | 참조: 계산에 쓴 조건 |
| `run_type` | 참조: PLAN(내 계획) / CHAT(대화 재계산). 비교 실행은 저장하지 않는다. CHAT은 재계산이 성공할 때마다 1행 남고, 메시지가 참조하지 않는 행도 정리하지 않는다(D-057) |
| `savings_rate_version_id` | 참조: 기본금리를 썼다면 그 버전(NFR-INT-02). 기준 데이터는 시간이 지나면 바뀌어 다시 알아낼 수 없다 |
| `sp500_version_id` | 참조: ETF가 포함된 경우 쓴 ETF 가격 버전(종목은 그 버전의 `product_code`. 이름은 #138 전 그대로). CHAT은 ETF를 쓴 쪽 종목(둘 다 썼으면 current 쪽) |
| `changed_etf_version_id` | 참조: CHAT만. 대화 비교의 changed가 current와 다른 ETF 종목일 때 그 가격 버전(#157). 같은 종목이면 NULL. 이 행만으로 두 결과를 다시 계산하려면 필요하다 |
| `calc_param_version_id` | 참조: 세율·총보수·기본 비중·대안 단위 버전 |
| `engine_version` | 참조: 계산 엔진 버전. 로직을 바꾸면 올려서 재현 범위를 구분한다 |
| `created_at` | 참조: 같은 버전에 대한 최신 기록을 고른다(FR-PLAN-05). 재로그인 시 재계산하지 않는다(D-010) |

## 5. `data_version` — 기준 데이터 버전 (FR-DATA-*, NFR-INT-02)
| 필드 | 저장 이유 |
|---|---|
| `id` | 참조: `calc_run`과 각 데이터 테이블이 가리킨다 |
| `data_kind` | 외부: SAVINGS_RATE / ETF_PRICE / CALC_PARAM (#138 전 SP500은 ETF_PRICE + 종목 SP500으로 보정) |
| `product_code` | 외부: ETF_PRICE만 종목 코드(SP500, 069500 등). 그 밖은 NULL(CHECK) |
| `version_label`, `base_date`, `source` | 외부: '가정 보기'에 보여주는 기준일·출처(FR-RES-06) |
| `is_active` | 참조: 종류별(ETF는 종목별)로 지금 쓸 버전 1개(부분 UNIQUE). 잘못 적재했을 때 이전 버전으로 되돌리는 스위치 |
| `loaded_at` | 외부: 적재 시점 |

## 6. `savings_avg_rate` — 은행연합회 전월 평균금리 (FR-DATA-01)
| 필드 | 저장 이유 |
|---|---|
| `data_version_id` (PK) | 참조: 버전당 1행 |
| `base_month` | 외부: 기준월. 매월 1일만 허용(CHECK) |
| `avg_rate_pct` | 외부: 평균금리. 금리를 비워 둔 입력의 기본값(FR-INP-07) |

## 7. `etf_monthly` — ETF 종목별 월말 종가 (FR-DATA-02, #138)
| 필드 | 저장 이유 |
|---|---|
| `(data_version_id, ym)` | 참조: 버전별로 월 1행. 어느 종목인지는 버전의 `product_code`가 정한다 |
| `close_value` | 외부: 월말 종가(분배금 제외). 3구간과 월별 범위 계산에 쓴다. S&P500도 종목 하나(SP500)다 |

## 7-1. `etf_product_version` — 가격 버전의 종목 정보 (#138)
| 필드 | 저장 이유 |
|---|---|
| `data_version_id` (PK) | 참조: 가격 버전당 1행 |
| `product_code` | 외부: 종목 코드 |
| `display_name` | 외부: 화면 이름. 상품명 대신 추종 지수로 쓴다(CP-06) |
| `gain_tax_rate_pct` | 외부: 매매차익 세율. 국내 주식형 ETF는 0. 데이터 버전에 묶어 두어 설정이 바뀌어도 옛 계산을 재현한다(NFR-INT-02) |

`sp500_monthly`는 #138 전 구조다. 앱은 읽지 않고, `schema.sql` 끝의 보정 구간이 기존 로컬 DB의 값을 `etf_monthly`로 옮길 때만 쓴다.

## 8. `calc_param` — 계산 파라미터 값 (CP-03)
| 필드 | 저장 이유 |
|---|---|
| `(data_version_id, param_key)` | 참조: 버전별 항목. 항목 목록은 enum `CalcParamKey` |
| `param_value` | 외부: 팀이 정한 시점 값(세율, 총보수, 기본 비중, 대안 변경 단위). 바뀌면 새 버전으로 넣어 과거 계산을 재현한다 |

## 9. `source_org` — 자료 발행 기관
| 필드 | 저장 이유 |
|---|---|
| `id`, `name` (UNIQUE) | 외부: 출처 표시 |
| `org_type` | 외부: ASSET_MANAGER 등. "서로 다른 운용사 2곳 이상"을 확인하는 기준(FR-AI-03) |

## 10. `rag_document` — 근거 자료 문서 (FR-AI-10)
이용 허락된 문서만 적재 스크립트가 넣는다.
| 필드 | 저장 이유 |
|---|---|
| `doc_type` | 외부: EDU(교육자료) / DISCLOSURE(운용사 공시) |
| `source_org_id` | 외부: 발행 기관 |
| `title`, `source_url`, `published_at`, `etf_code` | 외부: 출처 표시(FR-AI-03)와 ETF 연결 |
| `content_sha256` | 외부: 같은 URL의 개정본을 구분한다. `(source_url, content_sha256)` UNIQUE |
| `is_active` | 참조: 문서를 검색에서 뺄 때 쓴다. 과거 대화가 이 문서를 인용하고 있으므로 행은 지우지 않는다 |
| `ingested_at` | 외부: 적재 시점 |

## 11. `rag_chunk` — 본문 청크
| 필드 | 저장 이유 |
|---|---|
| `id` | 참조: 인용(`chat_message_citation`)과 임베딩이 가리킨다 |
| `document_id`, `chunk_no` | 참조: 문서 안에서 청크를 구분하는 번호. `(document_id, chunk_no)` UNIQUE. 한 번의 적재 안에서는 본문 순서다. 청킹 규칙을 바꿔 다시 넣으면(재청킹) 이전 청크 행을 남긴 채 번호를 이어 붙이므로, 문서 전체로는 위치가 아니다. 현재 청크는 임베딩이 활성인 것이다 |
| `content` | 외부: 인용 원문. 임베딩 모델 수와 무관하게 1회 저장 |

## 12. `chunk_embedding` — 모델별 임베딩 (NFR-INT-04)
| 필드 | 저장 이유 |
|---|---|
| `(chunk_id, model_code)` | 참조: 청크 1개에 모델별 1행. 모델을 바꿔도 본문은 다시 저장하지 않는다 |
| `embedding` | 외부: 임베딩 API 결과. 다시 만들려면 비용이 들고 모델이 바뀌면 같은 값이 나오지 않는다 |
| `is_active` | 참조: 부분 HNSW 인덱스(`WHERE is_active`)의 조건으로 쓰는 유일한 반정규화. 인덱스 조건은 같은 테이블의 컬럼만 쓸 수 있다. 비활성 문서가 인덱스 상위 결과를 차지하지 않게 한다. 문서를 비활성화할 때 앱이 같은 트랜잭션에서 함께 끈다. 재청킹으로 교체된 이전 청크의 임베딩도 꺼 두므로, 활성 문서 아래에 비활성 임베딩이 있는 것은 정상이다. 반대(비활성 문서 아래 활성 임베딩)는 없어야 한다 |

## 13. `chat_message` — 대화 메시지 (영속, FR-AI-01~09)
AI 출력은 다시 생성하면 같은 문장이 나오지 않으므로 원본으로 저장한다.
| 필드 | 저장 이유 |
|---|---|
| `member_id` | 원본: 소유자. 계획이 없어도 대화할 수 있다(NFR-USA-01) |
| `role`, `message_type` | 원본: 사용자/AI, 메시지 종류(질문·설명·재계산 결과·되묻기·범위 밖·실패). 대화 복원 시 표시 방식이 달라진다 |
| `status` | 원본: PENDING → COMPLETED/FAILED. 계산 결과를 먼저 보여주고 설명은 뒤이어 도착한다(FR-AI-01). 실패 안내를 복원한다(FR-AI-04) |
| `content` | 원본: 메시지 본문. 처리 중에만 NULL |
| `calc_run_id` | 참조: 설명·재계산이 어떤 계산 기록에 대한 것인지(FR-AI-02/07) |
| `baseline_scenario` | 원본: 설명 당시의 기준 시나리오. `plan.baseline_scenario`는 나중에 바뀔 수 있다 |
| `condition_changes` | 원본: 사용자가 말로 바꾼 조건. RECALC_RESULT에서는 재계산에 쓴 값이며 이 값이 없으면 결과를 다시 만들 수 없다(FR-AI-06/07) |
| `baseline_condition_changes` | 원본: RECALC_RESULT의 비교 기준. 그때까지 대화에서 쌓인 조건의 최종 값이다(#418, D-103). 이 값이 없으면 "직전 누적 계획 대비" 비교(F-4)를 다시 만들 수 없다. 쌓인 조건 없이 계산했으면 NULL |
| `retry_of_message_id` | 원본: '설명 다시 요청'의 원본 메시지(FR-AI-05) |
| `created_at` | 원본: 대화 순서와 시각 |

## 13-1. `chat_condition_change` — 대화 조건 누적의 변경 목록 (#418, D-103)
대화에서 바꾼 조건은 적용하지 않아도 다음 질문의 비교 기준이 된다. 최종 값이 아니라 **변경 목록**(조건·이전 값·바꾼 값·순서)으로 남긴다. 내 계획으로 적용하거나(F-5) 대화 세션이 끝나면(F-6 대화창 닫기, 로그인·로그아웃의 대화 삭제) 지운다. 대화로 되돌리면(#419) 그 범위(직전 질문의 변경·한 조건·전부)만 지운다.

| 필드 | 저장 이유 |
|---|---|
| `id` | 원본: 바꾼 순서. 같은 조건을 두 번 바꾸면 두 행이 순서대로 남는다 |
| `member_id` | 소유: 회원별 목록. 비우기의 기준 |
| `message_id` | 참조: 그 변경을 계산한 RECALC_RESULT. 대화가 지워지면 함께 지워진다(CASCADE) |
| `plan_version_id` | 원본: 그 질문을 접수한 계획 버전. 계획이 바뀌면(입력 수정·적용·되돌리기) 이전 버전의 목록은 읽지 않는다 |
| `field` | 원본: 바꾼 조건(camelCase 키) |
| `before_value` | 원본: 바꾸기 전 값(쌓인 값, 없으면 그때 계획 값). 계획에 값이 없었으면 NULL |
| `after_value` | 원본: 사용자가 말해 계산한 값 |
| `created_at` | 원본: 바꾼 시각 |

## 14. `chat_message_citation` — 설명 근거 출처 (FR-AI-03)
| 필드 | 저장 이유 |
|---|---|
| `(message_id, rank)` | 원본: 그 설명이 실제로 인용한 순서. 검색을 다시 하면 결과가 달라질 수 있어 저장한다 |
| `chunk_id` | 참조: 인용한 청크. 임베딩 모델과 무관하다 |
