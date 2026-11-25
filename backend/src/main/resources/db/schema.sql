-- =====================================================================
-- final/schema.sql — 최소 저장 원칙 v2 (이전 버전: final/v1/schema.sql)
-- 대상: PostgreSQL 16 + pgvector. 빈 DB에서 이 파일 전체를 순서대로 실행한다.
--
-- 저장 대상: (1) 사용자가 만든 원본 데이터 (2) 외부에서 가져온 시점 데이터
--            (3) 다른 테이블이 참조해야 하는 최소 기록
-- 무결성: PK / FK / NOT NULL / UNIQUE / 단순 CHECK 만 사용한다.
--         트리거·생성 컬럼·복사값 일치용 복합 FK는 쓰지 않는다.
--         소유권은 앱이 모든 쿼리에 member_id 조건을 붙여 검증한다.
-- 코드로 옮긴 규칙·코드표·결과: final/moved-to-code.md
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- =========================================================
-- 사용자 (FR-AUTH-01, NFR-SEC-01). 인증은 무상태 JWT라 세션 테이블이 없다.
-- =========================================================
CREATE TABLE IF NOT EXISTS member (
    id             BIGSERIAL    PRIMARY KEY,
    login_id       VARCHAR(50)  NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,                 -- bcrypt/argon2 결과
    CONSTRAINT uq_member_login_id UNIQUE (login_id)
);

-- =========================================================
-- 기준 데이터와 버전 (FR-DATA-*, CP-03, NFR-INT-02) — 외부 시점 데이터
-- =========================================================
CREATE TABLE IF NOT EXISTS data_version (
    id             BIGSERIAL    PRIMARY KEY,
    data_kind      VARCHAR(20)  NOT NULL,
    product_code   VARCHAR(20),                           -- ETF_PRICE만: 종목 코드(SP500, 069500 등). 그 밖은 NULL
    version_label  VARCHAR(50)  NOT NULL,
    base_date      DATE         NOT NULL,
    source         VARCHAR(100) NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT FALSE,
    loaded_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 아래 세 제약을 바꾸면 파일 끝 보정 구간의 같은 제약도 함께 고친다(보정 구간이 기동마다 다시 만든다)
    CONSTRAINT ck_data_kind CHECK (data_kind IN ('SAVINGS_RATE','ETF_PRICE','CALC_PARAM')),
    CONSTRAINT ck_data_version_product CHECK ((data_kind = 'ETF_PRICE') = (product_code IS NOT NULL)),
    CONSTRAINT uq_data_version_label UNIQUE NULLS NOT DISTINCT (data_kind, product_code, version_label)
);
-- 종류별(ETF는 종목별) 활성 버전 1개 인덱스 uq_data_version_active_product는 파일 끝 보정 구간에서 만든다.
-- 옛 DB에는 product_code 칸이 아직 없어서, 칸을 더한 뒤에 만들어야 한다

CREATE TABLE IF NOT EXISTS savings_avg_rate (
    data_version_id  BIGINT       PRIMARY KEY REFERENCES data_version(id),
    base_month       DATE         NOT NULL,
    avg_rate_pct     NUMERIC(6,3) NOT NULL,
    CONSTRAINT ck_sar_rate        CHECK (avg_rate_pct >= 0),
    CONSTRAINT ck_sar_month_first CHECK (EXTRACT(DAY FROM base_month) = 1)
);

-- ETF 종목별 월말 종가(FR-DATA-02). S&P500도 종목 하나(SP500)다. 분배금은 반영하지 않는다(팀 결정)
CREATE TABLE IF NOT EXISTS etf_monthly (
    data_version_id  BIGINT        NOT NULL REFERENCES data_version(id),
    ym               DATE          NOT NULL,
    close_value      NUMERIC(14,4) NOT NULL,
    PRIMARY KEY (data_version_id, ym),
    CONSTRAINT ck_etf_close CHECK (close_value > 0)
);

