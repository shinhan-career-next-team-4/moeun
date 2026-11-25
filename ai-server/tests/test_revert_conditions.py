"""Issue #419: 대화로 되돌리기("원래대로", "아까 거 취소", "다 원래대로", "비중은 원래대로").

#418의 변경 목록(accumulatedChanges)에서 되돌릴 항목을 빼고, 남은 조건으로 계산 엔진에 다시 계산을 맡긴다.
LLM은 도구(revert_conditions)와 인자만 고르고, 무엇을 되돌릴지 확정·재계산은 서버가 한다(NFR-INT-01, D-103).
되묻지 않고 가장 그럴듯한 해석으로 바로 실행한다. 표현 해석 규칙(infer_revert)이 모델의 범위를 바로잡는다.
네트워크 없이 FakeClaude·FakeCalc로 검증한다. 실제 모델의 도구 선택은 tests/scenarios(MOEUN_RUN_LLM=1) 몫이다.
"""

import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock

from app.core.copy import load_copy
from app.llm.agent import ChatAgent
from app.llm.prompts import SYSTEM_PROMPT
from app.llm.revert import REVERT_EXPRESSIONS, infer_revert
from app.llm.tools import TOOLS
from app.schemas import ChatRequest, ConditionChanges, DocType, RetrievedChunk, Scenario

REPO = Path(__file__).resolve().parents[2]
PLAN_CONTEXT = json.loads(
    (REPO / "docs" / "api" / "examples" / "plan-context" / "plan-context-basic.json").read_text(encoding="utf-8")
)
RATIO = {"field": "savingsRatioPct", "before": 50, "after": 30, "messageId": 10}
MONTHLY = {"field": "monthlySavingAmount", "before": 300000, "after": 1500000, "messageId": 12}
TWO = [RATIO, MONTHLY]
NAIVE_LAST = {"scope": "last", "condition": None}


def text_resp(message_type: str = "RECALC_RESULT", content: str = "되돌렸어요") -> SimpleNamespace:
    payload = {"message_type": message_type, "content": content, "citation_chunk_ids": []}
    return SimpleNamespace(stop_reason="end_turn", content=[BetaTextBlock(type="text", text=json.dumps(payload))])


def tool_resp(input_: dict[str, Any], name: str = "revert_conditions") -> SimpleNamespace:
    block = BetaToolUseBlock(type="tool_use", id="tu_1", name=name, input=input_)
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
    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    async def recalculate(self, member_id: int, baseline: Scenario, changes: ConditionChanges, **kwargs: Any):
        self.calls.append({"changes": changes.changed(), **kwargs})
        return {"calcRunId": 88, "display": {"conditions.current.monthlySavingAmount": "약 30만원"}}


class FakeRetriever:
    async def search(self, query: str, doc_type: DocType | None = None, top_k: int | None = None) -> list[RetrievedChunk]:
        return []


def make_agent(claude: FakeClaude, calc: Any) -> ChatAgent:
    return ChatAgent(claude, calc, FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4)


def question(text: str, accumulated: list[dict[str, Any]], **kw: Any) -> ChatRequest:
    return ChatRequest(memberId=1, messageId=20, mode="QUESTION", text=text, baselineScenario="AVG",
                       plan_context=PLAN_CONTEXT, accumulatedChanges=accumulated, **kw)


async def run(text: str, accumulated: list[dict[str, Any]], args: dict[str, Any], **kw: Any):
    claude = FakeClaude([tool_resp(args), text_resp()])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question(text, accumulated, **kw))
    return resp, calc, claude


def tool_result(claude: FakeClaude) -> dict[str, Any]:
    content = claude.calls[-1]["messages"][-1]["content"][0]["content"]
    return json.loads(content)


# ---- 도구 정의 ----

def test_revert_tool_is_offered_with_scope_and_condition():
    tool = next(t for t in TOOLS if t["name"] == "revert_conditions")
    props = tool["input_schema"]["properties"]
    assert props["scope"]["enum"] == ["last", "all", "condition"]
    assert "savings_ratio_pct" in props["condition"]["anyOf"][0]["enum"]


