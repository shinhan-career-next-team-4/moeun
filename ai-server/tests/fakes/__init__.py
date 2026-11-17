import copy
import json
from pathlib import Path
from typing import Any

from app.schemas import ConditionChanges, Scenario

REPO = Path(__file__).resolve().parents[3]
SUCCESS_RESPONSE = REPO / "docs" / "api" / "examples" / "internal-calc" / "success-response.json"


class FakeCalc:
    """Calculator 대역. 기본 응답은 internal-calc 부록 A-1 fixture이고 호출마다 독립 복사본을 준다(G-4)."""

    def __init__(self, response: dict[str, Any] | None = None, error: Exception | None = None):
        self._response = response if response is not None else json.loads(SUCCESS_RESPONSE.read_text(encoding="utf-8"))
        self._error = error
        self.calls: list[dict[str, Any]] = []   # 계산기에 보낸 changes.changed() (snake_case)

    async def recalculate(self, member_id: int, baseline: Scenario, changes: ConditionChanges) -> dict[str, Any]:
        self.calls.append(changes.changed())
        if self._error is not None:
            raise self._error
        return copy.deepcopy(self._response)
