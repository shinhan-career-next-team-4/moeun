"""Issue #418: 대화 중 바꾼 조건을 다음 질문에도 유지한다(D-103, D-041 일부 변경).

누적 상태(변경 목록)는 Spring이 저장해 요청의 accumulatedChanges로 넘긴다. ai-server는 이번 발화의 값만 도구 인자로 받고,
누적 조건은 /internal/calc의 baselineConditionChanges로 넘겨 계산 엔진이 "직전 누적 계획 대비" 비교를 만든다.
네트워크 없이 FakeClaude·FakeCalc·respx(Spring 대역)로 검증한다. 모델 준수는 수동 평가 몫이다(D-073).
"""

import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
import respx
from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock

from app.llm.agent import ChatAgent
from app.llm.conditions import accumulated_conditions
from app.llm.context import HISTORY_LIMIT, build_messages
from app.llm.prompts import SYSTEM_PROMPT
from app.schemas import ChatRequest, ConditionChanges, DocType, RetrievedChunk, Scenario
from app.tools.calc import CalcClient

REPO = Path(__file__).resolve().parents[2]
PLAN_CONTEXT = json.loads(
    (REPO / "docs" / "api" / "examples" / "plan-context" / "plan-context-basic.json").read_text(encoding="utf-8")
)
CALC_EXAMPLE = json.loads(
    (REPO / "docs" / "api" / "examples" / "internal-calc" / "success-response.json").read_text(encoding="utf-8")
)
NULLS = dict.fromkeys(["monthly_saving_amount", "savings_ratio_pct", "savings_rate_input_pct", "target_period_months"])
BASE = "http://spring.test"
RATIO_30 = {"field": "savingsRatioPct", "before": 50, "after": 30}


def text_resp(message_type: str = "RECALC_RESULT", content: str = "답") -> SimpleNamespace:
    payload = {"message_type": message_type, "content": content, "citation_chunk_ids": []}
    return SimpleNamespace(stop_reason="end_turn", content=[BetaTextBlock(type="text", text=json.dumps(payload))])


def tool_resp(input_: dict[str, Any], id_: str = "tu_1") -> SimpleNamespace:
    block = BetaToolUseBlock(type="tool_use", id=id_, name="recalculate", input=input_)
    return SimpleNamespace(stop_reason="tool_use", content=[block])


class FakeClaude:
    def __init__(self, responses: list[Any]):
        self.responses = list(responses)
        self.calls: list[dict[str, Any]] = []
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs: Any) -> Any:
        self.calls.append(kwargs)
        return self.responses.pop(0)


class FakeCalc:
    """비교 기준 인자(baseline_changes)를 받았는지까지 기록한다."""

    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    async def recalculate(self, member_id: int, baseline: Scenario, changes: ConditionChanges, **kwargs: Any):
        self.calls.append({"changes": changes.changed(), **kwargs})
        return {"calcRunId": 77}


class FakeRetriever:
    async def search(self, query: str, doc_type: DocType | None = None, top_k: int | None = None) -> list[RetrievedChunk]:
        return []


def make_agent(claude: FakeClaude, calc: Any) -> ChatAgent:
    return ChatAgent(claude, calc, FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4)


def question(text: str, **kw: Any) -> ChatRequest:
    return ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG", **kw)


# ---- 요청 모양 ----

def test_request_reads_accumulated_changes_in_order():
    req = ChatRequest.model_validate({
        "memberId": 1, "messageId": 9, "mode": "QUESTION", "text": "월 150만원으로 늘리면?", "baselineScenario": "AVG",
        "accumulatedChanges": [RATIO_30, {"field": "monthlySavingAmount", "before": 300000, "after": 1500000}],
    })
    assert [(c.field, c.before, c.after) for c in req.accumulated_changes] == [
        ("savingsRatioPct", 50, 30), ("monthlySavingAmount", 300000, 1500000),
    ]


def test_request_without_accumulated_changes_is_empty():
    assert question("월 50만원으로 바꾸면?").accumulated_changes == []


# ---- 변경 목록 → 최종 조건 ----

def test_accumulated_conditions_folds_list_with_later_change_winning():
    """같은 조건을 두 번 바꾸면(150만 → 200만) 최종 값은 200만이고, 다른 조건은 그대로 남는다."""
    req = question("기간은 4년으로", accumulatedChanges=[
        RATIO_30,
        {"field": "monthlySavingAmount", "before": 300000, "after": 1500000},
        {"field": "monthlySavingAmount", "before": 1500000, "after": 2000000},
    ])
    assert accumulated_conditions(req.accumulated_changes).changed() == {
        "savings_ratio_pct": 30, "monthly_saving_amount": 2000000,
    }


def test_accumulated_conditions_of_empty_list_is_empty():
    assert accumulated_conditions([]).changed() == {}


# ---- 재계산: 이번 발화 값 + 누적 조건(비교 기준) ----

async def test_recalculates_this_utterance_on_top_of_accumulated_conditions():
    """비중 30:70으로 바꾼 대화에서 '월 150만원으로 늘리면?' → 이번 변경은 월 저축액만, 비교 기준은 비중 30."""
    claude = FakeClaude([tool_resp({**NULLS, "monthly_saving_amount": 1_500_000}), text_resp()])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(
        question("월 150만원으로 늘리면?", plan_context=PLAN_CONTEXT, accumulatedChanges=[RATIO_30])
    )
    assert calc.calls == [{
        "changes": {"monthly_saving_amount": 1_500_000},
        "baseline_changes": ConditionChanges(savings_ratio_pct=30),
    }]
    # 응답의 condition_changes는 이번 변경만이다. 누적은 Spring의 변경 목록이 맡는다
    assert resp.condition_changes == {"monthly_saving_amount": 1_500_000}


