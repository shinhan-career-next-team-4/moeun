import re
from collections.abc import Iterable


class NumberMismatch(Exception):
    """본문에 근거 없는 금액·기간이 있다. 호출자가 AgentError(ErrorCode.NUMBER_MISMATCH)로 바꿔 올린다."""


# 숫자: 쉼표 묶음("1,234")과 소수("1.5")를 한 덩어리로 본다
_NUMBER = r"(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?"
# 대조할 토큰. 앞쪽 대안이 먼저 걸린다
# - 날짜 'N년 M월'은 한 토큰이다(연도·월을 따로 찾아 조합하지 않는다)
# - 금액은 원·만원·억(원)이고 숫자와 단위 사이 공백까지 글자 그대로 본다
# - 기간은 N년·N개월·N달. '달러'는 기간이 아니다
_TOKEN = re.compile(
    rf"\d{{4}}\s*년\s*\d{{1,2}}\s*월"
    rf"|{_NUMBER}\s*(?:억(?:\s*원)?|만\s*원|원)"
    rf"|{_NUMBER}\s*(?:년|개월|달(?!러))"
)
# 근거 문자열에서 토큰 바로 앞이 숫자·쉼표·소수점이면 다른 숫자의 꼬리다("580만원" ⊂ "1,580만원")
_TAIL_PREFIX = "0123456789,."
# 억·만 뒤의 원은 단위를 다시 적은 것이다. 사용자가 "1억으로"라고 말하고 답이 "1억 원"이라고 써도 같은 금액이다(#317)
_WON_AFTER_UNIT = re.compile(r"(?<=[억만])\s*원$")
# 원을 뗀 'N억'·'N만' 뒤에 수·더 작은 단위·반·다른 화폐가 이어지면 다른 금액이다
# ("1억 5천만원", "1억 오만원", "1억 반", "1만5천원", "100만 달러"). 모르는 말이 이어져도 거절 쪽으로 틀린다
_UNIT_CONTINUES = re.compile(r"\s*(?:\d|[일이삼사오육칠팔구]?[십백천만]|반|달러)")


def _in_evidence(token: str, evidence: list[str]) -> bool:
    for text in evidence:
        start = text.find(token)
        while start != -1:
            if start == 0 or text[start - 1] not in _TAIL_PREFIX:
                return True
            start = text.find(token, start + 1)
    return False


def _bare_in_evidence(bare: str, evidence: list[str]) -> bool:
    """원을 뗀 'N억'·'N만'이 근거에 토큰 경계로 있고, 뒤에 다른 금액을 만드는 말이 이어지지 않는다."""
    for text in evidence:
        start = text.find(bare)
        while start != -1:
            end = start + len(bare)
            continues = _UNIT_CONTINUES.match(text, end)
            if (start == 0 or text[start - 1] not in _TAIL_PREFIX) and not continues:
                return True
            start = text.find(bare, start + 1)
    return False


def check_numbers(content: str, evidence: Iterable[str]) -> None:
    """본문의 금액·기간·날짜 토큰이 모두 근거 문자열 안에 토큰 경계로 그대로 있어야 통과한다.
    '약'·부호 생략과 억·만 뒤의 원 생략은 허용하고 단위 환산은 하지 않는다. 하나라도 없으면 NumberMismatch."""
    texts = list(evidence)
    for m in _TOKEN.finditer(content):
        token = m.group()
        if _in_evidence(token, texts):
            continue
        bare = _WON_AFTER_UNIT.sub("", token)
        if bare == token or not _bare_in_evidence(bare, texts):
            raise NumberMismatch(token)
