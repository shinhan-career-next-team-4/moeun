"""문구표 로더 테스트(Issue #18). 실제 문구표(저장소 루트 copy/copy.ko.yml)를 읽는다.

backend의 CopyBookTest가 같은 문장을 같은 키로 단언한다(Spring과 ai-server가 같은 문구를 읽는다).
음성 테스트는 문구표를 임시 디렉터리로 복사해 키를 지우거나 비운다.
"""

from pathlib import Path
from typing import Any

import pytest
import yaml

from app.core.copy import Copy, CopyKeyError, load_copy

COPY_PATH = Path(__file__).resolve().parents[2] / "copy" / "copy.ko.yml"

CONDITION_CODES = ["monthlySavingAmount", "savingsRatioPct", "savingsRateInputPct", "targetPeriodMonths"]
ERROR_CODES = [
    "VALIDATION_FAILED",
    "FIELD_NOT_CHANGEABLE",
    "NO_PLAN",
    "ALREADY_EXCEEDED",
    "MALFORMED_REQUEST",
    "INVALID_INTERNAL_KEY",
    "CALC_FAILED",
]
ACHIEVEMENT_CHANGES = ["STAYS_ACHIEVED", "STAYS_MISSED", "MISS_TO_ACHIEVE", "ACHIEVE_TO_MISS"]
EXCLUDED_CODES = ["ETF_TOTAL_EXPENSE", "EXCHANGE_RATE", "ETF_DISTRIBUTION", "HELD_ASSET_RETURN", "SAVINGS_REDEPOSIT"]
SCENARIO_METHOD_CODES = ["CAGR", "ROLLING_DISTRIBUTION"]
GOAL_CODES = ["HOUSE", "CAR", "WEDDING", "EDUCATION", "TRAVEL", "EMERGENCY_FUND", "CUSTOM"]  # D-075

FIXED_KEYS = [
    "outOfScope",
    "outOfScope.tax",
    "disclaimer",
    "tax.note",
    "chat.unchangeableCondition",
    "chat.noPlan",
    "chat.alreadyExceeded",
    "chat.failed",
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
]

# 필수 키 정본 목록. backend의 ExcludedItem·ScenarioMethod enum 코드와 같은 목록이다.
REQUIRED_KEYS = [
    *FIXED_KEYS,
    *(f"excluded.{code}" for code in EXCLUDED_CODES),
    *(f"scenarioMethod.{code}" for code in SCENARIO_METHOD_CODES),
    *(f"goal.{code}" for code in GOAL_CODES),
    *(f"condition.{code}" for code in CONDITION_CODES),
    *(f"chat.exampleQuestion.{code}" for code in CONDITION_CODES),
    *(f"error.title.{code}" for code in ERROR_CODES),
    *(f"achievementChange.{value}" for value in ACHIEVEMENT_CHANGES),
]

# 글자 그대로 일치해야 하는 문장: (키, 치환 값, 기대 문장)
VERBATIM: list[tuple[str, dict[str, str], str]] = [
    ("excluded.EXCHANGE_RATE", {}, "환율 변동은 반영하지 않았어요"),
    (
        "chat.unchangeableCondition",
        {},
        "목표 금액은 입력 수정에서 바꿀 수 있어요. 대화로는 월 저축액·비중·적금 금리·기한을 바꿔 볼 수 있어요",
    ),
    ("chat.noPlan", {}, "먼저 목표와 조건을 입력해 계획을 만들어 주세요"),
    ("template.ratio", {"a": "60", "b": "40"}, "적금 60 : ETF 40"),
    ("chat.exampleQuestion.monthlySavingAmount", {}, "월 저축액을 바꿔 보고 싶어요"),
    ("condition.monthlySavingAmount", {}, "월 저축액"),
    ("condition.savingsRatioPct", {}, "저축 비중"),
    ("condition.savingsRateInputPct", {}, "적금 금리"),
    ("condition.targetPeriodMonths", {}, "목표 기간"),
    ("toast.applyStaleComparison", {}, "가장 최근 비교 결과만 적용할 수 있어요"),
    ("toast.applyPlanChanged", {}, "계획이 다른 곳에서 바뀌었어요"),
    ("toast.applyNotApplicable", {}, "지금 기준으로는 적용할 수 없는 값이에요"),
    ("toast.applyFailed", {}, "적용하지 못했어요"),
    ("comparison.loadFailed", {}, "비교 결과를 불러오지 못했어요"),
    ("unit.manwon", {"v": "500"}, "500만원"),
    ("unit.won", {"v": "0"}, "0원"),
    ("unit.month", {"y": "2", "m": "6"}, "2년 6개월"),
    ("unit.pct", {"v": "3.5"}, "연 3.5%"),
    ("unit.char", {"n": "100"}, "100자"),
    ("goal.HOUSE", {}, "내 집 마련"),
    ("goal.CAR", {}, "자동차"),
    ("goal.WEDDING", {}, "결혼"),
    ("goal.EDUCATION", {}, "교육"),
    ("goal.TRAVEL", {}, "여행"),
    ("goal.EMERGENCY_FUND", {}, "비상금"),
    ("goal.CUSTOM", {}, "직접 입력"),
]

