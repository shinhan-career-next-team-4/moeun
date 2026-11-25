"""대화로 되돌리기(#419, D-103). 표현을 되돌릴 범위로 해석하고, 변경 목록을 되돌릴 것과 남길 것으로 나눈다.

값을 계산하지 않고 목록에서 고르기만 한다(NFR-INT-01). 남은 조건의 결과는 계산 엔진이 다시 만든다.
되묻지 않고 가장 그럴듯한 해석으로 바로 실행한다. 모델이 고른 범위보다 표현이 분명하면 표현을 따른다.
"""

import re
from collections.abc import Sequence
from typing import Literal

from app.llm.conditions import mentions_etf_product
from app.schemas import ConditionChangeEntry, ConditionChanges

RevertScope = Literal["last", "all", "condition"]

# 되돌리기 말. 범위 판정은 이 말이 있을 때만 한다(모두·처음 계열은 그것만으로 되돌리기다)
_REVERT = re.compile(r"원래|아까\s*거|취소|없던\s*걸로|되돌|돌려|이전으로")
_ALL = re.compile(r"다\s*(?:원래|취소|되돌|돌려)|전부|모두|싹\s*다|처음|내\s*계획대로|원래\s*계획|전체")
# 제외를 말하는 표현("월 저축액은 그대로 두고 나머지 다"). 도구 하나로 나타낼 수 없어 모델에 맡긴다
_EXCEPT = re.compile(r"나머지|빼고|말고|그대로\s*두고|제외")
# 조건 이름. 하나만 가리킬 때만 그 조건으로 본다(둘 이상이면 범위를 모델에 맡긴다)
_CONDITION_WORDS: dict[str, re.Pattern[str]] = {
    "savings_ratio_pct": re.compile(r"비중|비율"),
    "savings_rate_input_pct": re.compile(r"금리|이율|이자"),
    "monthly_saving_amount": re.compile(r"월\s*저축|저축액|매달|납입"),
    "target_period_months": re.compile(r"기간|기한"),
    "goal_amount": re.compile(r"목표\s*금액"),
    "goal_custom_name": re.compile(r"목표\s*이름"),
}

def infer_revert(text: str) -> tuple[RevertScope, str | None] | None:
    """표현이 분명히 가리키는 되돌리기 범위. 분명하지 않으면 None(모델의 선택을 따른다). condition은 snake_case 이름이다.

    조건 이름을 먼저 본다. "비중만 원래 계획대로", "기간은 처음 계획대로"처럼 조건 하나를 지목하면 '모두'·'처음' 같은 말이
    있어도 그 조건이다(목록 전부를 지우는 쪽으로 틀리지 않는다). "나머지", "빼고"처럼 제외를 말하거나 조건이 둘 이상이면
    도구 하나로 나타낼 수 없어 모델에 맡긴다. 조건 이름 없이 '모두' 계열이면 all, 되돌리기 말만 있으면 last다.
    """
    lowered = text.lower()
    named = [name for name, pattern in _CONDITION_WORDS.items() if pattern.search(lowered)]
    if mentions_etf_product(lowered):
        named.append("etf_product_code")
    if _EXCEPT.search(lowered) or len(named) > 1:
        return None
    if len(named) == 1:
        return ("condition", named[0]) if _REVERT.search(lowered) or _ALL.search(lowered) else None
    if _ALL.search(lowered):
        return "all", None
    return ("last", None) if _REVERT.search(lowered) else None


def resolve_revert(text: str, scope: str, condition: str | None) -> tuple[RevertScope, str | None]:
    """모델이 고른 범위를 표현으로 바로잡는다. 표현이 모두(all)나 한 조건을 분명히 가리킬 때만 표현을 따른다(infer_revert).

    "아니 전부"처럼 바로잡는 말에 모델이 앞 턴과 같은 범위를 골라도 표현대로 처리한다. "150은 취소"처럼 조건 이름 없이
    값으로 가리킨 것은 모델의 조건 선택을 따른다.
    """
    inferred = infer_revert(text)
    if inferred is not None and inferred[0] != "last":
        return inferred
    if scope == "condition":
        return "condition", condition
    return ("all", None) if scope == "all" else ("last", None)


def camel_of(condition: str) -> str:
    """snake_case 조건 이름의 camelCase 키(변경 목록·Spring의 키)."""
    alias = ConditionChanges.model_fields[condition].alias
    return alias or condition


def split_entries(
    entries: Sequence[ConditionChangeEntry], scope: RevertScope, field: str | None
) -> tuple[list[ConditionChangeEntry], list[ConditionChangeEntry]]:
    """(되돌릴 항목, 남길 항목). last는 가장 최근 질문(messageId)의 변경 전부다. messageId가 없으면 마지막 항목 하나다.
    condition은 그 조건의 변경 전부라 그 조건은 계획 값으로 돌아간다."""
    items = list(entries)
    if scope == "all":
        return items, []
    if scope == "condition":
        return [e for e in items if e.field == field], [e for e in items if e.field != field]
    last_id = items[-1].message_id
    if last_id is None:
        return items[-1:], items[:-1]
    return [e for e in items if e.message_id == last_id], [e for e in items if e.message_id != last_id]
