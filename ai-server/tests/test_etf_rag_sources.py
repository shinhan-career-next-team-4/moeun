"""ETF 종목별 근거 자료(공시)가 허락 목록과 적재 스크립트에 들어 있는지 본다(Issue #181, FR-AI-10).

종목 코드는 백엔드 seed(069500 코스피200, 133690 나스닥100)와 같다.
"""

import re

import pytest

from app.ingest.allowlist import DEFAULT_ALLOWLIST, NotLicensedError, check_allowed, load_allowlist

INGEST_SH = DEFAULT_ALLOWLIST.parent / "ingest.sh"

KODEX200_URL = "https://www.samsungfund.com/upload/invest/2ETF01-A.pdf"
TIGER_NASDAQ100_URL = "https://investments.miraeasset.com/tigeretf/upload/etf/20260528095823000271.pdf"


@pytest.mark.parametrize(
    ("url", "org"),
    [(KODEX200_URL, "삼성자산운용"), (TIGER_NASDAQ100_URL, "미래에셋자산운용")],
)
def test_allowlist_has_kospi200_and_nasdaq100_disclosures(url, org):
    """AC1: 코스피200·나스닥100 운용사 공시가 허락 목록에 있다(ASSET_MANAGER, DISCLOSURE, 64자리 해시)."""
    by_url = {s.url: s for s in load_allowlist(DEFAULT_ALLOWLIST)}

    source = by_url[url]

    assert (source.org, source.org_type, source.doc_type) == (org, "ASSET_MANAGER", "DISCLOSURE")
    assert re.fullmatch(r"[0-9a-f]{64}", source.content_sha256)


def test_allowlist_rejects_unlisted_etf_document():
    """AC3 예외: 허락 목록에 없는 자료(같은 운용사의 다른 URL)는 막힌다."""
    allowlist = load_allowlist(DEFAULT_ALLOWLIST)

    with pytest.raises(NotLicensedError):
        check_allowed(
            allowlist,
            url="https://www.samsungfund.com/upload/invest/NOT-LISTED.pdf",
            org="삼성자산운용",
            org_type="ASSET_MANAGER",
            doc_type="DISCLOSURE",
            content_sha256="0" * 64,
        )


@pytest.mark.parametrize(
    ("url", "etf_code"),
    [(KODEX200_URL, "069500"), (TIGER_NASDAQ100_URL, "133690")],
)
def test_ingest_script_ingests_each_new_etf_with_its_code(url, etf_code):
    """AC1: 적재 스크립트가 종목 코드와 함께 두 문서를 적재한다."""
    script = INGEST_SH.read_text(encoding="utf-8")

    assert url in script
    assert re.search(rf'--url "\$\w+"[^\n]*--etf-code {etf_code}\b', script)