# 문장에 들어 있어야 하는 조각: (키, 치환 값, 포함할 조각들)
FRAGMENTS: list[tuple[str, dict[str, str], list[str]]] = [
    ("outOfScope", {}, ["이 질문은 모은에서 다루지 않아요"]),
    ("outOfScope.tax", {}, ["전문가"]),
    ("chat.sendFailed", {}, ["보내지 못했어요"]),
    ("chat.alreadyExceeded", {}, ["초과", "목표 금액은 입력 수정에서 바꿀 수 있어요"]),
    ("disclaimer", {}, ["AI 설명은 참고용이며 계산 결과가 기준이에요", "과거"]),
    ("tax.note", {}, ["일반 과세", "비과세", "세법"]),
    ("result.alreadyExceeded", {"goalAmount": "GOAL_X", "heldAssets": "HELD_Y"}, ["GOAL_X", "HELD_Y"]),
    ("input.heldAssetsRecheck", {"calcStartMonth": "MONTH_Z"}, ["MONTH_Z"]),
    ("validation.OUT_OF_RANGE", {"min": "MIN_A", "max": "MAX_B"}, ["MIN_A", "MAX_B"]),
    ("validation.TOO_MANY_DECIMALS", {"scale": "SCALE_C"}, ["SCALE_C"]),
]


def read_raw(path: Path = COPY_PATH) -> dict[str, str]:
    """실제 문구표를 키 평탄화한 원본 값으로 읽는다(로더를 거치지 않는 픽스처 재료)."""
    flat: dict[str, str] = {}

    def walk(prefix: str, node: Any) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                walk(f"{prefix}.{key}" if prefix else str(key), value)
        elif node is not None:
            flat[prefix] = str(node)

    walk("", yaml.safe_load(path.read_text(encoding="utf-8")))
    return flat


def write_copy(directory: Path, flat: dict[str, str], extra_yaml: str = "") -> Path:
    file = directory / "copy.ko.yml"
    dumped = yaml.safe_dump(flat, allow_unicode=True, width=10**6, sort_keys=False)
    file.write_text(dumped + extra_yaml, encoding="utf-8")
    return file


@pytest.fixture(scope="module")
def copy() -> Copy:
    return load_copy(COPY_PATH)


def test_loads_real_copy_and_its_copy_the_same_way(tmp_path: Path, copy: Copy) -> None:
    """CP-05. 픽스처 검증: 복사본이 실제 문구표와 같은 키로 읽힌다."""
    assert load_copy(write_copy(tmp_path, read_raw())).keys() == copy.keys()


def test_keys_expose_all_flattened_keys(copy: Copy) -> None:
    """CP-05. keys()는 평탄화된 키 전체를 돌려주고 필수 키를 모두 포함한다."""
    assert isinstance(copy.keys(), frozenset)
    assert copy.keys() == frozenset(read_raw())
    assert set(REQUIRED_KEYS) <= copy.keys()


@pytest.mark.parametrize(("key", "params", "expected"), VERBATIM, ids=[v[0] for v in VERBATIM])
def test_reads_fixed_sentence_verbatim(copy: Copy, key: str, params: dict[str, str], expected: str) -> None:
    """Spring과 ai-server가 같은 키로 같은 문구를 읽는다(backend CopyBookTest와 같은 문장)."""
    assert copy.get(key, **params) == expected


@pytest.mark.parametrize(("key", "params", "fragments"), FRAGMENTS, ids=[f[0] for f in FRAGMENTS])
def test_sentence_contains_required_fragments(copy: Copy, key: str, params: dict[str, str], fragments: list[str]) -> None:
    """D-056, D-066, D-067, D-073, C12a-7-1. 요구한 내용과 자리 표시자를 담고 치환 후 중괄호가 남지 않는다."""
    sentence = copy.get(key, **params)
    for fragment in fragments:
        assert fragment in sentence
    assert "{" not in sentence
    assert "}" not in sentence