# ---- 수용 기준 ----

async def test_last_reverts_only_latest_change_and_keeps_the_rest():
    """변경 2개(비중 → 월 저축액)에서 "원래대로" → 월 저축액만 되돌리고 비중은 남긴 계산을 한다."""
    resp, calc, claude = await run("원래대로", TWO, NAIVE_LAST)
    assert calc.calls == [{"changes": {"savings_ratio_pct": 30}}]   # 남은 조건만, 비교 기준 없이(내 계획 대비)
    assert resp.message_type == "RECALC_RESULT"
    assert resp.condition_changes == {"savings_ratio_pct": 30}
    assert resp.revert is not None and (resp.revert.scope, resp.revert.condition) == ("last", None)
    result = tool_result(claude)
    assert [r["field"] for r in result["reverted"]] == ["monthlySavingAmount"]
    assert [r["field"] for r in result["remaining"]] == ["savingsRatioPct"]


async def test_all_clears_list_and_answers_with_my_plan_without_calc():
    """"다 원래대로" → 계산하지 않고 내 계획 기준으로 답한다(planContext). Spring이 목록을 비운다."""
    claude = FakeClaude([tool_resp({"scope": "all", "condition": None}), text_resp("ANSWER", "내 계획으로 돌렸어요")])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question("다 원래대로", TWO))
    assert calc.calls == []
    assert resp.message_type == "ANSWER"
    assert resp.calc_run_id is None and resp.condition_changes is None
    assert (resp.revert.scope, resp.revert.condition) == ("all", None)
    assert tool_result(claude)["remaining"] == []


async def test_condition_reverts_only_that_condition():
    resp, calc, _ = await run("비중은 원래대로", TWO, {"scope": "condition", "condition": "savings_ratio_pct"})
    assert calc.calls == [{"changes": {"monthly_saving_amount": 1_500_000}}]
    assert (resp.revert.scope, resp.revert.condition) == ("condition", "savingsRatioPct")


async def test_no_after_last_means_all():
    """last를 실행한 직후 "아니 전부" → 모델이 다시 last를 골라도 all로 처리한다."""
    history = [{"role": "user", "content": "원래대로"}, {"role": "assistant", "content": "월 저축액을 되돌렸어요."}]
    resp, calc, _ = await run("아니 전부", [RATIO], NAIVE_LAST, history=history)
    assert calc.calls == []
    assert resp.revert.scope == "all"


async def test_nothing_to_revert_keeps_state():
    """(예외) 변경 0개에서 "취소" → 상태를 바꾸지 않고 "아직 바꾼 조건이 없어요"."""
    claude = FakeClaude([tool_resp(NAIVE_LAST)])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question("취소", []))
    assert calc.calls == []
    assert resp.message_type == "ANSWER"
    assert resp.content == load_copy().get("chat.nothingToRevert") == "아직 바꾼 조건이 없어요"
    assert resp.revert is None


async def test_condition_never_changed_keeps_state():
    """(예외) 바꾼 적 없는 조건("기간은 원래대로") → 바꾼 적 없다고 밝히고 상태를 바꾸지 않는다."""
    claude = FakeClaude([tool_resp({"scope": "condition", "condition": "target_period_months"})])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question("기간은 원래대로", TWO))
    assert calc.calls == []
    assert resp.revert is None
    assert resp.content == load_copy().get("chat.conditionNotChanged").replace("{condition}", "목표 기간")


async def test_same_field_twice_last_returns_to_previous_value():
    """150만 → 200만으로 두 번 바꾼 뒤 "취소" → 200만만 되돌려 150만이 남는다."""
    twice = [MONTHLY, {"field": "monthlySavingAmount", "before": 1500000, "after": 2000000, "messageId": 14}]
    _, calc, _ = await run("취소", twice, NAIVE_LAST)
    assert calc.calls == [{"changes": {"monthly_saving_amount": 1_500_000}}]


