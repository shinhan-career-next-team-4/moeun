"""Spring과 주고받는 display 키 계약. 키 구성 요소를 여기서 한 번만 정의하고 나머지 코드는 여기서 만들어 쓴다.

계획(planContext)의 display와 계산 응답(/internal/calc)의 display는 키 모양이 다른 별개 계약이다.
이 모듈은 아무 app 모듈도 import하지 않는다(schemas.py보다 아래층).
"""

import re
from enum import StrEnum


class Scenario(StrEnum):
    GOOD = "GOOD"
    AVG = "AVG"
    BAD = "BAD"


SCENARIOS = tuple(s.value for s in Scenario)

# 키 이름의 앞부분(네임스페이스)
CONDITIONS = "conditions"
ASSUMPTIONS = "assumptions"
DIFF = "diff"
CURRENT_SIDE = "current"
CHANGED_SIDE = "changed"
CALC_SIDES = (CURRENT_SIDE, CHANGED_SIDE)

# ---- 계획 display(planContext, C1a-8) ----

GOAL_AMOUNT = "goalAmount"
TOP_LEVEL_KEYS = (GOAL_AMOUNT, "calcStartMonth", "targetMonth")

AFTER_TAX_ASSET = "afterTaxAsset"
GOAL_REACH_MONTH = "goalReachMonth"   # 달성한 시나리오에만 있다(G-17)
SCENARIO_FIELDS = (AFTER_TAX_ASSET, "principal", "afterTaxGain", "gapToGoal", GOAL_REACH_MONTH)
REQUIRED_SCENARIO_FIELDS = tuple(f for f in SCENARIO_FIELDS if f != GOAL_REACH_MONTH)

HELD_ASSETS = "heldAssets"
CONDITION_FIELDS = (HELD_ASSETS, "monthlySavingAmount", "targetPeriodMonths", "savingsRatioPct", "savingsRateInputPct")

TAX_RATE = "taxRatePct"
ETF_NAME = "etfProductName"
ETF_TAX_RATE = "etfGainTaxRatePct"
ASSUMPTION_FIELDS = ("savingsRateBaseMonth", "sp500BaseDate", TAX_RATE, ETF_NAME, ETF_TAX_RATE)

TAX_RATE_KEY = f"{ASSUMPTIONS}.{TAX_RATE}"   # D-060
ETF_TAX_RATE_KEY = f"{ASSUMPTIONS}.{ETF_TAX_RATE}"
ETF_NAME_KEY = f"{ASSUMPTIONS}.{ETF_NAME}"
HELD_ASSETS_KEY = f"{CONDITIONS}.{HELD_ASSETS}"

# 계획 설명에 꼭 있어야 하는 키. 달성한 시나리오의 goalReachMonth는 호출하는 쪽이 더한다
CALCULATED_KEYS = (
    *TOP_LEVEL_KEYS,
    *(f"{CONDITIONS}.{f}" for f in CONDITION_FIELDS),
    TAX_RATE_KEY,
)
EXCEEDED_KEYS = (GOAL_AMOUNT, HELD_ASSETS_KEY)   # 이미 목표를 넘은 계획


def scenario_key(scenario: str, field: str) -> str:
    return f"{scenario}.{field}"


def _alt(names: tuple[str, ...]) -> str:
    return "|".join(re.escape(n) for n in names)


# 받아도 되는 display 키. 여기에 맞고 값이 문자열인 항목만 받는다
DISPLAY_KEY = re.compile(
    f"{_alt(TOP_LEVEL_KEYS)}"
    f"|(?:{_alt(SCENARIOS)})\\.(?:{_alt(SCENARIO_FIELDS)})"
    f"|{CONDITIONS}\\.(?:{_alt(CONDITION_FIELDS)})"
    f"|{ASSUMPTIONS}\\.(?:{_alt(ASSUMPTION_FIELDS)})"
)

# 본문에 나오면 내부 이름이 보이는 표시 키. 키 목록이 아니라 네임스페이스로 잡는다. 계산 응답의 current·changed·diff도
# 포함하고, 최상위 낱말(goalAmount 등)은 일반 단어라 잡지 않는다
DISPLAY_KEY_LEAK = re.compile(
    f"\\b(?:{_alt((*CALC_SIDES, DIFF, CONDITIONS, ASSUMPTIONS))})\\.[A-Za-z]"
    f"|\\b(?:{_alt(SCENARIOS)})\\.[A-Za-z]"
)

# ---- 계산 응답 display(/internal/calc, C2-6) ----


def calc_scenario_key(side: str, scenario: str, field: str) -> str:
    return f"{side}.{scenario}.{field}"


def calc_condition_key(side: str, field: str) -> str:
    return f"{CONDITIONS}.{side}.{field}"
