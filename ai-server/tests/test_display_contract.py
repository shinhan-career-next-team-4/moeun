"""display 키 계약(app/core/contract.py)이 분리 전 규칙과 같은 키를 다루는지 고정한다(#211).

아래 LEGACY_* 는 계약 모듈로 모으기 전에 코드에 있던 정규식·목록을 그대로 옮긴 것이다. 모듈이 이 값과 다르게
조립되면 받는 키, 요구하는 키, 막는 키가 바뀐 것이다.
"""

import ast
import re
from pathlib import Path

import pytest

from app import schemas
from app.core import contract
from app.llm import plan

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "ai-server" / "app"
PLAN_CONTEXT_DOC = ROOT / "docs" / "api" / "plan-context.md"

LEGACY_ALLOWED = (
    r"goalAmount|calcStartMonth|targetMonth"
    r"|(?:GOOD|AVG|BAD)\.(?:afterTaxAsset|principal|afterTaxGain|gapToGoal|goalReachMonth)"
    r"|conditions\.(?:heldAssets|monthlySavingAmount|targetPeriodMonths|savingsRatioPct|savingsRateInputPct)"
    r"|assumptions\.(?:savingsRateBaseMonth|sp500BaseDate|taxRatePct|etfProductName|etfGainTaxRatePct)"
)
LEGACY_LEAK = r"\b(?:current|changed|diff|conditions|assumptions)\.[A-Za-z]|\b(?:GOOD|AVG|BAD)\.[A-Za-z]"
LEGACY_CALCULATED = (
    "goalAmount",
    "calcStartMonth",
    "targetMonth",
    "conditions.heldAssets",
    "conditions.monthlySavingAmount",
    "conditions.targetPeriodMonths",
    "conditions.savingsRatioPct",
    "conditions.savingsRateInputPct",
    "assumptions.taxRatePct",
)
LEGACY_SCENARIO_FIELDS = ("afterTaxAsset", "principal", "afterTaxGain", "gapToGoal")
LEGACY_EXCEEDED = ("goalAmount", "conditions.heldAssets")

SCENARIOS = ("GOOD", "AVG", "BAD")
SCENARIO_FIELDS = ("afterTaxAsset", "principal", "afterTaxGain", "gapToGoal", "goalReachMonth")
CONDITION_FIELDS = ("heldAssets", "monthlySavingAmount", "targetPeriodMonths", "savingsRatioPct", "savingsRateInputPct")
ASSUMPTION_FIELDS = ("savingsRateBaseMonth", "sp500BaseDate", "taxRatePct", "etfProductName", "etfGainTaxRatePct")
ALLOWED_KEYS = [
    "goalAmount",
    "calcStartMonth",
    "targetMonth",
    *(f"{s}.{f}" for s in SCENARIOS for f in SCENARIO_FIELDS),
    *(f"conditions.{f}" for f in CONDITION_FIELDS),
    *(f"assumptions.{f}" for f in ASSUMPTION_FIELDS),
]
# 허용 키 밖이지만 비슷하게 생긴 것들. 규칙이 넓어지거나 좁아지면 여기서 갈린다
NEAR_MISSES = [
    "",
    "x",
    "conditions.foo",
    "conditions.",
    "assumptions.etfProductCode",
    "AVG.afterTaxAssetDelta",
    "AVG.",
    "avg.afterTaxAsset",
    "current.AVG.afterTaxAsset",
    "changed.GOOD.principal",
    "diff.GOOD.principalDelta",
    "conditions.current.goalAmount",
    "conditions.changed.goalAmount",
    "goalAmountX",
    "Xgoal",
    "targetMonths",
    "display.goalAmount",
]
CANDIDATES = [
    *ALLOWED_KEYS,
    *NEAR_MISSES,
    *(f"x{k}" for k in ALLOWED_KEYS),
    *(f"{k}x" for k in ALLOWED_KEYS),
    *(f"current.{k}" for k in ALLOWED_KEYS),
    *(f"changed.{k}" for k in ALLOWED_KEYS),
    *(f"diff.{k}" for k in ALLOWED_KEYS),
    *(f"본문에 {k} 가 그대로 있다" for k in ALLOWED_KEYS + NEAR_MISSES),
    *(f"{k}." for k in ALLOWED_KEYS),
]


def test_allowed_key_pattern_is_unchanged():
    assert contract.DISPLAY_KEY.pattern == LEGACY_ALLOWED


def test_leak_pattern_is_unchanged():
    assert contract.DISPLAY_KEY_LEAK.pattern == LEGACY_LEAK


@pytest.mark.parametrize("key", CANDIDATES)
def test_allowed_pattern_matches_like_the_legacy_one(key: str):
    assert bool(contract.DISPLAY_KEY.fullmatch(key)) == bool(re.fullmatch(LEGACY_ALLOWED, key)), key


@pytest.mark.parametrize("text", CANDIDATES)
def test_leak_pattern_matches_like_the_legacy_one(text: str):
    assert bool(contract.DISPLAY_KEY_LEAK.search(text)) == bool(re.search(LEGACY_LEAK, text)), text


def test_every_legal_key_is_allowed():
    assert all(contract.DISPLAY_KEY.fullmatch(k) for k in ALLOWED_KEYS)


