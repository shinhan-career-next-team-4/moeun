"""S0-0. 오라클 함수 자체의 확인. 서버 없이 돈다."""
from oracle import citation_orgs, foreign_chars


def test_foreign_chars_catches_cyrillic():
    """S9 J2에서 실제로 나온 답(#376)."""
    text = "목표 금액을 약ніх 4,500만원에서 약 3,500만원으로 낮춰 봤어요."
    assert foreign_chars(text) == ["н", "і", "х"]


def test_foreign_chars_allows_korean_ascii_and_copy_punctuation():
    text = ("평균 기준 약 5,456만원(+약 456만원 달성)이에요. 좋았던 때·평균·나빴던 때 모두 3.2%~4% 사이!\n"
            "“S&P500 추종 ETF” 자료에 따르면… 약 10,000만원 → 약 3,500만원 – 끝 — ‘참고’ ㄱ")
    assert foreign_chars(text) == []


def test_foreign_chars_catches_cjk_and_symbols():
    assert foreign_chars("예시例 ⚠ 확인") == ["例", "⚠"]


def test_citation_orgs_counts_each_org_once():
    """같은 문서가 여러 번 나와도(#375 전) 기관 수는 그대로다."""
    tiger = {"orgName": "미래에셋자산운용", "title": "TIGER 미국S&P500 투자설명서"}
    kodex = {"orgName": "삼성자산운용", "title": "KODEX 200 투자설명서"}
    assert citation_orgs([tiger, tiger, tiger]) == {"미래에셋자산운용"}
    assert citation_orgs([kodex, tiger, tiger]) == {"삼성자산운용", "미래에셋자산운용"}
    assert citation_orgs([]) == set()
