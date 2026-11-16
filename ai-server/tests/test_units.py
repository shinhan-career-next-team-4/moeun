import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from app.config import get_settings
from app.ingest.chunker import split_chunks
from app.main import app, get_agent
from app.rag.search import diversify_orgs
from app.schemas import ChatRequest, ChatResponse, ConditionChanges, DocType, RetrievedChunk, Scenario
from app.tools.calc import CalcClient, CalcError


def test_chunker_respects_max_and_keeps_text():
    text = "가" * 1900 + "\n\n" + "짧은 문단"
    chunks = split_chunks(text, max_chars=800, overlap=100)
    assert all(len(c) <= 800 for c in chunks)
    assert chunks[-1] == "짧은 문단"


def chunk(i, org):
    return RetrievedChunk(chunk_id=i, content="", title="", source_url="", doc_type=DocType.DISCLOSURE,
                          org_name=org, distance=i / 10)


def test_diversify_brings_second_org():
    rows = [chunk(1, "A"), chunk(2, "A"), chunk(3, "A"), chunk(4, "B")]
    picked = diversify_orgs(rows, top_k=3)
    assert [c.chunk_id for c in picked] == [1, 2, 4]


@respx.mock
async def test_calc_client_sends_camel_case_and_key():
    route = respx.post("http://spring/internal/calc").mock(return_value=httpx.Response(200, json={"calcRunId": 3}))
    async with httpx.AsyncClient(base_url="http://spring") as http:
        body = await CalcClient(http, "k").recalculate(1, Scenario.AVG, ConditionChanges(monthly_saving_amount=1))
    sent = route.calls.last.request
    assert sent.headers["X-Internal-Api-Key"] == "k"
    assert b'"conditionChanges":{"monthlySavingAmount":1}' in sent.content.replace(b" ", b"")
    assert body["calcRunId"] == 3


@respx.mock
async def test_calc_validation_error_becomes_calc_error():
    respx.post("http://spring/internal/calc").mock(return_value=httpx.Response(422, text="bad"))
    async with httpx.AsyncClient(base_url="http://spring") as http:
        with pytest.raises(CalcError):
            await CalcClient(http, "k").recalculate(1, Scenario.AVG, ConditionChanges(goal_amount=5))


def test_chat_requires_internal_key():
    with TestClient(app) as client:
        assert client.get("/health").json() == {"status": "UP"}
        assert client.post("/chat", json={}).status_code == 401


def test_chat_uses_injected_agent_and_camel_case():
    class StubAgent:
        async def run(self, req: ChatRequest) -> ChatResponse:
            return ChatResponse(message_type="ANSWER", content=req.text or "", baseline_scenario=req.baseline_scenario)

    app.dependency_overrides[get_agent] = StubAgent
    try:
        with TestClient(app) as client:
            r = client.post(
                "/chat",
                headers={"X-Internal-Api-Key": get_settings().internal_api_key.get_secret_value()},
                json={"memberId": 1, "messageId": 2, "mode": "QUESTION", "text": "안녕", "baselineScenario": "AVG"},
            )
    finally:
        app.dependency_overrides.clear()
    assert r.status_code == 200
    assert r.json() == {
        "messageType": "ANSWER", "content": "안녕", "baselineScenario": "AVG",
        "calcRunId": None, "conditionChanges": None, "citationChunkIds": [],
    }
