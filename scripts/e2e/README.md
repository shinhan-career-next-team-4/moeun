# 로컬 E2E 시나리오 테스트

Spring(계산·계획·대화 API)과 ai-server(설명·대화)를 로컬에 띄운 상태에서, 와이어프레임의 사용자 흐름을
**실제 HTTP로** 따라가며 기능이 맞게 동작하는지 확인한다. 시나리오 목록과 와이어프레임 차이는 [SCENARIOS.md](SCENARIOS.md).

## 1. 띄우기

```bash
# DB (repo 루트)
docker compose up -d

# backend: backend/.env에 테스트 전용 계정을 추가한다 (예: DEMO_ACCOUNTS=e2e:<비밀번호>)
#          ETF 가격 파일(backend/data/seed/sp500-monthly.csv 또는 MOEUN_SP500_FILE)이 있어야 3구간 계산이 된다
cd backend && ./gradlew bootRun --args='--spring.profiles.active=local'

# ai-server (AI 시나리오용, .env에 ANTHROPIC_API_KEY·OPENAI_API_KEY)
cd ai-server && uv run uvicorn app.main:app --port 8000
# 출처 시나리오(S5-2, S9 J6)는 RAG 문서 적재가 필요하다: scripts/ingest
```

## 2. 돌리기 (repo 루트에서)

```bash
export E2E_LOGIN_ID=e2e E2E_PASSWORD=<비밀번호>

# 계산·계획·비교·대안만 (ai-server 없이, 수십 초)
uv run --no-project --with httpx --with pytest pytest scripts/e2e -m "not ai" -v

# 전체 (LLM 호출 포함, 수 분)
uv run --no-project --with httpx --with pytest pytest scripts/e2e -v

# 타깃 사용자 여정(발표용 7명)
uv run --no-project --with httpx --with pytest pytest scripts/e2e -k s9 -v

# 페르소나 시나리오만
uv run --no-project --with httpx --with pytest pytest scripts/e2e -k "s6 or s7" -v

# 대화 회귀만(S8)
uv run --no-project --with httpx --with pytest pytest scripts/e2e -k s8 -v

# 하나만
uv run --no-project --with httpx --with pytest pytest scripts/e2e -k s5_5 -v
```

| 환경 변수 | 기본값 | 뜻 |
|---|---|---|
| `E2E_BASE_URL` | `http://localhost:8080` | Spring |
| `E2E_AI_URL` | `http://localhost:8000` | ai-server(없으면 AI 시나리오는 skip) |
| `E2E_LOGIN_ID` / `E2E_PASSWORD` | 없음(필수) | `DEMO_ACCOUNTS`의 계정 |

**주의**: 테스트가 그 계정의 계획을 덮어쓰고 대화를 지운다(재로그인). 시연 계정과 다른 계정을 쓴다.

## 3. 무엇을 정답으로 삼나

- **와이어프레임 숫자는 정답으로 쓰지 않는다.** 예시 숫자라 서로 맞지 않는 곳이 있다(SCENARIOS.md 차이 G8).
- **적금 100%** 결과는 `oracle.py`가 `docs/calc-spec.md` 공식으로 따로 계산해 원 단위까지 비교한다.
- **ETF가 섞인 결과**(부트스트랩)는 값 대신 불변 조건을 본다: 원금 = 보유 + 월저축 × 기간, 자산 = 원금 + 세후 수익,
  달성 판정, 좋았던 때 ≥ 평균 ≥ 나빴던 때, 그래프 점 수·첫 점·마지막 점, 같은 계획이면 같은 숫자.
- **화면 문자열**(`display`)은 raw 숫자를 Formatter 규칙(약 N만원, 천 원 자리 반올림)으로 바꾼 것과 글자까지 같은지 본다.
- **AI 문장**은 비교하지 않는다. 메시지 종류, 도구 호출 값(F-4 `conditions`), 그리고 문장 속 'N만원'이
  전부 Spring `display`나 질문에 있는지(지어낸 숫자 없음)만 본다.

## 4. 파일

| 파일 | 내용 |
|---|---|
| `conftest.py` | 로그인(쿠키 직접 처리), 계획 저장, 대화 폴링 헬퍼 |
| `oracle.py` | 명세 기반 계산·표시 형식·불변 조건·AI 숫자 검사 |
| `test_01_auth.py` | S0 로그인·인증 |
| `test_02_plan.py` | S1 입력 → 결과 ① |
| `test_03_compare_alternatives.py` | S2 정확값, S3 조건 비교(③), S4 대안 카드(②) |
| `test_00_oracle.py` | 오라클 함수(허용 밖 문자, 운용사 수) 확인. 서버 불필요 |
| `test_04_chat_ai.py` | S5 AI 설명·대화 (`-m ai`) |
| `scenarios.py` | 페르소나 16개, 대화 질문 6종 |
| `test_10_personas.py` | S6 페르소나 x 목표 성질 (핵심) |
| `test_11_persona_chat.py` | S7 페르소나 x 말투 (`-m ai`) |
| `test_12_conversation.py` | S8 실제 대화 실패의 회귀: 금액 표현, 되묻기 뒤 금액만 답하기, 답 길이, 세금 안내 없음, 다른 ETF 출처 (`-m ai`, #234) |
| `journeys.py` | 타깃 사용자 7명의 여정 데이터 |
| `test_20_journeys.py` | S9 여정 실행 (입력·판정·대안 + 대화·적용·되돌리기) |
| `demo_scenarios_real.py` | 데모 대화 시나리오(`docs/demo-scenarios.md`) 5개를 진짜 Spring에 붙여 N회 실행(pytest 아님, #57) |
| `journeys.md` | 실행 후 생기는 여정 기록 (git 제외) |
| `report.md` | 실행 후 생기는 페르소나별 결과 표 (git 제외) |
