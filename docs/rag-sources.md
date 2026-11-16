# RAG 근거 자료 선정과 이용 허락 확인

Issue #24 (T-016) · 요구사항 FR-AI-03, FR-AI-10, NFR-INT-04 · 결정 TBD-08, TBD-31, D-007, D-028

상태: **초안. 팀 승인 대기.** 조사일 2026-11-16. 이 목록이 적재(`moeun-ingest`)와 출처 표시 작업의 입력이다.

## 결정과 한계
- 작업자가 **운용사의 투자설명서 2건은 적재해도 되는 것으로 간주**하기로 했다. 근거는 Issue #24와 TBD-08이 "불확실하면 투자설명서 PDF 2건으로 한정"을 대안으로 둔 점과 이 자료가 법정 공시라는 점이다. 법적 검토를 받은 것이 아니라 판단이다. 두 운용사 사이트에는 데이터베이스화 금지 고지가 있어 RAG 적재가 이에 해당할 수 있으므로, **팀 승인 전에는 적재하지 않는다.** 아래 "이용 허락 상태"의 `허용(간주)`가 이것이다.
- 이용 허락이 불확실한 자료는 적재 대상에서 제외하고 사유를 적었다("제외한 자료").
- 작성 기준일은 코드 리뷰 중 두 PDF 첫 장에서 읽은 값이다(작성 기준일 / 효력발생일). 이 작업자는 PDF 본문을 직접 읽지 않았으므로 적재 전에 첫 장과 대조한다.
- 자료를 repo에 넣지 않는다. PDF는 `ai-server/docs/raw/`(git 제외)에 받아 둔다.

## 선정 목록
서로 다른 운용사의 공시 2건이다(FR-AI-03 "운용사 2곳 이상"). D-007의 후보(KODEX S&P, TIGER S&P)와 같다.

| # | 구분 | 기관(운용사) | 자료 | URL | 이용 허락 상태 | 적재 |
|---|---|---|---|---|---|---|
| 1 | DISCLOSURE | 삼성자산운용 | KODEX 미국S&P500 투자설명서(종목코드 379800) | https://www.samsungfund.com/upload/invest/2ETFE4-A.pdf | 허용(간주): 법정 공시. 사이트 무단 사용·DB화 금지 고지가 있어 팀 승인 필요 | 대상(팀 승인 후) |
| 2 | DISCLOSURE | 미래에셋자산운용 | TIGER 미국S&P500 투자설명서(종목코드 360750) | https://investments.miraeasset.com/tigeretf/upload/etf/20261008090949000003.pdf | 허용(간주): 법정 공시. 사이트 무단 사용·DB화 금지 고지가 있어 팀 승인 필요 | 대상(팀 승인 후) |

URL을 찾은 곳(2026-11-16에 둘 다 HTTP 200, `application/pdf` 확인):
- 1: 상품 페이지 https://www.samsungfund.com/etf/product/view.do?id=2ETFE4 의 "투자설명서" 링크. 상품 ID가 `2ETFE4`다.
- 2: 상품 페이지 https://investments.miraeasset.com/tigeretf/ko/product/search/detail/index.do?ksdFund=KR7360750004 의 "관련 자료 > 투자설명서" 링크.

운용사가 개정본을 올리면 PDF 주소나 내용이 바뀔 수 있다. 같은 URL의 새 내용은 새 행으로 들어가고 이전 개정본은 검색에서 빠진다(`ai-server/README.md`). 주소 자체가 바뀌면 이 목록을 고친다.