-- 그 가격 버전의 종목 정보. 세율을 데이터 버전에 묶어 두어 나중에 바뀌어도 옛 계산을 그대로 다시 만든다(NFR-INT-02)
CREATE TABLE IF NOT EXISTS etf_product_version (
    data_version_id    BIGINT        PRIMARY KEY REFERENCES data_version(id),
    product_code       VARCHAR(20)   NOT NULL,
    display_name       VARCHAR(50)   NOT NULL,             -- 화면 이름. 상품명 대신 "코스피200 추종 ETF"(CP-06)
    gain_tax_rate_pct  NUMERIC(6,3)  NOT NULL,             -- 매매차익 세율(%). 국내 주식형은 0
    CONSTRAINT ck_epv_tax CHECK (gain_tax_rate_pct >= 0 AND gain_tax_rate_pct <= 100)
);

-- 옛 구조(#138 전). 읽지 않는다. 기존 로컬 DB를 보정할 때 원본으로만 쓰므로 새 DB에도 빈 채로 만든다
CREATE TABLE IF NOT EXISTS sp500_monthly (
    data_version_id     BIGINT        NOT NULL REFERENCES data_version(id),
    ym                  DATE          NOT NULL,
    close_value         NUMERIC(14,4) NOT NULL,
    total_return_index  NUMERIC(18,6),
    PRIMARY KEY (data_version_id, ym),
    CONSTRAINT ck_sp500_close CHECK (close_value > 0)
);

CREATE TABLE IF NOT EXISTS calc_param (
    data_version_id  BIGINT        NOT NULL REFERENCES data_version(id),
    param_key        VARCHAR(50)   NOT NULL,              -- 앱 enum CalcParamKey
    param_value      NUMERIC(14,6) NOT NULL,
    PRIMARY KEY (data_version_id, param_key)
);

-- =========================================================
-- 내 계획 (FR-INP-*, FR-PLAN-*) — 사용자 원본 데이터
-- =========================================================
CREATE TABLE IF NOT EXISTS plan_version (
    id                             BIGSERIAL    PRIMARY KEY,
    member_id                      BIGINT       NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    goal_type_code                 VARCHAR(30)  NOT NULL,   -- 앱 enum GoalType
    goal_custom_name               VARCHAR(100),             -- GoalType.CUSTOM일 때만 (앱 검증)
    goal_amount                    BIGINT       NOT NULL,
    target_period_months           INT,                     -- 필수(앱 검증)
    calc_start_month               DATE         NOT NULL,
    held_assets                    BIGINT       NOT NULL DEFAULT 0,
    monthly_saving_amount          BIGINT,                  -- 필수(앱 검증)
    savings_rate_input_pct         NUMERIC(6,3),            -- NULL = 기본금리
    existing_monthly_contribution  BIGINT,                  -- 항상 NULL(계약 C12a-5-1). 컬럼만 남겨 둔다
    savings_ratio_pct              SMALLINT,                -- NULL = 규칙으로 산출(토글/기본값). 사용자가 직접 정한 비중만 저장
    etf_product_code               VARCHAR(20)  NOT NULL DEFAULT 'SP500',   -- ETF 종목 코드(#154). 새 계획은 대표 상품
    source                         VARCHAR(20)  NOT NULL,   -- 이 버전을 만든 경로. 되돌리기로 복사한 버전은 복사한 원본의 경로
    created_at                     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_pv_goal_amount CHECK (goal_amount > 0),
    CONSTRAINT ck_pv_period      CHECK (target_period_months IS NULL OR target_period_months > 0),
    CONSTRAINT ck_pv_amounts     CHECK (held_assets >= 0
                                        AND (monthly_saving_amount IS NULL OR monthly_saving_amount >= 0)
                                        AND (existing_monthly_contribution IS NULL
                                             OR existing_monthly_contribution >= 0)),
    CONSTRAINT ck_pv_rate        CHECK (savings_rate_input_pct IS NULL OR savings_rate_input_pct >= 0),
    CONSTRAINT ck_pv_ratio       CHECK (savings_ratio_pct IS NULL OR savings_ratio_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_pv_source      CHECK (source IN ('INPUT','ALTERNATIVE','COMPARE','CHAT'))
);
CREATE INDEX IF NOT EXISTS ix_plan_version_member ON plan_version (member_id);

CREATE TABLE IF NOT EXISTS plan (
    member_id            BIGINT      PRIMARY KEY REFERENCES member(id) ON DELETE CASCADE,
    current_version_id   BIGINT      NOT NULL REFERENCES plan_version(id),
    previous_version_id  BIGINT      REFERENCES plan_version(id),   -- 되돌리기 대상(직전 1단계)
    baseline_scenario    VARCHAR(10) NOT NULL,
    revision             BIGINT      NOT NULL DEFAULT 1,            -- 낙관적 락: UPDATE ... WHERE revision = $expected
    CONSTRAINT ck_plan_baseline  CHECK (baseline_scenario IN ('GOOD','AVG','BAD')),
    CONSTRAINT ck_plan_prev_diff CHECK (previous_version_id IS NULL OR previous_version_id <> current_version_id),
    CONSTRAINT ck_plan_revision  CHECK (revision >= 1)
);

-- =========================================================
-- 계산 기록 (NFR-INT-01/02) — 결과는 저장하지 않고 "무엇으로 계산했는지"만 남긴다
-- PLAN: 내 계획 계산. CHAT: chat_message가 참조하는 대화 재계산. COMPARE는 저장하지 않는다.
-- =========================================================
CREATE TABLE IF NOT EXISTS calc_run (
    id                       BIGSERIAL    PRIMARY KEY,
    plan_version_id          BIGINT       NOT NULL REFERENCES plan_version(id) ON DELETE CASCADE,
    run_type                 VARCHAR(10)  NOT NULL,
    savings_rate_version_id  BIGINT       REFERENCES data_version(id),   -- NULL = 기본금리를 쓰지 않음
    sp500_version_id         BIGINT       REFERENCES data_version(id),   -- ETF 가격 버전(이름은 #138 전 그대로). NULL = ETF 비중 0%
    changed_etf_version_id   BIGINT       REFERENCES data_version(id),   -- CHAT만: changed가 다른 종목일 때 그 가격 버전(#157). NULL = 같은 종목
    calc_param_version_id    BIGINT       NOT NULL REFERENCES data_version(id),
    engine_version           VARCHAR(20)  NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_cr_run_type CHECK (run_type IN ('PLAN','CHAT'))
);
CREATE INDEX IF NOT EXISTS ix_calc_run_latest ON calc_run (plan_version_id, run_type, created_at DESC);

-- =========================================================
-- RAG 자료 (FR-AI-03, FR-AI-10, NFR-INT-04) — 외부 시점 데이터. 이용 허락된 문서만 적재한다
-- =========================================================
CREATE TABLE IF NOT EXISTS source_org (
    id        BIGSERIAL    PRIMARY KEY,
    name      VARCHAR(100) NOT NULL,
    org_type  VARCHAR(20)  NOT NULL,
    CONSTRAINT uq_source_org_name UNIQUE (name),
    CONSTRAINT ck_source_org_type CHECK (org_type IN ('ASSET_MANAGER','EDU_PROVIDER','REGULATOR'))
);

CREATE TABLE IF NOT EXISTS rag_document (
    id              BIGSERIAL     PRIMARY KEY,
    doc_type        VARCHAR(20)   NOT NULL,
    source_org_id   BIGINT        NOT NULL REFERENCES source_org(id),
    title           VARCHAR(300)  NOT NULL,
    source_url      VARCHAR(1000) NOT NULL,
    content_sha256  CHAR(64)      NOT NULL,              -- 같은 URL의 개정본은 새 행
    etf_code        VARCHAR(20),
    published_at    DATE,
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE, -- 검색 제외용. 과거 인용 보존을 위해 행은 지우지 않는다
    ingested_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_rag_document_rev UNIQUE (source_url, content_sha256),
    CONSTRAINT ck_rag_doc_type CHECK (doc_type IN ('EDU','DISCLOSURE'))
);

CREATE TABLE IF NOT EXISTS rag_chunk (
    id           BIGSERIAL PRIMARY KEY,
    document_id  BIGINT    NOT NULL REFERENCES rag_document(id) ON DELETE CASCADE,
    chunk_no     INT       NOT NULL,
    content      TEXT      NOT NULL,
    CONSTRAINT uq_rag_chunk_doc_no UNIQUE (document_id, chunk_no)
);

CREATE TABLE IF NOT EXISTS chunk_embedding (
    chunk_id    BIGINT       NOT NULL REFERENCES rag_chunk(id) ON DELETE CASCADE,
    model_code  VARCHAR(50)  NOT NULL,                   -- 임베딩 모델 식별 문자열
    embedding   vector(1536) NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,      -- 부분 HNSW 조건. rag_document.is_active와 같은 트랜잭션에서 앱이 함께 갱신
    PRIMARY KEY (chunk_id, model_code)
);
CREATE INDEX IF NOT EXISTS ix_chunk_embedding_hnsw ON chunk_embedding
    USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64)
    WHERE is_active;

-- =========================================================
-- AI 대화 (FR-AI-01~09) — 영속 저장. AI 출력은 다시 만들 수 없으므로 원본으로 취급한다
-- =========================================================
CREATE TABLE IF NOT EXISTS chat_message (
    id                   BIGSERIAL    PRIMARY KEY,
    member_id            BIGINT       NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    role                 VARCHAR(10)  NOT NULL,
    message_type         VARCHAR(20)  NOT NULL,
    status               VARCHAR(10)  NOT NULL DEFAULT 'COMPLETED',
    content              TEXT,
    calc_run_id          BIGINT       REFERENCES calc_run(id),
    baseline_scenario    VARCHAR(10),                    -- 설명·재계산 당시 기준 시나리오
    condition_changes    JSONB,                          -- 인식한 변경 의도. RECALC_RESULT에서는 재계산에 쓴 바꾼 값
    baseline_condition_changes JSONB,                    -- RECALC_RESULT의 비교 기준: 그때까지 쌓인 조건의 최종 값(#418, D-103). 없으면 NULL
    retry_of_message_id  BIGINT       REFERENCES chat_message(id),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_cm_role      CHECK (role IN ('USER','ASSISTANT')),
    CONSTRAINT ck_cm_type      CHECK (message_type IN ('QUESTION','ANSWER','EXPLANATION','RECALC_RESULT','CLARIFY','OUT_OF_SCOPE','FAILURE')),
    CONSTRAINT ck_cm_role_type CHECK ((role = 'USER') = (message_type = 'QUESTION')),
    CONSTRAINT ck_cm_status    CHECK (status IN ('PENDING','COMPLETED','FAILED')),
    CONSTRAINT ck_cm_content   CHECK (status = 'PENDING' OR content IS NOT NULL),
    CONSTRAINT ck_cm_baseline  CHECK (baseline_scenario IS NULL OR baseline_scenario IN ('GOOD','AVG','BAD')),
    CONSTRAINT ck_cm_changes   CHECK (condition_changes IS NULL OR jsonb_typeof(condition_changes) = 'object'),
    CONSTRAINT ck_cm_baseline_changes CHECK (baseline_condition_changes IS NULL
                                      OR jsonb_typeof(baseline_condition_changes) = 'object'),
    CONSTRAINT ck_cm_recalc    CHECK (message_type <> 'RECALC_RESULT'
                                      OR (calc_run_id IS NOT NULL AND condition_changes IS NOT NULL AND baseline_scenario IS NOT NULL)),
    CONSTRAINT ck_cm_explain   CHECK (message_type <> 'EXPLANATION' OR (calc_run_id IS NOT NULL AND baseline_scenario IS NOT NULL)),
    CONSTRAINT ck_cm_out_of_scope CHECK (message_type <> 'OUT_OF_SCOPE' OR calc_run_id IS NULL)
);
CREATE INDEX IF NOT EXISTS ix_chat_message_member ON chat_message (member_id, id);                       -- 대화 복원·폴링 키셋
CREATE INDEX IF NOT EXISTS ix_chat_message_pending ON chat_message (created_at) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS ix_chat_message_run ON chat_message (calc_run_id) WHERE calc_run_id IS NOT NULL;

-- 대화 조건 누적의 변경 목록(#418, D-103). 대화에서 바꾼 조건을 바꾼 순서대로 남긴다. 최종 값이 아니라 목록이다.
-- 그 질문을 접수한 계획 버전의 것만 쓴다(계획이 바뀌면 이전 목록은 쓰지 않는다). 적용·대화창 닫기·로그인·로그아웃 때 지운다.
CREATE TABLE IF NOT EXISTS chat_condition_change (
    id               BIGSERIAL    PRIMARY KEY,
    member_id        BIGINT       NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    message_id       BIGINT       NOT NULL REFERENCES chat_message(id) ON DELETE CASCADE,   -- 그 변경을 계산한 RECALC_RESULT
    plan_version_id  BIGINT       NOT NULL,             -- 그 질문을 접수한 계획 버전(다른 모듈 행은 ID로만 참조)
    field            VARCHAR(30)  NOT NULL,             -- camelCase 조건 키(/internal/calc conditionChanges와 같다)
    before_value     JSONB,                             -- 바꾸기 전 값. 계획에 값이 없었으면 NULL
    after_value      JSONB        NOT NULL,             -- 바꾼 값
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_chat_condition_change_member ON chat_condition_change (member_id, plan_version_id, id);

CREATE TABLE IF NOT EXISTS chat_message_citation (
    message_id  BIGINT   NOT NULL REFERENCES chat_message(id) ON DELETE CASCADE,
    rank        SMALLINT NOT NULL,
    chunk_id    BIGINT   NOT NULL REFERENCES rag_chunk(id),
    PRIMARY KEY (message_id, rank),
    CONSTRAINT uq_citation_chunk UNIQUE (message_id, chunk_id)
);

-- =========================================================
-- 기존 로컬 DB 보정(#138, ETF 종목 확장). 새 DB에서는 아무것도 바꾸지 않는다.
-- 마이그레이션 도구가 없어 서버가 뜰 때마다 이 파일을 실행하므로 몇 번을 실행해도 결과가 같게 쓴다.
-- 옛 S&P500 버전(data_kind SP500)을 ETF 종목 SP500으로 바꾸고 가격을 etf_monthly로 옮긴다.
-- data_version id는 그대로라 calc_run에 남은 옛 기록도 그대로 다시 계산된다.
-- =========================================================
-- #418: 대화 조건 누적의 비교 기준. 기존 대화 행은 NULL(기준 계획과 비교)이다
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS baseline_condition_changes JSONB;
ALTER TABLE data_version ADD COLUMN IF NOT EXISTS product_code VARCHAR(20);
DROP INDEX IF EXISTS uq_data_version_active;
ALTER TABLE data_version DROP CONSTRAINT IF EXISTS ck_data_kind;
ALTER TABLE data_version DROP CONSTRAINT IF EXISTS ck_data_version_product;
ALTER TABLE data_version DROP CONSTRAINT IF EXISTS uq_data_version_label;
UPDATE data_version SET data_kind = 'ETF_PRICE', product_code = 'SP500' WHERE data_kind = 'SP500';
ALTER TABLE data_version ADD CONSTRAINT ck_data_kind CHECK (data_kind IN ('SAVINGS_RATE','ETF_PRICE','CALC_PARAM'));
ALTER TABLE data_version ADD CONSTRAINT ck_data_version_product
    CHECK ((data_kind = 'ETF_PRICE') = (product_code IS NOT NULL));
ALTER TABLE data_version ADD CONSTRAINT uq_data_version_label
    UNIQUE NULLS NOT DISTINCT (data_kind, product_code, version_label);
-- 종류별(ETF는 종목별) 활성 버전 1개
CREATE UNIQUE INDEX IF NOT EXISTS uq_data_version_active_product ON data_version (data_kind, product_code)
    NULLS NOT DISTINCT WHERE is_active;
-- #157: 대화 비교의 changed 쪽이 다른 ETF 종목일 때 그 가격 버전
ALTER TABLE calc_run ADD COLUMN IF NOT EXISTS changed_etf_version_id BIGINT REFERENCES data_version(id);
INSERT INTO etf_monthly (data_version_id, ym, close_value)
    SELECT data_version_id, ym, close_value FROM sp500_monthly
    ON CONFLICT (data_version_id, ym) DO NOTHING;
INSERT INTO etf_product_version (data_version_id, product_code, display_name, gain_tax_rate_pct)
    SELECT id, 'SP500', 'S&P500 추종 ETF', 15.4 FROM data_version
    WHERE data_kind = 'ETF_PRICE' AND product_code = 'SP500'
    ON CONFLICT (data_version_id) DO NOTHING;
-- #154: 계획의 ETF 종목. 종목 칸이 없던 옛 계획은 대표 상품(SP500)으로 채운다
ALTER TABLE plan_version ADD COLUMN IF NOT EXISTS etf_product_code VARCHAR(20) NOT NULL DEFAULT 'SP500';
