"""대화로 되돌리기 평가 세트를 실제 LLM으로 돌린다(Issue #419). 모델만 실제이고 Spring 계산은 fake다.

표현마다(`REVERT_EXPRESSIONS`, 20개 이상) 변경 2개(비중 → 월 저축액) 상태에서 모델이 revert_conditions를 부르는지,
고른 범위(scope, condition)가 기대와 같은지 센다. 서버가 표현으로 바로잡은 최종 범위는 늘 기대와 같아야 한다.
비용이 들어 기본 `pytest`에서는 건너뛴다.
    MOEUN_RUN_LLM=1 uv run pytest tests/scenarios/test_revert_scenarios.py -s
"""

import os
from typing import Any

import anthropic
import pytest

from app.core.config import get_settings
from app.llm.agent import ChatAgent
from app.llm.revert import REVERT_EXPRESSIONS, camel_of
from app.schemas import ChatRequest
from tests.scenarios.test_demo_scenarios import PLAN, NoDocs, ScenarioCalc

pytestmark = pytest.mark.skipif(
    os.environ.get("MOEUN_RUN_LLM") != "1" or get_settings().anthropic_api_key is None,
    reason="실제 LLM 시나리오: MOEUN_RUN_LLM=1과 ANTHROPIC_API_KEY가 필요하다",
)

TWO_CHANGES = [
    {"field": "savingsRatioPct", "before": 50, "after": 30, "messageId": 10},
    {"field": "monthlySavingAmount", "before": 300000, "after": 1500000, "messageId": 12},
]
# 조건 이름 표현 중 변경 목록에 없는 조건(금리·기간·목표 금액·종목)은 "바꾼 적 없다"로 끝나 범위를 남기지 않는다
IN_LIST = {"savings_ratio_pct", "monthly_saving_amount"}


def _agent() -> tuple[ChatAgent, list[dict[str, Any]]]:
    settings = get_settings()
    client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key)
    agent = ChatAgent(client, ScenarioCalc(), NoDocs(), model=settings.anthropic_model,
                      effort=settings.anthropic_effort, max_rounds=settings.max_tool_rounds)
    calls: list[dict[str, Any]] = []
    handler = agent._tools["revert_conditions"]

    async def spy(turn: Any, args: dict[str, Any]) -> str:
        calls.append(args)
        return await handler(turn, args)

    agent._tools["revert_conditions"] = spy
    return agent, calls


@pytest.mark.parametrize("text", sorted(REVERT_EXPRESSIONS))
async def test_model_picks_expected_revert_call(text: str) -> None:
    agent, calls = _agent()
    req = ChatRequest(memberId=1, messageId=30, mode="QUESTION", text=text, baselineScenario="AVG",
                      plan_context=PLAN, accumulatedChanges=TWO_CHANGES)
    resp = await agent.run(req)

    scope, condition = REVERT_EXPRESSIONS[text]
    assert calls, f"{text!r}: revert_conditions를 부르지 않았다"
    chosen = (calls[0].get("scope"), calls[0].get("condition"))
    print(f"{text!r}: model={chosen} expected={(scope, condition)}")
    if scope == "condition" and condition not in IN_LIST:
        assert resp.revert is None   # 바꾼 적 없는 조건은 상태를 바꾸지 않는다
    else:
        expected_field = camel_of(condition) if condition else None
        assert resp.revert is not None and (resp.revert.scope, resp.revert.condition) == (scope, expected_field)
