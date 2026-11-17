"""Issue #35 리뷰 라운드 1 회귀 테스트: 세금 단서의 이력 유입, 검색 후 범위 밖 답, EXPLAIN의 범위 밖 답."""

from types import SimpleNamespace
from typing import Any

from anthropic.types.beta import BetaToolUseBlock

from app.llm.agent import ChatAgent, build_messages
from app.schemas import DocType, RetrievedChunk
from tests.fakes import FakeCalc
from tests.test_plan_context import COPY, FakeClaude, load, make_request, text_resp

TAX_NOTE = COPY.get("tax.note")
TAX_SENTENCE = COPY.get("outOfScope.tax")


class OneChunkRetriever:
    async def search(self, query: str, doc_type: DocType | None = None, top_k: int | None = None) -> list[RetrievedChunk]:
        return [
            RetrievedChunk(
                chunk_id=101, content="본문", title="제목", source_url="https://example.com", doc_type=DocType.EDU,
                org_name="기관", distance=0.1,
            )
        ]


def search_resp() -> SimpleNamespace:
    block = BetaToolUseBlock(
        type="tool_use", id="t1", name="search_documents", input={"query": "세제 혜택", "doc_type": "ANY"}
    )
    return SimpleNamespace(stop_reason="tool_use", content=[block])


def agent(claude: FakeClaude) -> ChatAgent:
    return ChatAgent(claude, FakeCalc(), OneChunkRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4)


def sent_text(messages: Any) -> str:
    return "\n".join(str(m["content"]) for m in messages)


async def test_tax_note_of_previous_answer_is_not_sent_back_to_the_model():
    plan = load("plan-context-basic.json")
    claude = FakeClaude([text_resp("세금은 15.4%로 가정했어요.", "ANSWER")])
    first = await agent(claude).run(make_request(plan, "QUESTION", text="세금 얼마 떼요?"))
    assert TAX_NOTE in first.content   # 응답에는 붙는다

    history = [
        {"role": "user", "content": "세금 얼마 떼요?"},
        {"role": "assistant", "content": first.content},
    ]
    req = make_request(plan, "QUESTION", text="그럼 목표까지 얼마나 모자라요?", history=history)

    sent = sent_text(build_messages(req))
    assert TAX_NOTE not in sent and "세금은 15.4%로 가정했어요." in sent   # 본문은 남고 단서만 빠진다

    claude2 = FakeClaude([text_resp("-약 288만원 미달이에요.", "ANSWER")])
    await agent(claude2).run(req)
    assert TAX_NOTE not in sent_text(claude2.calls[0]["messages"])


async def test_assistant_turn_made_only_of_tax_note_is_dropped_without_breaking_role_order():
    req = make_request(
        load("plan-context-basic.json"), "QUESTION", text="다음 질문",
        history=[
            {"role": "user", "content": "첫 질문"},
            {"role": "assistant", "content": TAX_NOTE},
        ],
    )
    roles = [m["role"] for m in build_messages(req)]
    assert roles == ["user"] and TAX_NOTE not in sent_text(build_messages(req))


async def test_out_of_scope_tax_answer_after_search_has_no_citations():
    final = text_resp(TAX_SENTENCE, "OUT_OF_SCOPE")
    final.content[0].text = final.content[0].text.replace('"citation_chunk_ids": []', '"citation_chunk_ids": [101]')
    assert "[101]" in final.content[0].text   # 모델이 검색한 근거를 인용했다고 낸 입력
    claude = FakeClaude([search_resp(), final])
    req = make_request(load("plan-context-basic.json"), "QUESTION", text="이 ETF는 세제 혜택이 있어요?")
    response = await agent(claude).run(req)

    assert len(claude.calls) == 2   # 검색을 실제로 거쳤다
    assert response.message_type == "OUT_OF_SCOPE" and response.content == TAX_SENTENCE
    assert response.citation_chunk_ids == [] and response.calc_run_id is None and response.condition_changes is None


async def test_search_answer_keeps_its_citations():
    final = text_resp("교육자료에 따르면 그래요.", "ANSWER")
    final.content[0].text = final.content[0].text.replace('"citation_chunk_ids": []', '"citation_chunk_ids": [101]')
    claude = FakeClaude([search_resp(), final])
    response = await agent(claude).run(make_request(load("plan-context-basic.json"), "QUESTION", text="ETF가 뭐예요?"))
    assert response.message_type == "ANSWER" and response.citation_chunk_ids == [101]


async def test_explain_keeps_out_of_scope_from_the_model_with_null_calc_run_id():
    claude = FakeClaude([text_resp(TAX_SENTENCE, "OUT_OF_SCOPE")])
    response = await agent(claude).run(make_request(load("plan-context-basic.json"), "EXPLAIN", 540))

    assert response.message_type == "OUT_OF_SCOPE" and response.content == TAX_SENTENCE
    assert response.calc_run_id is None and response.condition_changes is None and response.citation_chunk_ids == []
