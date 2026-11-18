"""Issue #103: 프롬프트의 상품명 예시 제거(#36·#40 충돌).

#36의 '프롬프트에 상품명 없음'과 #40의 '본문은 상품명 대신 S&P500 추종 ETF, 자료의 상품명은 옮기지 않음' 규칙이
함께 성립하는지 본다. 상품명 예시만 빠지고 규칙의 뜻은 원래 자리(## 근거)와 ## 범위에 그대로 남아야 한다.
"""

from app.llm.assemble import replace_product_names
from app.llm.prompts import SYSTEM_PROMPT

EVIDENCE_RULE = "- 본문에는 상품명 대신 'S&P500 추종 ETF'라고 씁니다. 자료에 상품명이 있어도 본문에 옮기지 않습니다."
SCOPE_RULE = (
    '- 답변 본문에는 특정 ETF의 상품명(운용사 브랜드가 붙은 이름)을 쓰지 않고 항상 "S&P500 추종 ETF"로만 씁니다. '
    "검색 자료에 상품명이 있어도 본문에 옮기지 않습니다."
)


def section(title: str) -> str:
    return SYSTEM_PROMPT.split(f"## {title}", 1)[1].split("\n## ", 1)[0]


def test_system_prompt_has_no_product_name_by_server_guardrail_pattern():
    """서버 본문 검사(영문·한글 표기 상품명)로 프롬프트를 돌려도 거부되지 않고 한 글자도 바뀌지 않는다"""
    assert replace_product_names(SYSTEM_PROMPT) == SYSTEM_PROMPT


def test_evidence_section_keeps_generic_name_rule_without_product_examples():
    """## 근거의 규칙 줄은 상품명 예시만 빠진 완전한 문장이다. 서술어('씁니다')가 빠지거나 뒤집히면 실패한다"""
    assert EVIDENCE_RULE in section("근거").splitlines()


def test_scope_section_keeps_generic_name_rule():
    """## 범위의 본문 표기 규칙(#36)은 한 줄 그대로 남는다"""
    assert SCOPE_RULE in section("범위").splitlines()
