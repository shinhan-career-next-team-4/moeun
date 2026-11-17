#!/usr/bin/env bash
# RAG 근거 자료(공시 투자설명서 2건)를 내려받아 적재한다. 몇 번 돌려도 중복 행이 생기지 않는다.
# 허락 목록(scripts/ingest/allowlist.yml)에 없는 자료는 moeun-ingest가 거부한다. 근거: docs/rag-sources.md
# URL을 바꾸면 scripts/ingest/allowlist.yml도 같이 바꾼다(아니면 게이트가 거부한다)
# 필요: ai-server/.env의 OPENAI_API_KEY·DATABASE_URL, 실행 중인 pgvector DB
set -euo pipefail

cd "$(dirname "$0")/../../ai-server"
mkdir -p docs/raw

KODEX_URL=https://www.samsungfund.com/upload/invest/2ETFE4-A.pdf
TIGER_URL=https://investments.miraeasset.com/tigeretf/upload/etf/20261008090949000003.pdf

# 받다가 끊긴 파일이 남지 않게 임시 파일로 받아 옮긴다(덜 받은 PDF가 새 개정본으로 적재되는 것을 막는다)
fetch() {
  [ -f "$2" ] || { curl -fL -o "$2.part" "$1" && mv "$2.part" "$2"; }
}
fetch "$KODEX_URL" docs/raw/kodex-sp500-prospectus.pdf
fetch "$TIGER_URL" docs/raw/tiger-sp500-prospectus.pdf

uv run moeun-ingest --file docs/raw/kodex-sp500-prospectus.pdf --doc-type DISCLOSURE \
  --org "삼성자산운용" --org-type ASSET_MANAGER --title "KODEX 미국S&P500 투자설명서" \
  --url "$KODEX_URL" --etf-code 379800 --published-at 2026-08-07
uv run moeun-ingest --file docs/raw/tiger-sp500-prospectus.pdf --doc-type DISCLOSURE \
  --org "미래에셋자산운용" --org-type ASSET_MANAGER --title "TIGER 미국S&P500 투자설명서" \
  --url "$TIGER_URL" --etf-code 360750 --published-at 2026-10-01