def test_tax_note_guides_to_professional(copy: Copy) -> None:
    """D-067. 금융기관 또는 세무 전문가 확인 안내를 담는다."""
    note = copy.get("tax.note")
    assert "금융기관" in note or "세무 전문가" in note


def test_unchangeable_condition_is_separate_from_out_of_scope(copy: Copy) -> None:
    """D-056. 대화로 못 바꾸는 조건 안내는 범위 밖 안내와 키도 값도 다르다."""
    assert "chat.unchangeableCondition" != "outOfScope"
    assert copy.get("chat.unchangeableCondition") != copy.get("outOfScope")


def test_tax_out_of_scope_is_separate_from_general(copy: Copy) -> None:
    """D-073. 범위 밖 세금 안내는 일반 범위 밖 안내와 다른 키·값이다."""
    assert {"outOfScope", "outOfScope.tax"} <= copy.keys()
    assert copy.get("outOfScope.tax") != copy.get("outOfScope")


def test_result_and_chat_already_exceeded_are_not_merged(copy: Copy) -> None:
    """C12a-7-1, D-072. 결과 화면 초과 안내와 대화창 초과 안내는 합치지 않는다."""
    assert {"result.alreadyExceeded", "chat.alreadyExceeded"} <= copy.keys()
    assert copy.get("result.alreadyExceeded", goalAmount="1", heldAssets="2") != copy.get("chat.alreadyExceeded")


def test_has_single_one_line_disclaimer(copy: Copy) -> None:
    """D-066. 면책은 disclaimer 키 하나이고 chat.disclaimer는 없으며 한 줄이다."""
    keys = copy.keys()
    assert "disclaimer" in keys
    assert "chat.disclaimer" not in keys
    assert "\n" not in copy.get("disclaimer")
    with pytest.raises(CopyKeyError):
        copy.get("chat.disclaimer")


@pytest.mark.parametrize("code", EXCLUDED_CODES)
def test_has_sentence_for_every_excluded_item(copy: Copy, code: str) -> None:
    """G-14. ExcludedItem 코드마다 비어 있지 않은 문장이 있다."""
    assert copy.get(f"excluded.{code}").strip()


@pytest.mark.parametrize("code", SCENARIO_METHOD_CODES)
def test_has_sentence_for_every_scenario_method(copy: Copy, code: str) -> None:
    """G-14. ScenarioMethod 코드마다 비어 있지 않은 문장이 있다."""
    assert copy.get(f"scenarioMethod.{code}").strip()


@pytest.mark.parametrize(
    "key",
    [
        *(f"goal.{c}" for c in GOAL_CODES),
        *(f"error.title.{c}" for c in ERROR_CODES),
        *(f"achievementChange.{v}" for v in ACHIEVEMENT_CHANGES),
        *(f"scenario.{c}" for c in ("GOOD", "AVG", "BAD")),
        *(f"template.{n}" for n in ("achieved", "missed", "increase", "decrease", "unchanged")),
    ],
)
def test_code_keyed_sentences_are_not_blank(copy: Copy, key: str) -> None:
    """D-075, 오류 title 7개, achievementChange 4값, 시나리오 3개, 표시 템플릿."""
    assert copy.get(key).strip()


def test_copy_has_no_recommend_word() -> None:
    """CF-07, D-064. 문구표 파일 전체에 '추천'이 없다(질문 예시 버튼 포함)."""
    assert "추천" not in COPY_PATH.read_text(encoding="utf-8")


@pytest.mark.parametrize("code", CONDITION_CODES)
def test_example_questions_only_pick_a_condition(copy: Copy, code: str) -> None:
    """D-064. 조건 4개 각각 있고 숫자·%·늘·줄 같은 값·변화량 표현이 없다."""
    sentence = copy.get(f"chat.exampleQuestion.{code}")
    assert sentence.strip()
    assert not any(ch.isdigit() for ch in sentence)
    for banned in ("%", "늘", "줄", "추천"):
        assert banned not in sentence


def test_unknown_key_raises_copy_key_error(copy: Copy) -> None:
    """CP-05. 없는 키는 빈 문구가 아니라 CopyKeyError."""
    with pytest.raises(CopyKeyError):
        copy.get("no.such.key")
    with pytest.raises(CopyKeyError):
        copy.get("no.such.key", v="1")


