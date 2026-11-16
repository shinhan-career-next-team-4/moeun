import json

import pytest
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from pydantic import SecretStr

from app.llm.agent import FALLBACK_BETA, FINAL_FORMAT, TOOLS, AgentError, ChatAgent, build_chat_model, build_messages
from app.llm.prompts import SYSTEM_PROMPT
from app.schemas import ChatRequest, DocType, RetrievedChunk


def text_resp(payload: dict) -> AIMessage:
    return AIMessage(json.dumps(payload), response_metadata={"stop_reason": "end_turn"})


def tool_resp(name: str, input_: dict, id_: str = "tu_1") -> AIMessage:
    call = {"name": name, "args": input_, "id": id_, "type": "tool_call"}
    return AIMessage("", tool_calls=[call], response_metadata={"stop_reason": "tool_use"})


class FakeClaude:
    """도구와 출력 형식이 묶인 채팅 모델 자리에 끼운다. 호출마다 받은 메시지 목록을 남긴다."""

    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    async def ainvoke(self, input, /):
        self.calls.append({"messages": list(input)})
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
    ["goal_amount", "target_period_months", "held_assets", "monthly_saving_amount",
     "savings_rate_input_pct", "existing_monthly_contribution", "savings_ratio_pct"]
)


def make_agent(claude, calc=None):
    return ChatAgent(claude, calc or FakeCalc(), FakeRetriever(), max_rounds=4)


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
    # 도구 결과는 다음 요청의 메시지로 전달된다(user 메시지로 바뀌는지는 test_request_payload_matches_sdk_loop)
    last = claude.calls[1]["messages"][-1]
    assert isinstance(last, ToolMessage) and last.tool_call_id == "tu_1"


async def test_citations_limited_to_retrieved_chunks():
    claude = FakeClaude([
        tool_resp("search_documents", {"query": "ETF 위험", "doc_type": "DISCLOSURE"}),
        text_resp({"message_type": "ANSWER", "content": "위험은...", "citation_chunk_ids": [12, 999, 11, 12]}),
    ])
    resp = await make_agent(claude).run(question("ETF 위험이 뭐야?"))
    assert resp.citation_chunk_ids == [12, 11]


async def test_parallel_tool_calls_record_server_facts():
    both = AIMessage(
        "",
        tool_calls=[
            {"name": "recalculate", "args": {**NULL_CHANGES, "goal_amount": 20_000_000}, "id": "tu_1", "type": "tool_call"},
            {"name": "search_documents", "args": {"query": "위험", "doc_type": "ANY"}, "id": "tu_2", "type": "tool_call"},
        ],
        response_metadata={"stop_reason": "tool_use"},
    )
    claude = FakeClaude([both, text_resp({"message_type": "ANSWER", "content": "...", "citation_chunk_ids": [11, 99]})])
    resp = await make_agent(claude).run(question("2천만원으로 바꾸면 위험은?"))

    assert (resp.message_type, resp.calc_run_id) == ("RECALC_RESULT", 77)
    assert resp.condition_changes == {"goal_amount": 20_000_000}
    assert resp.citation_chunk_ids == [11]
    assert [m.tool_call_id for m in claude.calls[1]["messages"][-2:]] == ["tu_1", "tu_2"]


async def test_empty_recalc_returns_tool_error_to_model():
    claude = FakeClaude([
        tool_resp("recalculate", NULL_CHANGES),
        text_resp({"message_type": "CLARIFY", "content": "무엇을 바꿀까요?", "citation_chunk_ids": []}),
    ])
    resp = await make_agent(claude).run(question("좀 바꿔줘"))
    tool_result = claude.calls[1]["messages"][-1]
    assert tool_result.status == "error"
    assert resp.message_type == "CLARIFY" and resp.calc_run_id is None


async def test_explain_mode_uses_given_calc_run():
    claude = FakeClaude([text_resp({"message_type": "ANSWER", "content": "설명", "citation_chunk_ids": []})])
    req = ChatRequest(memberId=1, messageId=9, mode="EXPLAIN", baselineScenario="GOOD", calcRunId=5)
    resp = await make_agent(claude).run(req)
    assert (resp.message_type, resp.calc_run_id) == ("EXPLANATION", 5)


async def test_refusal_maps_to_out_of_scope():
    claude = FakeClaude([AIMessage("", response_metadata={"stop_reason": "refusal"})])
    resp = await make_agent(claude).run(question("이 종목 사도 돼?"))
    assert resp.message_type == "OUT_OF_SCOPE" and resp.calc_run_id is None


async def test_question_without_text_rejected():
    with pytest.raises(AgentError):
        await make_agent(FakeClaude([])).run(question(text=None))


async def test_refusal_in_explain_mode_stays_out_of_scope():
    claude = FakeClaude([AIMessage("", response_metadata={"stop_reason": "refusal"})])
    req = ChatRequest(memberId=1, messageId=9, mode="EXPLAIN", baselineScenario="GOOD", calcRunId=5)
    resp = await make_agent(claude).run(req)
    assert (resp.message_type, resp.calc_run_id) == ("OUT_OF_SCOPE", None)


async def test_tool_loop_stops_after_max_rounds():
    search = {"query": "ETF 위험", "doc_type": "ANY"}
    claude = FakeClaude([tool_resp("search_documents", search, id_=f"tu_{i}") for i in range(5)])
    with pytest.raises(AgentError) as e:
        await make_agent(claude).run(question("ETF 위험이 뭐야?"))
    assert e.value.code == "TOO_MANY_ROUNDS"
    assert len(claude.calls) == 4


async def test_bad_output_raises_agent_error():
    claude = FakeClaude([AIMessage("JSON이 아님", response_metadata={"stop_reason": "end_turn"})])
    with pytest.raises(AgentError) as e:
        await make_agent(claude).run(question())
    assert e.value.code == "BAD_OUTPUT"


def test_request_payload_matches_sdk_loop():
    """실제 모델 설정이 SDK 루프가 보내던 요청과 같은지 네트워크 없이 확인한다."""
    bound = build_chat_model(model="claude-opus-5-5", effort="medium", api_key=SecretStr("test-key"))
    messages = [
        *build_messages(question("좀 바꿔줘")),
        tool_resp("recalculate", NULL_CHANGES),
        ToolMessage(content="Error: 바꿀 조건이 없습니다.", tool_call_id="tu_1", status="error"),
    ]
    payload = bound.bound._get_request_payload(messages, **bound.kwargs)

    assert payload["model"] == "claude-opus-5-5"
    assert payload["max_tokens"] == 16000
    assert payload["system"] == SYSTEM_PROMPT
    assert payload["tools"] == TOOLS
    assert payload["output_config"] == {"effort": "medium", "format": FINAL_FORMAT}
    assert payload["cache_control"] == {"type": "ephemeral"}
    assert payload["betas"] == [FALLBACK_BETA]
    assert payload["fallbacks"] == "default"
    assert "thinking" not in payload
    assert bound.bound._async_client.timeout == 600.0
    # 도구 결과는 다음 요청의 user 메시지로 전달된다
    last = payload["messages"][-1]
    assert last["role"] == "user"
    assert (last["content"][0]["tool_use_id"], last["content"][0]["is_error"]) == ("tu_1", True)
    assert isinstance(build_messages(question())[1], HumanMessage)
