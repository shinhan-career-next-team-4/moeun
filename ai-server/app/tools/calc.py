from typing import Any

import httpx

from app.schemas import ConditionChanges, Scenario


class CalcError(Exception):
    """Spring이 계산을 거절했다(검증 실패 등). 모델에게 is_error 결과로 돌려준다."""


class CalcClient:
    """금융 계산은 Spring 계산 엔진만 한다(NFR-INT-01). AI는 이 도구로 결과를 받아 설명만 한다."""

    def __init__(self, http: httpx.AsyncClient, internal_api_key: str):
        self._http = http
        self._key = internal_api_key

    async def recalculate(self, member_id: int, baseline: Scenario, changes: ConditionChanges) -> dict[str, Any]:
        resp = await self._http.post(
            "/internal/calc",
            headers={"X-Internal-Api-Key": self._key},
            json={
                "memberId": member_id,
                "baselineScenario": baseline.value,
                "conditionChanges": changes.model_dump(by_alias=True, exclude_none=True),
            },
        )
        if resp.status_code in (400, 409, 422):
            raise CalcError(resp.text[:500])
        resp.raise_for_status()
        body: dict[str, Any] = resp.json()
        if not isinstance(body.get("calcRunId"), int):
            raise CalcError("calc response has no calcRunId")
        return body
