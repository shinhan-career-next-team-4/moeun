# 실서버 연결 확인 기록 (#55, A팀 화면 범위)

프론트를 목이 아닌 실제 Spring API에 붙여 **로그인 → 입력 → 결과 → 입력 수정 → 재로그인**을 손으로 돌린 기록이다.
대화(F-1~F-5) 흐름은 ai-server가 함께 떠야 해서 **#56(3서버 통합)에서 확인한다**.

- 확인 날짜: 2026-11-23
- 구성: PostgreSQL(docker compose) · Spring(`:8081`, 프로필 `local`) · Vite(`:5173`, `VITE_API_PROXY_TARGET=http://localhost:8081`)
- 계정: `demo02`

## 띄우는 법

```bash
docker compose up -d
cd backend && set -a && . ./.env && set +a && ./gradlew bootRun --args='--spring.profiles.active=local'
cd frontend && npm run dev          # 목을 쓰려면 VITE_API_MODE=mock
```

프론트는 **기본이 실서버 모드**다(`resolveApiMode`: `VITE_API_MODE=mock`일 때만 목).
브라우저는 `/api/...` 상대 경로만 부르고 Vite 프록시가 Spring으로 넘긴다 → 같은 사이트라 쿠키(`SameSite=Lax`)가 그대로 간다(C12b-2).

## 확인한 것

| 수용 기준 | 결과 |
|---|---|
| 로그인 → 입력 → 결과가 서버 계산값으로 보인다 | **통과**. `PUT /api/plan` 200 → `revision 1`, `calcRunId 1`, 3구간·그래프·가정 보기·면책까지 서버 `display` 문자열로 렌더 |
| 로그아웃 후 재로그인하면 같은 계획과 시작월이 보인다 | **통과**. 재로그인 뒤 같은 조건 바(목표 약 2,000만원 · 3년 0개월 · 시작월 2026년 11월)와 같은 결과 |
| 범위 밖 입력 → 서버 422가 칸별로 보인다 | **통과**. 목표 금액 0.5만원 → 422 `OUT_OF_RANGE` → "입력 항목 1개를 확인해 주세요." + 칸 아래 "1만원~1,000,000만원 사이로 입력해 주세요" |
| 폴링이 401 없이 동작(쿠키·같은 사이트) | **부분**. 로그인·저장·조회가 모두 쿠키로 통과. 폴링(F-2)은 #56 |
| 대화 F-1·F-2·F-3·F-4·F-5 | **미확인(#56 범위)** |

곁들여 확인한 것: 로그인 실패 401 → "아이디 또는 비밀번호가 맞지 않아요", 계획이 있는 채 `/`로 들어가면 결과 화면으로 보냄(FR-PLAN-06), 입력 수정 진입 시 저장값 채우기와 D-035 안내.

## 막혔던 것과 처리

### 1. ETF 월말 종가 데이터가 없어 모든 계산이 500

`etf_monthly` 0행, `data_version`에 `ETF_PRICE` 없음. 가격 CSV는 이용 조건(TBD-08) 때문에 레포에 두지 않고
`application.yml`에서 `optional: true`라 서버는 뜨지만, 비중이 `null`이면 기본 비중(5:5)이라 **모든 계획이 ETF를 포함**해
`CurrentPlanResultService`가 계산을 막는다 → `GET /api/plan` 500.

처리: `backend/data/seed/`에 **개발 전용 합성 CSV 3개**(SP500·069500·133690, 2016-10~2026-10 121개월)를 만들어 적재했다.
파일 첫 줄과 `backend/data/README.md`에 "실제 시세 아님"을 적었고 `backend/data/`는 `.gitignore`에 넣었다.
**데모 수치로 쓸 수 없다.** 실데이터는 각자 받아 같은 이름으로 덮어쓴다.

### 2. `demo01`의 계획이 옛 목표 코드라 조회 500

`plan_version` 1·2·3(2026-11-16~17 저장)의 `goal_type_code`가 `JEONSE`인데 지금 `GoalType`에 없는 코드라
`No enum constant …GoalType.JEONSE`로 500이 난다. 계약 C12a-5-1이 예고한 상황이며 **#58 초기화**에서 지울 데이터다.
이번 확인은 계획이 없던 `demo02`로 했다.

### 3. `demo03`은 로그인 401

`.env`의 비밀번호와 DB의 해시가 다르다. `DemoAccountSeeder`는 **없는 계정만 만들어서** 비밀번호를 바꿔도 기존 계정에 반영되지 않는다. **#58**에서 정리한다.

## 남은 것

- 대화 흐름(F-1~F-5) 실서버 확인 → #56
- 데모용 데이터·계정 정리(위 2·3) → #58
- 대안 영역·비교 영역은 실서버 모드에서 빈 카드와 안내만 나온다. 서버(#66·#64)가 생기면 채워진다
