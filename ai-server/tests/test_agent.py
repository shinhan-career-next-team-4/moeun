import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock

from app.llm.agent import AgentError, ChatAgent
from app.schemas import ChatRequest, DocType, RetrievedChunk


def text_resp(payload: dict) -> SimpleNamespace:
    block = BetaTextBlock(type="text", text=json.dumps(payload))
    return SimpleNamespace(stop_reason="end_turn", content=[block])


def tool_resp(name: str, input_: dict, id_: str = "tu_1") -> SimpleNamespace:
    block = BetaToolUseBlock(type="tool_use", id=id_, name=name, input=input_)
    return SimpleNamespace(stop_reason="tool_use", content=[block])


class FakeClaude:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs):
        self.calls.append(kwargs)
        return self.responses.pop(0)


class FakeCalc:
    def __init__(self):
        self.calls = []

    async def recalculate(self, member_id, baseline, changes):
        self.calls.append(changes.changed())
        return {"calcRunId": 77, "goodCase": {"finalAmount": 10_500_000}}


class FakeRetriever:
    async def search(self, query, doc_type=None, top_k=None):
        return [
            RetrievedChunk(chunk_id=11, content="환율 위험...", title="A", source_url="u", doc_type=DocType.DISCLOSURE,
                           org_name="A운용", distance=0.1),
            RetrievedChunk(chunk_id=12, content="지수 하락...", title="B", source_url="u2", doc_type=DocType.DISCLOSURE,
                           org_name="B운용", distance=0.2),
        ]


NULL_CHANGES = dict.fromkeys(
    ["monthly_saving_amount", "savings_ratio_pct", "savings_rate_input_pct", "target_period_months"]
)


def make_agent(claude, calc=None):
    return ChatAgent(claude, calc or FakeCalc(), FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4)


def question(text="월 50만원으로 바꾸면?", **kw):
    return ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG", **kw)


async def test_recalc_sets_type_and_changes_from_tool_not_model():
    claude = FakeClaude([
        tool_resp("recalculate", {**NULL_CHANGES, "monthly_saving_amount": 500_000}),
        text_resp({"message_type": "ANSWER", "content": "월 50만원이면...", "citation_chunk_ids": []}),
    ])
    calc = FakeCalc()
    resp = await make_agent(claude, calc).run(question())

    assert calc.calls == [{"monthly_saving_amount": 500_000}]
    assert resp.message_type == "RECALC_RESULT"
    assert resp.calc_run_id == 77
    assert resp.condition_changes == {"monthly_saving_amount": 500_000}
    # 도구 결과는 다음 요청의 user 메시지로 전달된다
    last = claude.calls[1]["messages"][-1]
    assert last["role"] == "user" and last["content"][0]["tool_use_id"] == "tu_1"


async def test_citations_limited_to_retrieved_chunks():
    claude = FakeClaude([
        tool_resp("search_documents", {"query": "ETF 위험", "doc_type": "DISCLOSURE"}),
        text_resp({"message_type": "ANSWER", "content": "위험은...", "citation_chunk_ids": [12, 999, 11, 12]}),
    ])
    resp = await make_agent(claude).run(question("ETF 위험이 뭐야?"))
    assert resp.citation_chunk_ids == [12, 11]


async def test_empty_recalc_returns_tool_error_to_model():
    claude = FakeClaude([
        tool_resp("recalculate", NULL_CHANGES),
        text_resp({"message_type": "CLARIFY", "content": "무엇을 바꿀까요?", "citation_chunk_ids": []}),
    ])
    resp = await make_agent(claude).run(question("좀 바꿔줘"))
    tool_result = claude.calls[1]["messages"][-1]["content"][0]
    assert tool_result["is_error"] is True
    assert resp.message_type == "CLARIFY" and resp.calc_run_id is None


async def test_explain_mode_uses_given_calc_run():
    claude = FakeClaude([text_resp({"message_type": "ANSWER", "content": "설명", "citation_chunk_ids": []})])
    plan = json.loads(
        (Path(__file__).resolve().parents[2] / "docs" / "api" / "examples" / "plan-context" / "plan-context-basic.json")
        .read_text(encoding="utf-8")
    )
    req = ChatRequest(memberId=1, messageId=9, mode="EXPLAIN", baselineScenario="GOOD", calcRunId=540, planContext=plan)
    resp = await make_agent(claude).run(req)
    assert (resp.message_type, resp.calc_run_id) == ("EXPLANATION", 540)


async def test_refusal_maps_to_out_of_scope():
    claude = FakeClaude([SimpleNamespace(stop_reason="refusal", content=[])])
    resp = await make_agent(claude).run(question("이 종목 사도 돼?"))
    assert resp.message_type == "OUT_OF_SCOPE" and resp.calc_run_id is None


async def test_question_without_text_rejected():
    with pytest.raises(AgentError):
        await make_agent(FakeClaude([])).run(question(text=None))
