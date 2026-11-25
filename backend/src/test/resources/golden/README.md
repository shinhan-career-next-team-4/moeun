# 계산 골든 케이스

`docs/calc-spec.md`의 계산 규칙을 손으로 계산한 기대값이다. 계산 코드 없이 읽을 수 있게 파일마다 `handCalc`에 계산 과정을 적었다. 엔진 테스트(#26, #27)는 이 파일을 읽어 원 단위까지 같은지 확인한다.

| 파일 | 종류 | 확인하는 것 |
|---|---|---|
| `g1-savings-only-minimal.json` | plan | 적금만, 회차별 단리·세금·월별 점 |
| `g2-savings-only-a1-plan.json` | plan | 보유 자산 포함, 36개월 → 점 37개, 첫 점·마지막 점 |
| `g3-already-exceeded.json` | plan | 보유 자산 = 목표 → `ALREADY_EXCEEDED` |
| `g4-gap-zero-achieved.json`, `g4-gap-zero-missed.json` | plan | 목표 대비 0원은 달성, -1원은 미달 |
| `g5-etf-single-path.json` | component | 정해진 수익률 순서 하나로 ETF 경로·세금(손실이면 0) |
| `g6-percentile-and-mean.json` | component | 하위 10%·산술평균·상위 90%(선형 보간) |
| `g7-monthly-returns.json` | component | 월말 종가 → 한 달 수익률 |
| `g8-bootstrap-constant-returns.json` | plan | 부트스트랩 전체. 수익률이 모두 같아 무작위와 관계없이 세 구간이 같다 |
| `g9-etf-single-path-tax-free.json` | component | G5와 같은 경로, 비과세 종목(매매차익 세율 0%, #150) |
| `g10-bootstrap-constant-returns-tax-free.json` | plan | G8과 같은 입력, 비과세 종목. 적금 이자 세율 15.4%는 그대로(#150) |

- `plan`: `params.etfGainTaxRatePct`가 있으면 ETF 종목의 매매차익 세율이다(없으면 대표 상품 S&P500의 15.4%). `CalcInput`과 같은 입력, `CalcResult`와 같은 모양의 기대값. 금액은 원 단위 정수, 금리·세율은 문자열 소수(BigDecimal로 읽는다).
- `component`: 엔진 안의 한 단계만 확인한다. 부트스트랩은 무작위 추출이라 실제 데이터의 3구간 값을 손으로 계산할 수 없어서, 손으로 계산할 수 있는 단계로 나눴다.
- 실제 S&P500 데이터로 낸 3구간 값은 시드를 고정한 회귀 값으로 #27에서 따로 고정한다.
- 기대값은 명세를 엔진과 별개로 옮긴 계산(BigDecimal, 유효숫자 34자리)으로 교차 검산했다.
