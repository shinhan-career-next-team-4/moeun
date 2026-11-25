/** 결과 화면에 거는 운용사 공시 한 건. 링크 글자는 문서 제목 그대로, 기관명은 링크 밖에 둔다(D-028). */
export interface EtfDisclosure {
  title: string;
  orgName: string;
  sourceUrl: string;
}

/**
 * 계획 종목 코드(`assumptions.etfProductCode`)별 운용사 공시(#374, D-104).
 * 적재를 허락받은 자료(scripts/ingest/allowlist.yml)만 둔다. 테스트가 URL을 그 파일과 비교한다.
 * 종목 ↔ 문서 매핑은 ai-server `app/rag/etf_codes.py`와 같다(S&P500은 상품이 둘이라 공시도 둘).
 */
export const ETF_DISCLOSURES: Readonly<Record<string, readonly EtfDisclosure[]>> = {
  SP500: [
    {
      title: "KODEX 미국S&P500 투자설명서",
      orgName: "삼성자산운용",
      sourceUrl: "https://www.samsungfund.com/upload/invest/2ETFE4-A.pdf",
    },
    {
      title: "TIGER 미국S&P500 투자설명서",
      orgName: "미래에셋자산운용",
      sourceUrl: "https://investments.miraeasset.com/tigeretf/upload/etf/20261008090949000003.pdf",
    },
  ],
  "069500": [
    {
      title: "KODEX 200 투자설명서",
      orgName: "삼성자산운용",
      sourceUrl: "https://www.samsungfund.com/upload/invest/2ETF01-A.pdf",
    },
  ],
  "133690": [
    {
      title: "TIGER 미국나스닥100 투자설명서",
      orgName: "미래에셋자산운용",
      sourceUrl: "https://investments.miraeasset.com/tigeretf/upload/etf/20260528095823000271.pdf",
    },
  ],
};