def test_required_key_lists_are_unchanged():
    assert contract.CALCULATED_KEYS == LEGACY_CALCULATED
    assert contract.REQUIRED_SCENARIO_FIELDS == LEGACY_SCENARIO_FIELDS
    assert contract.GOAL_REACH_MONTH == "goalReachMonth"
    assert contract.EXCEEDED_KEYS == LEGACY_EXCEEDED


def test_required_keys_are_all_allowed_keys():
    required = [
        *contract.CALCULATED_KEYS,
        *contract.EXCEEDED_KEYS,
        *(contract.scenario_key(s, f) for s in contract.SCENARIOS for f in contract.REQUIRED_SCENARIO_FIELDS),
        *(contract.scenario_key(s, contract.GOAL_REACH_MONTH) for s in contract.SCENARIOS),
    ]
    assert all(contract.DISPLAY_KEY.fullmatch(k) for k in required), required


def test_scenario_key_joins_with_a_dot():
    assert contract.scenario_key("AVG", "gapToGoal") == "AVG.gapToGoal"


def test_every_dotted_allowed_key_is_caught_by_the_leak_check():
    """허용 키에 네임스페이스를 더하고 누출 검사에서 빠뜨리는 실수를 잡는다. 점이 없는 최상위 키는 낱말이라 잡지 않는다."""
    for key in ALLOWED_KEYS:
        assert bool(contract.DISPLAY_KEY_LEAK.search(key)) == ("." in key), key


@pytest.mark.parametrize("name", ["conditions.foo", "current.AVG.afterTaxAsset", "diff.GOOD.principalDelta"])
def test_leak_check_is_namespace_based_not_key_based(name: str):
    assert contract.DISPLAY_KEY_LEAK.search(name)
    assert not contract.DISPLAY_KEY.fullmatch(name)


def test_scenario_names_are_the_wire_enum():
    assert contract.SCENARIOS == SCENARIOS
    assert schemas.Scenario is contract.Scenario
    assert [s.value for s in contract.Scenario] == list(SCENARIOS)


def test_calc_response_keys():
    assert contract.CALC_SIDES == ("current", "changed")
    assert contract.calc_scenario_key("current", "GOOD", contract.AFTER_TAX_ASSET) == "current.GOOD.afterTaxAsset"
    assert contract.calc_condition_key("changed", contract.GOAL_AMOUNT) == "conditions.changed.goalAmount"


def test_existing_names_are_re_exported():
    assert schemas.DISPLAY_KEY is contract.DISPLAY_KEY
    assert plan.TAX_RATE_KEY == "assumptions.taxRatePct"
    assert plan.ETF_TAX_RATE_KEY == "assumptions.etfGainTaxRatePct"
    assert plan.ETF_NAME_KEY == "assumptions.etfProductName"


def test_plan_context_doc_lists_the_same_allowed_keys():
    """문서의 정규식 줄은 캡처 그룹 (…), 코드는 비캡처 그룹 (?:…)이다. 그룹만 맞추면 같은 문자열이다."""
    lines = [raw.strip() for raw in PLAN_CONTEXT_DOC.read_text(encoding="utf-8").splitlines()]
    line = next(raw for raw in lines if raw.startswith("(goalAmount|"))
    assert line == "(" + contract.DISPLAY_KEY.pattern.replace("(?:", "(") + ")"


# prompts.py는 모델에게 보내는 안내 문장이고 copy.py의 같은 낱말은 문구표 키라서 계약 키가 아니다
VOCABULARY_EXEMPT = {"core/contract.py", "core/copy.py", "llm/prompts.py"}
COPY_KEY_PREFIX = "condition."   # assemble.py가 문구표를 읽는 키(condition.goalAmount 등). display 키가 아니다


def _key_names() -> re.Pattern[str]:
    names = {*contract.TOP_LEVEL_KEYS, *contract.SCENARIO_FIELDS, *contract.CONDITION_FIELDS, *contract.ASSUMPTION_FIELDS}
    return re.compile(r"(?<![A-Za-z])(?:" + "|".join(sorted(map(re.escape, names))) + r")(?![A-Za-z])")


def _docstring_node_ids(tree: ast.AST) -> set[int]:
    ids: set[int] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Module | ast.ClassDef | ast.FunctionDef | ast.AsyncFunctionDef):
            first = node.body[0] if node.body else None
            if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant):
                ids.add(id(first.value))
    return ids


def _key_literal(node: ast.AST, skip: set[int], names: re.Pattern[str]) -> str | None:
    if not isinstance(node, ast.Constant) or not isinstance(node.value, str) or id(node) in skip:
        return None
    value = node.value
    if value.startswith(COPY_KEY_PREFIX) or names.search(value) is None:
        return None
    return value


def test_no_display_key_literals_outside_the_contract():
    """키 이름이 f-string 조각(".afterTaxAsset")이나 점이 이어진 이름("conditions.current.goalAmount")으로 쓰여도 잡는다."""
    names = _key_names()
    found = []
    for path in sorted(APP.rglob("*.py")):
        if path.relative_to(APP).as_posix() in VOCABULARY_EXEMPT:
            continue
        tree = ast.parse(path.read_text(encoding="utf-8"))
        skip = _docstring_node_ids(tree)
        for node in ast.walk(tree):
            value = _key_literal(node, skip, names)
            if value is not None:
                found.append(f"{path.relative_to(APP)}:{getattr(node, 'lineno', 0)} {value!r}")
    assert not found, found
