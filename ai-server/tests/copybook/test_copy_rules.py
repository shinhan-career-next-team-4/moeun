"""문구표 헤더의 규칙을 코드로 확인한다(#59, CP-05, D-066). 헤더: "문체는 해요체. 면책(disclaimer)만 격식체 문장을 섞는다."

말투 검사는 문구표 YAML을 직접 읽어 로더(app.core.copy)와 독립이다. 앵커 검사만 로더로 문장을 읽는다.
"""

import re

import yaml

from app.core.copy import load_copy
from app.llm.assemble import _UNCHANGEABLE_ANCHOR
from tests.paths import COPY_FILE

FORMAL_ENDING = re.compile(r"(니다|니까|십시오)")   # ~합니다·~됩니다·~드립니다·~있습니까·~하십시오


def flatten(prefix: str, node: object, out: dict[str, str]) -> dict[str, str]:
    if isinstance(node, dict):
        for key, value in node.items():
            flatten(f"{prefix}.{key}" if prefix else str(key), value, out)
    else:
        out[prefix] = str(node)
    return out


def sentences() -> dict[str, str]:
    raw = yaml.safe_load(COPY_FILE.read_text(encoding="utf-8"))
    return flatten("", raw, {})


def test_only_the_disclaimer_uses_formal_endings():
    formal = sorted(key for key, value in sentences().items() if FORMAL_ENDING.search(value))

    assert formal == ["disclaimer"], formal


def test_fixed_copy_anchor_still_appears_in_the_copy_sentence():
    """assemble.py의 감지 앵커는 문구표의 고정 문장 일부를 그대로 적어 둔 것이다. 문구표를 고치면 앵커가 어긋난다."""
    assert _UNCHANGEABLE_ANCHOR in load_copy().get("chat.unchangeableCondition")