async def test_last_reverts_every_change_of_latest_message():
    """한 질문에서 두 조건을 바꿨으면("월 150만원, 기간 4년") "취소"는 그 질문의 변경을 모두 되돌린다."""
    turn = [RATIO, {**MONTHLY, "messageId": 12}, {"field": "targetPeriodMonths", "before": 36, "after": 48,
                                                   "messageId": 12}]
    _, calc, _ = await run("취소", turn, NAIVE_LAST)
    assert calc.calls == [{"changes": {"savings_ratio_pct": 30}}]


async def test_reverts_three_turns_ago_change_in_long_conversation():
    """15턴 넘는 대화(원문은 최근 10개만)에서 3턴 전에 바꾼 비중을 조건 이름으로 되돌린다."""
    history = []
    for n in range(16):
        history.append({"role": "user", "content": f"질문 {n}"})
        history.append({"role": "assistant", "content": f"답 {n}"})
    entries = [RATIO, MONTHLY, {"field": "targetPeriodMonths", "before": 36, "after": 48, "messageId": 14},
               {"field": "goalAmount", "before": 20000000, "after": 30000000, "messageId": 16}]
    resp, calc, claude = await run("아까 바꾼 비중은 원래대로", entries, NAIVE_LAST, history=history)
    assert calc.calls[0]["changes"] == {
        "monthly_saving_amount": 1_500_000, "target_period_months": 48, "goal_amount": 30_000_000,
    }
    assert resp.revert.condition == "savingsRatioPct"
    sent = "\n".join(m["content"] for m in claude.calls[0]["messages"] if isinstance(m["content"], str))
    assert "질문 5" not in sent and "질문 15" in sent


def test_system_prompt_has_revert_examples():
    for phrase in ("revert_conditions", "원래대로", "다 원래대로", "비중은 원래대로", "아니 전부"):
        assert phrase in SYSTEM_PROMPT


# ---- 평가 세트: 되돌리기 표현 20개 이상 × 변경 0/1/2개 ----

STATES = {0: [], 1: [RATIO], 2: TWO}


def test_eval_set_has_at_least_twenty_expressions():
    assert len(REVERT_EXPRESSIONS) >= 20
    assert {scope for scope, _ in REVERT_EXPRESSIONS.values()} == {"last", "all", "condition"}


@pytest.mark.parametrize("text", sorted(REVERT_EXPRESSIONS))
def test_infer_revert_matches_expected_tool_call(text):
    assert infer_revert(text) == REVERT_EXPRESSIONS[text]


@pytest.mark.parametrize("text", ["월 150만원으로 늘리면?", "금리 4%면?", "결과 설명해줘", "기간을 4년으로 바꾸면?"])
def test_infer_revert_ignores_non_revert_utterances(text):
    assert infer_revert(text) is None


def _expected_outcome(text: str, state: list[dict[str, Any]]) -> tuple[str, str | None] | str:
    scope, condition = REVERT_EXPRESSIONS[text]
    if not state:
        return "nothing"
    if scope == "condition":
        camel = ConditionChanges.model_fields[condition].alias  # type: ignore[index]
        if camel not in {e["field"] for e in state}:
            return "notChanged"
        return scope, camel
    return scope, None


@pytest.mark.parametrize("state", sorted(STATES))
@pytest.mark.parametrize("text", sorted(REVERT_EXPRESSIONS))
async def test_eval_server_action_for_each_expression_and_state(text, state):
    """모델이 늘 last로 불러도(가장 단순한 선택) 서버가 표현대로 범위를 바로잡아 실행한다."""
    entries = STATES[state]
    claude = FakeClaude([tool_resp(NAIVE_LAST), text_resp()])
    resp = await make_agent(claude, FakeCalc()).run(question(text, entries))
    expected = _expected_outcome(text, entries)
    copy = load_copy()
    if expected == "nothing":
        assert resp.content == copy.get("chat.nothingToRevert") and resp.revert is None
    elif expected == "notChanged":
        assert resp.revert is None and "바꾼 적이 없어요" in resp.content
    else:
        assert resp.revert is not None and (resp.revert.scope, resp.revert.condition) == expected