@pytest.mark.parametrize("key", REQUIRED_KEYS)
def test_load_fails_when_required_key_is_missing(tmp_path: Path, key: str) -> None:
    """CP-05. 필수 키가 하나라도 없으면 로드가 실패한다."""
    flat = read_raw()
    flat.pop(key)
    with pytest.raises(CopyKeyError):
        load_copy(write_copy(tmp_path, flat))


@pytest.mark.parametrize("key", REQUIRED_KEYS)
def test_load_fails_when_required_value_is_empty(tmp_path: Path, key: str) -> None:
    """CP-05. 필수 키의 값이 비어 있으면 로드가 실패한다(조용히 빈 문구가 되지 않음)."""
    flat = read_raw()
    flat[key] = ""
    with pytest.raises(CopyKeyError):
        load_copy(write_copy(tmp_path, flat))


def test_load_fails_when_required_value_is_blank(tmp_path: Path) -> None:
    """CP-05. 공백뿐인 값도 빈 값으로 본다."""
    flat = read_raw()
    flat["excluded.EXCHANGE_RATE"] = "   "
    with pytest.raises(CopyKeyError):
        load_copy(write_copy(tmp_path, flat))


def test_load_fails_on_duplicate_key(tmp_path: Path) -> None:
    """CP-05. 같은 키가 두 번 있으면 로드가 실패한다."""
    path = write_copy(tmp_path, read_raw(), '"disclaimer": "다시 쓴 면책"\n')
    with pytest.raises(Exception):  # noqa: B017  중복 키 예외 타입은 계약에 없다(CopyKeyError 또는 YAML 예외)
        load_copy(path)


def test_load_fails_when_flattened_keys_collide(tmp_path: Path) -> None:
    """CP-05. 중첩 키를 펼친 결과가 다른 키와 겹치면 로드가 실패한다."""
    path = write_copy(tmp_path, read_raw(), 'excluded:\n  EXCHANGE_RATE: "겹치는 문장"\n')
    with pytest.raises(Exception):  # noqa: B017  예외 타입은 계약에 없다
        load_copy(path)


def test_load_fails_on_malformed_yaml(tmp_path: Path) -> None:
    """예외. YAML 문법이 잘못되면 로드가 실패한다."""
    path = tmp_path / "copy.ko.yml"
    path.write_text("disclaimer: [열리고 닫히지 않은 목록\n", encoding="utf-8")
    with pytest.raises(Exception):  # noqa: B017  예외 타입은 계약에 없다
        load_copy(path)


def test_default_path_is_repo_root_copy(monkeypatch: pytest.MonkeyPatch, copy: Copy) -> None:
    """경로 인자와 환경변수가 없으면 저장소 루트 copy/copy.ko.yml을 읽는다."""
    monkeypatch.delenv("MOEUN_COPY_PATH", raising=False)
    assert load_copy().keys() == copy.keys()
    assert load_copy().get("excluded.EXCHANGE_RATE") == "환율 변동은 반영하지 않았어요"


def test_env_var_overrides_default_path(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    """MOEUN_COPY_PATH가 기본 경로를 덮어쓴다."""
    flat = read_raw()
    flat["goal.HOUSE"] = "환경변수로 바꾼 문구"
    monkeypatch.setenv("MOEUN_COPY_PATH", str(write_copy(tmp_path, flat)))
    assert load_copy().get("goal.HOUSE") == "환경변수로 바꾼 문구"


def test_env_var_path_is_validated_too(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    """MOEUN_COPY_PATH로 가리킨 파일도 필수 키 검증을 받는다."""
    flat = read_raw()
    flat.pop("chat.noPlan")
    monkeypatch.setenv("MOEUN_COPY_PATH", str(write_copy(tmp_path, flat)))
    with pytest.raises(CopyKeyError):
        load_copy()


def test_substitutes_placeholders(copy: Copy) -> None:
    """치환. 자리 표시자를 값으로 바꾼다."""
    assert copy.get("unit.month", y="1", m="0") == "1년 0개월"
    assert copy.get("unit.manwon", v="1,000") == "1,000만원"


def test_missing_placeholder_value_raises_value_error(copy: Copy) -> None:
    """치환 값이 하나라도 빠지면 CopyKeyError가 아니라 ValueError다."""
    for key, params in (
        ("unit.month", {"y": "1"}),
        ("unit.manwon", {}),
        ("validation.OUT_OF_RANGE", {"min": "1"}),
    ):
        with pytest.raises(ValueError) as exc_info:
            copy.get(key, **params)
        assert not isinstance(exc_info.value, CopyKeyError)
