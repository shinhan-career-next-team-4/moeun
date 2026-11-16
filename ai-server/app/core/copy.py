"""문구표(저장소 루트 copy/copy.ko.yml) 로더(CP-05). Spring과 같은 파일을 같은 키로 읽는다.

없는 키·빈 값·중복 키는 조용히 넘기지 않고 `CopyKeyError`로 실패한다.
"""

import os
import re
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import yaml  # type: ignore[import-untyped]

ENV_PATH = "MOEUN_COPY_PATH"
DEFAULT_PATH = Path(__file__).resolve().parents[3] / "copy" / "copy.ko.yml"

_PLACEHOLDER = re.compile(r"\{(\w+)\}")

_ERROR_CODES = (
    "VALIDATION_FAILED",
    "FIELD_NOT_CHANGEABLE",
    "NO_PLAN",
    "ALREADY_EXCEEDED",
    "MALFORMED_REQUEST",
    "INVALID_INTERNAL_KEY",
    "CALC_FAILED",
)
_CONDITION_CODES = ("monthlySavingAmount", "savingsRatioPct", "savingsRateInputPct", "targetPeriodMonths")
_ACHIEVEMENT_CHANGES = ("STAYS_ACHIEVED", "STAYS_MISSED", "MISS_TO_ACHIEVE", "ACHIEVE_TO_MISS")
# backend의 ExcludedItem·ScenarioMethod enum 코드와 같은 목록이다(G-14). enum이 늘면 두 곳을 함께 고친다.
_EXCLUDED_CODES = ("ETF_TOTAL_EXPENSE", "EXCHANGE_RATE", "ETF_DISTRIBUTION", "HELD_ASSET_RETURN", "SAVINGS_REDEPOSIT")
_SCENARIO_METHOD_CODES = ("CAGR", "ROLLING_DISTRIBUTION")
_GOAL_CODES = ("HOUSE", "CAR", "WEDDING", "EDUCATION", "TRAVEL", "EMERGENCY_FUND", "CUSTOM")  # D-075

_FIXED_KEYS = (
    "outOfScope",
    "outOfScope.tax",
    "disclaimer",
    "tax.note",
    "chat.unchangeableCondition",
    "chat.noPlan",
    "chat.alreadyExceeded",
    "chat.pollingFailed",
    "chat.sendFailed",
    "template.achieved",
    "template.missed",
    "template.increase",
    "template.decrease",
    "template.unchanged",
    "template.ratio",
    "toast.applyStaleComparison",
    "toast.applyPlanChanged",
    "toast.applyNotApplicable",
    "toast.applyFailed",
    "comparison.loadFailed",
    "common.networkError",
    "auth.invalidCredentials",
    "auth.loginFailed",
    "auth.logoutFailed",
    "input.saveFailed",
    "input.invalidAmountFormat",
    "input.currentRatioLabel",
    "input.heldAssetsRecheck",
    "plan.changedElsewhere",
    "undo.nothingToUndo",
    "undo.failed",
    "result.loadFailed",
    "result.alreadyExceeded",
    "result.notReachedInPeriod",
    "result.scenarioNotice",
    "result.defaultSavingsRate",
    "scenario.GOOD",
    "scenario.AVG",
    "scenario.BAD",
    "validation.REQUIRED",
    "validation.OUT_OF_RANGE",
    "validation.NOT_INTEGER",
    "validation.TOO_MANY_DECIMALS",
    "unit.manwon",
    "unit.won",
    "unit.month",
    "unit.pct",
    "unit.char",
)

REQUIRED_KEYS: frozenset[str] = frozenset(
    (
        *_FIXED_KEYS,
        *(f"excluded.{code}" for code in _EXCLUDED_CODES),
        *(f"scenarioMethod.{code}" for code in _SCENARIO_METHOD_CODES),
        *(f"goal.{code}" for code in _GOAL_CODES),
        *(f"condition.{code}" for code in _CONDITION_CODES),
        *(f"chat.exampleQuestion.{code}" for code in _CONDITION_CODES),
        *(f"error.title.{code}" for code in _ERROR_CODES),
        *(f"achievementChange.{value}" for value in _ACHIEVEMENT_CHANGES),
    )
)


class CopyKeyError(Exception):
    """문구표에 키가 없거나 값이 비었거나 파일 형식이 잘못됐다."""


class _UniqueKeyLoader(yaml.SafeLoader):  # type: ignore[misc]
    """같은 매핑 안의 중복 키를 거부하는 로더(PyYAML 기본은 뒤의 값이 조용히 이긴다)."""

    def construct_mapping(self, node: Any, deep: bool = False) -> dict[Any, Any]:
        seen: set[Any] = set()
        for key_node, _ in node.value:
            key = self.construct_object(key_node, deep=True)
            if key in seen:
                raise CopyKeyError(f"문구표에 중복 키가 있어요: {key}")
            seen.add(key)
        mapping: dict[Any, Any] = super().construct_mapping(node, deep=deep)
        return mapping


def _flatten(prefix: str, node: Any, out: dict[str, str]) -> None:
    if isinstance(node, Mapping):
        for key, value in node.items():
            _flatten(f"{prefix}.{key}" if prefix else str(key), value, out)
        return
    if prefix in out:
        raise CopyKeyError(f"문구표에 중복 키가 있어요: {prefix}")
    out[prefix] = "" if node is None else str(node)


class Copy:
    """평탄화된 문구표. 키로 문장을 찾고 `{이름}` 자리 표시자를 치환한다."""

    def __init__(self, sentences: Mapping[str, str]) -> None:
        self._sentences = dict(sentences)

    def keys(self) -> frozenset[str]:
        return frozenset(self._sentences)

    def get(self, key: str, **params: str) -> str:
        try:
            sentence = self._sentences[key]
        except KeyError:
            raise CopyKeyError(f"문구표에 없는 키예요: {key}") from None

        def replace(match: re.Match[str]) -> str:
            name = match.group(1)
            if name not in params:
                raise ValueError(f"문구 {key}의 자리 표시자 {name} 값이 없어요")
            return params[name]

        return _PLACEHOLDER.sub(replace, sentence)


def load_copy(path: Path | None = None) -> Copy:
    """문구표를 읽고 필수 키를 검증한다. 경로를 안 주면 환경변수, 그다음 저장소 루트 파일을 쓴다."""
    if path is None:
        path = Path(os.environ.get(ENV_PATH) or DEFAULT_PATH)
    try:
        raw = yaml.load(path.read_text(encoding="utf-8"), Loader=_UniqueKeyLoader)
    except (OSError, yaml.YAMLError) as error:
        raise CopyKeyError(f"문구표를 읽지 못했어요: {path}") from error
    if not isinstance(raw, Mapping):
        raise CopyKeyError(f"문구표 형식이 올바르지 않아요: {path}")

    sentences: dict[str, str] = {}
    _flatten("", raw, sentences)
    blank = sorted(key for key, value in sentences.items() if not value.strip())
    if blank:
        raise CopyKeyError(f"문구표에 빈 값이 있어요: {', '.join(blank)}")
    missing = sorted(REQUIRED_KEYS - sentences.keys())
    if missing:
        raise CopyKeyError(f"문구표에 없는 키가 있어요: {', '.join(missing)}")
    return Copy(sentences)
