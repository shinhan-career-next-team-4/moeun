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
# 조건 이름. 하나만 가리킬 때만 그 조건으로 본다(둘 이상이면 범위를 모델에 맡긴다)
_CONDITION_WORDS: dict[str, re.Pattern[str]] = {
    "savings_ratio_pct": re.compile(r"비중|비율"),
    "savings_rate_input_pct": re.compile(r"금리|이율|이자"),
    "monthly_saving_amount": re.compile(r"월\s*저축|저축액|매달|납입"),
    "target_period_months": re.compile(r"기간|기한"),
    "goal_amount": re.compile(r"목표\s*금액"),
    "goal_custom_name": re.compile(r"목표\s*이름"),
}

# 평가 세트: 되돌리기 표현 → 기대 도구 호출(scope, condition). 실제 모델 평가(tests/scenarios)와 서버 보정이 같이 쓴다
REVERT_EXPRESSIONS: dict[str, tuple[RevertScope, str | None]] = {
    "원래대로": ("last", None),
    "원래대로 해줘": ("last", None),
    "아까 거로": ("last", None),
    "아까 거 취소": ("last", None),
    "취소": ("last", None),
    "취소해 줘": ("last", None),
    "없던 걸로": ("last", None),
    "없던 걸로 해줘": ("last", None),
    "방금 거 되돌려줘": ("last", None),
    "되돌려줘": ("last", None),
    "이전으로 돌려줘": ("last", None),
    "처음으로": ("all", None),
    "다 원래대로": ("all", None),
    "전부 원래대로": ("all", None),
    "내 계획대로": ("all", None),
    "아니 전부": ("all", None),
    "모두 취소": ("all", None),
    "싹 다 되돌려줘": ("all", None),
    "원래 계획으로": ("all", None),
    "비중은 원래대로": ("condition", "savings_ratio_pct"),
    "저축 비중 취소": ("condition", "savings_ratio_pct"),
    "아까 바꾼 비중은 원래대로": ("condition", "savings_ratio_pct"),
    "월 저축액은 원래대로": ("condition", "monthly_saving_amount"),
    "금리는 원래대로": ("condition", "savings_rate_input_pct"),
    "기간은 원래대로": ("condition", "target_period_months"),
    "목표 금액 취소": ("condition", "goal_amount"),
    "종목은 원래대로": ("condition", "etf_product_code"),
}


def infer_revert(text: str) -> tuple[RevertScope, str | None] | None:
    """되돌리기 표현의 범위. 되돌리기 말이 없으면 None. condition은 snake_case 조건 이름이다."""
    lowered = text.lower()
    if _ALL.search(lowered):
        return "all", None
    if not _REVERT.search(lowered):
        return None
    named = [name for name, pattern in _CONDITION_WORDS.items() if pattern.search(lowered)]
    if mentions_etf_product(lowered):
        named.append("etf_product_code")
    if len(named) == 1:
        return "condition", named[0]
    return "last", None


def resolve_revert(text: str, scope: str, condition: str | None) -> tuple[RevertScope, str | None]:
    """모델이 고른 범위를 표현으로 바로잡는다. 표현이 모두(all)나 한 조건을 분명히 가리킬 때만 표현을 따른다.

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
