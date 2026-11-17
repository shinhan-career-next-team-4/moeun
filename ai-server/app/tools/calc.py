from typing import Any

import httpx

from app.schemas import ConditionChanges, Scenario

# 모델에게 넘기는 시나리오 줄기. 원 단위 금액 키는 display 밖으로 내보내지 않는다(NFR-INT-01)
_SIDES = ("current", "changed")


class CalcError(Exception):
    """계산을 못 했다(빈 변경 등). 모델에게 is_error 결과로 돌려준다."""


class CalcRejected(Exception):
    """Spring이 422·409로 거절했다. 본문을 파싱한 구조화 오류이며 자르지 않는다(C3-2)."""

    def __init__(self, code: str, errors: list[dict[str, Any]]):
        super().__init__(f"{code}: {errors}")
        self.code = code
        self.errors = errors


class CalcFailed(Exception):
    """계산 서버 쪽 실패. 에이전트가 AgentError(code)로 바꿔 요청을 실패시킨다(FR-AI-04)."""

    def __init__(self, code: str, detail: str = ""):
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


class CalcClient:
    """금융 계산은 Spring 계산 엔진만 한다(NFR-INT-01). AI는 이 도구로 결과를 받아 설명만 한다."""

    def __init__(self, http: httpx.AsyncClient, internal_api_key: str):
        self._http = http
        self._key = internal_api_key

    async def recalculate(self, member_id: int, baseline: Scenario, changes: ConditionChanges) -> dict[str, Any]:
        try:
            resp = await self._http.post(
                "/internal/calc",
                headers={"X-Internal-Api-Key": self._key},
                json={
                    "memberId": member_id,
                    "baselineScenario": baseline.value,
                    "conditionChanges": changes.model_dump(by_alias=True, exclude_none=True),
                },
            )
        except httpx.TransportError as e:   # 연결 실패·타임아웃
            raise CalcFailed("CALC_UNAVAILABLE", type(e).__name__) from e

        match resp.status_code:
            case 200:
                return _select_for_model(_json_object(resp))
            case 400:
                raise CalcFailed("CALC_BAD_REQUEST", resp.text[:200])
            case 401:
                raise CalcFailed("CALC_UNAUTHORIZED")
            case 409 | 422:
                raise _rejected(resp)
            case status:
                raise CalcFailed("CALC_FAILED", f"status {status}")


def _json_object(resp: httpx.Response) -> dict[str, Any]:
    try:
        body = resp.json()
    except ValueError as e:
        raise CalcFailed("CALC_FAILED", f"status {resp.status_code} body is not JSON") from e
    if not isinstance(body, dict):
        raise CalcFailed("CALC_FAILED", f"status {resp.status_code} body is not an object")
    return body


def _rejected(resp: httpx.Response) -> CalcRejected:
    body = _json_object(resp)
    code = body.get("code")
    errors = body.get("errors", [])
    if not isinstance(code, str) or not isinstance(errors, list):
        raise CalcFailed("CALC_FAILED", f"status {resp.status_code} body has no code")
    return CalcRejected(code, errors)


def _select_for_model(body: dict[str, Any]) -> dict[str, Any]:
    """200 응답에서 모델에게 줄 것만 추린다. 금액은 display 문자열로만 나간다(원칙 1)."""
    # bool은 int의 하위 타입이라 따로 거른다
    calc_run_id = body.get("calcRunId")
    if not isinstance(calc_run_id, int) or isinstance(calc_run_id, bool):
        raise CalcFailed("CALC_FAILED", "calcRunId is not an integer")
    try:
        _check_display(body)
        selected: dict[str, Any] = {"calcRunId": calc_run_id}
        for key in ("display", "diff", "conditions"):
            if key in body:
                selected[key] = body[key]
        for side in _SIDES:
            selected[side] = _select_side(body[side])
    except (KeyError, TypeError) as e:
        raise CalcFailed("CALC_FAILED", f"unexpected response shape: {e!r}") from e
    return selected


def _check_display(body: dict[str, Any]) -> None:
    """[전환기] 응답에 있는 시나리오의 afterTaxAsset 표시 문자열이 모두 있어야 비교를 낸다(C2-6)."""
    display = body.get("display")
    if not isinstance(display, dict) or not display:
        raise CalcFailed("CALC_DISPLAY_MISSING", "display is empty")
    for side in _SIDES:
        for item in body[side]["scenarios"]:
            key = f"{side}.{item['scenario']}.afterTaxAsset"
            if key not in display:
                raise CalcFailed("CALC_DISPLAY_MISSING", f"display has no {key}")


def _select_side(side: dict[str, Any]) -> dict[str, Any]:
    selected: dict[str, Any] = {
        "scenarios": [{"scenario": s["scenario"], "isAchieved": s["isAchieved"]} for s in side["scenarios"]]
    }
    if "baselineScenario" in side:
        selected["baselineScenario"] = side["baselineScenario"]
    if "assumptions" in side:
        selected["assumptions"] = {"isDefaultSavingsRate": side["assumptions"]["isDefaultSavingsRate"]}
    return selected