## 제외한 자료
| 자료 | 기관 | 사유 |
|---|---|---|
| 금융 교육자료(e-금융교육센터 교재·콘텐츠) | 금융감독원 | 저작권법상 보호 저작물이고 개방 라이선스(공공누리 등) 표시가 없다. 출처("금융감독원") 명시가 필요하고, 수익 목적 이용은 사전 허락이 필요하다고 적혀 있다(https://www.fss.or.kr/edu/main/contents.do?menuNo=300113). 이 서비스의 이용이 그 범위인지 불확실해 제외. 허락을 받으면 `EDU`·`REGULATOR`로 추가한다 |
| 금융투자협회·한국거래소 교육자료 | 금융투자협회, 한국거래소 | 사이트가 JS로 그려져 자료와 이용 조건을 확인하지 못했다. 확인 전까지 제외 |
| KODEX·TIGER 간이투자설명서 | 삼성·미래에셋 | 투자설명서의 요약본이라 같은 내용이 중복된다. 인용은 원문(투자설명서)이 낫다 |
| KODEX·TIGER 월간운용보고서(FactSheet) | 삼성·미래에셋 | 시점 수익률 수치 위주의 1쪽 요약이라 설명 근거로 쓸 내용이 적고, 수치를 그대로 인용할 위험이 있다(NFR-INT-01). 운용사 웹사이트 하단에는 지수·수익률 정보가 코스콤·사무수탁사 제공분이며 오류·지연될 수 있다는 고지가 있다 |

교육자료(`EDU`)는 지금 목록에 없다. FR-AI-10의 "금융 교육자료"는 허락이 확인되는 자료가 생길 때 채운다. 불확실하면 투자설명서 PDF 2건으로 한정한다는 TBD-08의 대안과 같은 상태다.

## 이용 허락 근거(조사 원문)
- 삼성자산운용 https://www.samsungfund.com/etf/main.do 하단: "본 웹사이트의 자료를 사전 허가없이 무단으로 사용하거나 데이터베이스화 할 경우, 민형사상 법적 책임을 질 수 있습니다." 이용약관 페이지는 찾지 못했다.
- 미래에셋자산운용 TIGER ETF 페이지 하단에 같은 취지의 고지가 있다. 이용약관(https://investments.miraeasset.com/magi/use.do) 제13조 3항은 회원이 얻은 정보를 회사의 사전 동의 없이 복사·복제·변경·제공하지 못하게 한다. 약관은 "회원"과 홈페이지 서비스 기준이다.
- 두 고지는 공시 문서의 이용 조건을 따로 정하지 않았다. 그래서 "허용(간주)"이다. 확실히 하려면 두 운용사에 RAG 적재·출처 표시 목적의 이용을 문의해 답을 받는다.

## 적재 CLI 인자
`ai-server/`에서 실행한다. `--published-at`은 PDF 첫 장의 작성 기준일이다(1: 2026-08-07, 2: 2026-10-01. 효력발생일은 각각 2026-08-26, 2026-10-08). 출처 링크에는 상품명을 그대로 보여주고 본문 설명에서는 "S&P500 추종 ETF"로만 쓴다는 규칙(D-028)은 적재 값이 아니라 답변 단계의 규칙이다. `--title`에는 상품명을 그대로 적는다.

| # | `--doc-type` | `--org` | `--org-type` | `--title` | `--url` | `--etf-code` |
|---|---|---|---|---|---|---|
| 1 | `DISCLOSURE` | `삼성자산운용` | `ASSET_MANAGER` | `KODEX 미국S&P500 투자설명서` | `https://www.samsungfund.com/upload/invest/2ETFE4-A.pdf` | `379800` |
| 2 | `DISCLOSURE` | `미래에셋자산운용` | `ASSET_MANAGER` | `TIGER 미국S&P500 투자설명서` | `https://investments.miraeasset.com/tigeretf/upload/etf/20261008090949000003.pdf` | `360750` |

```bash
mkdir -p docs/raw
curl -L -o docs/raw/kodex-sp500-prospectus.pdf https://www.samsungfund.com/upload/invest/2ETFE4-A.pdf
curl -L -o docs/raw/tiger-sp500-prospectus.pdf https://investments.miraeasset.com/tigeretf/upload/etf/20261008090949000003.pdf

uv run moeun-ingest --file docs/raw/kodex-sp500-prospectus.pdf --doc-type DISCLOSURE \
  --org "삼성자산운용" --org-type ASSET_MANAGER --title "KODEX 미국S&P500 투자설명서" \
  --url https://www.samsungfund.com/upload/invest/2ETFE4-A.pdf --etf-code 379800 --published-at 2026-08-07
uv run moeun-ingest --file docs/raw/tiger-sp500-prospectus.pdf --doc-type DISCLOSURE \
  --org "미래에셋자산운용" --org-type ASSET_MANAGER --title "TIGER 미국S&P500 투자설명서" \
  --url https://investments.miraeasset.com/tigeretf/upload/etf/20261008090949000003.pdf --etf-code 360750 --published-at 2026-10-01
```

## 승인
- [ ] 팀 승인(이용 허락 "간주" 판단 포함): 승인자 ____ / 일자 ____
- [ ] (선택) 두 운용사의 이용 문의 답변 기록
- [ ] (선택) 운용사 사이트 대신 투자설명서에 적힌 공식 열람처(DART)를 출처 URL로 쓸지 결정. DART의 이용 조건은 따로 확인해야 한다
