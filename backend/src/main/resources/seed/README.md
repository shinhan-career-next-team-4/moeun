# 기준 데이터 시드

서버가 뜰 때 `application.yml`의 `moeun.refdata.seeds`에 적힌 CSV를 기준 데이터 버전으로 적재한다(같은 버전 이름은 다시 넣지 않는다). 형식 검증과 적재 규칙은 `refdata` 모듈과 `docs/calc-spec.md`를 따른다.

| 데이터 | 파일 | 레포에 있나 | 출처 | 기준일 |
|---|---|---|---|---|
| 적금 평균금리 | `seed/savings-avg-rate.csv` | 있음 | 은행연합회 12개월 정기적금 은행별 단순평균(D-012, D-034). **지금은 임시값 3.12%** | 2026-10 |
| 계산 파라미터 | `seed/calc-param.csv` | 있음 | 팀 결정(calc-spec 9절, 임시값 포함) | - |
| S&P500 월말 종가 | `backend/data/seed/sp500-monthly.csv` | **없음(각자 준비)** | Investing.com S&P500 월별, 달러 지수(D-027) | 2026-10-31 |

## S&P500 데이터 준비 (팀원마다 한 번)

S&P500 지수 데이터는 S&P Dow Jones Indices의 권리가 있어 공개 레포에 올리지 않는다(TBD-08, 팀 결정). 각자 받아서 로컬에만 둔다. 파일이 없으면 서버는 경고 로그(`refdata.missing`)만 남기고 S&P500 없이 뜬다. 이때는 ETF가 들어간 계산을 할 수 없다.

1. 원본 받기: Investing.com(무료 회원 로그인 필요)의 **S&P 500 과거 데이터** 화면에서 시간 단위를 **월간**으로, 기간을 **2016-10-01 ~ 2026-10-31**(또는 그 이후)로 정하고 다운로드한다. 파일 이름은 `S&P 500 과거 데이터.csv`이고 머리줄은 `"날짜","종가",...`다.
   - 한 번에 받을 수 있는 줄 수에 한계가 있는 것 같다. 받은 파일에 `2016- 10- 01` 줄이 있는지 확인한다.
   - 2016-10 종가가 2,126.20, 2026-10 종가가 7,580.07이면 맞다(2026-10은 FRED와 같은 값).
2. 변환하기(레포 루트에서):
   ```bash
   python3 backend/scripts/refdata/convert_sp500.py --input "$HOME/Downloads/S&P 500 과거 데이터.csv"
   ```
   - 2016-10 ~ 2026-10(121개월)의 월말 종가를 `backend/data/seed/sp500-monthly.csv`로 저장한다.
   - 팀 모두 같은 기간을 써야 같은 데이터 버전이 된다. 기간을 바꾸려면 `--end-month`와 `application.yml`의 `version-label`·`base-date`를 함께 바꾼다.
   - 빠진 달이 있거나 아직 끝나지 않은 달을 고르면 변환이 실패한다. 파일에 진행 중인 달(예: 2026-11)이 있어도 `--end-month`까지만 남긴다.
3. 서버를 띄운다. 로그에 `refdata.loaded kind=SP500`이 보이면 된다.

- 파일 위치를 바꾸려면 환경 변수 `MOEUN_SP500_FILE`(예: `file:/Users/me/sp500-monthly.csv`)을 쓴다. IDE에서 실행해 작업 폴더가 `backend/`가 아니면 이 값을 절대 경로로 준다.
- `backend/data/`는 `.gitignore`에 있다. 이 폴더의 파일을 커밋하지 않는다.
- Stooq는 S&P500 지수(`^SPX`)를 더 이상 제공하지 않는다(2026-11 확인). FRED(`observation_date,SP500`)는 지금 최근 약 1년치만 받아져서 시드로 쓸 수 없고 값 대조용으로 쓴다(2026-11-18 대조: 2025-12 ~ 2026-10 월말 종가 11개 모두 Investing.com 값과 같음). 변환 스크립트는 두 형식도 읽는다.

## 이용 조건 메모

- FRED 이용약관: 저작권이 있는 시리즈는 비상업적 교육·개인 용도로만 쓸 수 있고, 그 밖의 용도는 저작권자 허락이 필요하다.
- Investing.com은 로그인 후 받는 데이터다. 해커톤 시연 범위에서 출처를 밝히고 로컬에서만 쓰며 레포·배포물에 넣지 않는다.

## 테스트

- 기준 데이터 테스트는 `src/test/resources/refdata/test-*.csv`(수식으로 만든 가짜 값)를 쓴다. 실제 S&P500 파일이 없어도 돈다.
- 변환 스크립트 테스트: `python3 -m unittest discover -s backend/scripts/refdata`