async def test_model_reinjecting_accumulated_value_is_filtered_from_this_turn():
    """모델이 누적 조건(비중 30)을 이번 인자에 다시 넣어도 이번 변경에서는 뺀다. 비교 기준으로는 그대로 쓴다."""
    claude = FakeClaude([
        tool_resp({**NULLS, "monthly_saving_amount": 1_500_000, "savings_ratio_pct": 30}),
        text_resp(),
    ])
    calc = FakeCalc()
    history = [{"role": "user", "content": "적금 30으로 바꾸면?"}, {"role": "assistant", "content": "계산했어요."}]
    await make_agent(claude, calc).run(
        question("월 150만원으로 늘리면?", plan_context=PLAN_CONTEXT, history=history, accumulatedChanges=[RATIO_30])
    )
    assert calc.calls[0]["changes"] == {"monthly_saving_amount": 1_500_000}
    assert calc.calls[0]["baseline_changes"] == ConditionChanges(savings_ratio_pct=30)


async def test_without_accumulated_changes_calc_gets_no_baseline():
    claude = FakeClaude([tool_resp({**NULLS, "monthly_saving_amount": 500_000}), text_resp()])
    calc = FakeCalc()
    await make_agent(claude, calc).run(question("월 50만원으로 바꾸면?", plan_context=PLAN_CONTEXT))
    assert calc.calls == [{"changes": {"monthly_saving_amount": 500_000}}]


async def test_switching_back_to_planned_etf_after_accumulated_etf_is_a_change():
    """누적으로 종목을 069500으로 바꾼 뒤 계획 종목(SP500)으로 되돌리면 같은 종목이 아니라 바꾼 것이다."""
    claude = FakeClaude([tool_resp({**NULLS, "etf_product_code": "SP500"}), text_resp()])
    calc = FakeCalc()
    accumulated = [{"field": "etfProductCode", "before": "SP500", "after": "069500"}]
    await make_agent(claude, calc).run(
        question("S&P500으로 다시 바꾸면?", plan_context=PLAN_CONTEXT, accumulatedChanges=accumulated)
    )
    assert calc.calls[0]["changes"] == {"etf_product_code": "SP500"}


# ---- /internal/calc 본문 ----

@pytest.fixture
async def http():
    async with httpx.AsyncClient(base_url=BASE) as client:
        yield client


@pytest.fixture
def spring():
    with respx.mock(base_url=BASE, assert_all_called=False) as router:
        yield router.post("/internal/calc")


async def test_calc_client_sends_baseline_condition_changes_only_when_given(http, spring):
    route = spring.mock(return_value=httpx.Response(200, json=CALC_EXAMPLE))
    client = CalcClient(http, "k")

    await client.recalculate(7, Scenario.AVG, ConditionChanges(monthly_saving_amount=1))
    await client.recalculate(7, Scenario.AVG, ConditionChanges(monthly_saving_amount=1),
                             baseline_changes=ConditionChanges(savings_ratio_pct=30))
    await client.recalculate(7, Scenario.AVG, ConditionChanges(monthly_saving_amount=1),
                             baseline_changes=ConditionChanges())

    first, second, third = (json.loads(call.request.content) for call in route.calls)
    assert "baselineConditionChanges" not in first
    assert second["baselineConditionChanges"] == {"savingsRatioPct": 30}
    assert "baselineConditionChanges" not in third


# ---- LLM 컨텍스트 ----

def test_context_has_accumulated_changes_in_current_turn():
    req = question("월 150만원으로 늘리면?", plan_context=PLAN_CONTEXT, accumulatedChanges=[RATIO_30])
    current = build_messages(req)[-1]["content"]
    assert "<accumulated_changes>" in current
    assert "savingsRatioPct" in current


def test_context_without_accumulated_changes_has_no_block():
    current = build_messages(question("월 50만원?", plan_context=PLAN_CONTEXT))[-1]["content"]
    assert "<accumulated_changes>" not in current


def test_context_keeps_only_last_ten_history_messages():
    """(예외) 긴 대화여도 대화 원문은 최근 10개(5번 주고받은 분량)만 넣는다."""
    history = []
    for n in range(8):
        history.append({"role": "user", "content": f"질문{n}"})
        history.append({"role": "assistant", "content": f"답{n}"})
    messages = build_messages(question("마지막 질문", plan_context=PLAN_CONTEXT, history=history))
    text = "\n".join(m["content"] for m in messages)
    assert HISTORY_LIMIT == 10
    assert "질문2" not in text and "답2" not in text
    assert "질문3" in text and "답7" in text


def test_system_prompt_describes_accumulation():
    """이번 발화 값만 인자로 넣고, 앞 턴 조건은 서버가 쌓는다(D-103). 모델 준수는 수동 평가(D-073)."""
    assert "<accumulated_changes>" in SYSTEM_PROMPT
    assert "D-103" in SYSTEM_PROMPT
