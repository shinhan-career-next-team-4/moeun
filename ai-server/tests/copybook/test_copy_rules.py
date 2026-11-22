"""문구표 헤더의 규칙을 코드로 확인한다(#59, CP-05, D-066). 헤더: "문체는 해요체. 면책(disclaimer)만 격식체 문장을 섞는다."

이 파일은 문구표 값을 직접 읽는다. 로더(app.core.copy)를 거치지 않아 로더의 검증과 독립이다.
"""

import re

import yaml

from app.core.copy import load_copy
from app.llm.assemble import _UNCHANGEABLE_ANCHOR
from tests.paths import COPY_FILE

FORMAL_ENDING = re.compile(r"(합니다|습니다|입니다|십시오|바랍니다)")


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
