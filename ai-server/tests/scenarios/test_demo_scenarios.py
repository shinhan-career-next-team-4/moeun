"""데모 대화 시나리오를 실제 LLM으로 반복 실행해 규칙 위반이 없는지 본다(Issue #57, FR-AI-06·07·09, CP-06, NFR-USA-01).

비용이 들고 결과가 흔들려서 기본 `pytest`에서는 건너뛴다. 돌릴 때:

    MOEUN_RUN_LLM=1 uv run pytest tests/scenarios -s          # 기본 5회
    MOEUN_RUN_LLM=1 MOEUN_LLM_RUNS=1 uv run pytest tests/scenarios -s   # 비용 가늠용 1회

모델만 실제이고 Spring 계산은 fake다(`tests/fakes.FakeCalc`, 요청한 조건을 응답에 반영). 그래서 숫자 자체가 아니라
도구 호출·인용·고정 문구·숫자 대조 같은 가드레일을 본다. 결과는 세 가지로 센다.
- ok: 기대한 유형·도구 호출·문구로 끝났다
- blocked: 서버 가드레일이 모델 답을 거부해 `AgentError`로 끝났다(사용자에게는 실패 안내). 위반은 아니다
- unexpected: 위반은 없지만 기대와 다른 유형으로 끝났다(예: 계산해야 할 것을 되물음)
- violation: 규칙을 어긴 답이 사용자에게 나갈 수 있다(누적, 개별 종목, 범위 밖 문구 불일치, 인용 등)
본문 원문은 남기지 않고 시나리오마다 첫 번째 성공 답 하나만 문서용 표본으로 출력한다.
"""

import json
import os
import re
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import anthropic
import pytest

from app.core.config import get_settings
from app.core.copy import load_copy
from app.llm.agent import AgentError, ChatAgent, out_of_scope_content
from app.schemas import ChatRequest, ConditionChanges, DocType, RetrievedChunk, Scenario
from app.tools.calc import CalcRejected
from tests.fakes import FakeCalc

REPO = Path(__file__).resolve().parents[3]
PLAN = json.loads((REPO / "docs/api/examples/plan-context/plan-context-basic.json").read_text(encoding="utf-8"))
OUT_OF_RANGE = json.loads(
    (REPO / "docs/api/examples/internal-calc/validation-failed-out-of-range-response.json").read_text(encoding="utf-8")
)

RUNS = int(os.environ.get("MOEUN_LLM_RUNS", "5"))
pytestmark = pytest.mark.skipif(
    os.environ.get("MOEUN_RUN_LLM") != "1" or get_settings().anthropic_api_key is None,
    reason="실제 LLM 시나리오: MOEUN_RUN_LLM=1과 ANTHROPIC_API_KEY가 필요하다",
)

# 본문에 display 키 이름(diff.AVG.afterTaxAssetDelta 등)이 그대로 나오면 사용자에게 내부 이름이 보인다
INTERNAL_KEY = re.compile(r"\b(?:current|changed|diff|conditions|assumptions)\.[A-Za-z]|\b(?:GOOD|AVG|BAD)\.[A-Za-z]")

# 이 낱말이 본문에 나오면 개별 종목을 거론한 것이다(ETF 상품명은 #168 정책으로 허용이라 보지 않는다)
INDIVIDUAL_STOCKS = ("삼성전자", "삼전", "SK하이닉스", "하이닉스", "엔비디아", "테슬라", "애플", "카카오", "네이버")


class ScenarioCalc(FakeCalc):
    """요청한 조건을 `conditions.changed`와 그 표시 문자열에 반영하는 fake. 나머지 숫자는 fixture 그대로다."""

    async def recalculate(self, member_id: int, baseline: Scenario, changes: ConditionChanges) -> dict[str, Any]:
        result = await super().recalculate(member_id, baseline, changes)
        asked = changes.changed()
        changed, display = result["conditions"]["changed"], result["display"]
        # fixture는 월 저축액만 바뀐 비교다. 요청하지 않은 조건은 현재 값으로 되돌려 요청과 응답을 맞춘다
        for key, value in result["conditions"]["current"].items():
            changed[key] = value
            display[f"conditions.changed.{key}"] = display[f"conditions.current.{key}"]
        if (v := asked.get("monthly_saving_amount")) is not None:
            changed["monthlySavingAmount"] = v
            display["conditions.changed.monthlySavingAmount"] = f"약 {v // 10_000:,}만원"
        if (v := asked.get("savings_ratio_pct")) is not None:
            changed["savingsRatioPct"] = v
            display["conditions.changed.savingsRatioPct"] = f"적금 {v} : ETF {100 - v}"
        if (v := asked.get("target_period_months")) is not None:
            changed["targetPeriodMonths"] = v
            display["conditions.changed.targetPeriodMonths"] = f"{v // 12}년 {v % 12}개월"
        if (v := asked.get("savings_rate_input_pct")) is not None:
            changed["savingsRateInputPct"] = v
            display["conditions.changed.savingsRateInputPct"] = f"연 {v}%"
        return result


class NoDocs:
    async def search(self, query: str, doc_type: DocType | None = None, top_k: int | None = None,
                     etf_codes: Any = None) -> list[RetrievedChunk]:
        return []


@dataclass
class Turn:
    text: str
    types: tuple[str, ...]            # 허용하는 messageType
    calls: list[dict[str, Any]] | None = None   # 이 턴에서 계산기에 간 changes. None이면 계산 호출이 없어야 한다
    # 범위 밖 답이 이 중 하나(서버 고정 문구)여야 한다. outOfScope.tax(D-073)도 고정 문구다
    fixed_content: tuple[str, ...] = ()


@dataclass
class Case:
    name: str
    turns: list[Turn]
    rejected: bool = False            # 계산기가 범위 밖 값(422)으로 거절한다
    sample: str = field(default="", init=False)


OUT_OF_SCOPE_COPIES = (out_of_scope_content(), load_copy().get("outOfScope.tax"))
MONTHLY_50 = {"monthly_saving_amount": 500_000}
CASES = [
    Case("S1 월 저축액 변경", [Turn("월 저축액을 50만원으로 바꾸면 어떻게 돼?", ("RECALC_RESULT",), [MONTHLY_50])]),
    Case("S2 비중 변경", [Turn("적금 70 ETF 30으로 바꿔서 보여줘", ("RECALC_RESULT",), [{"savings_ratio_pct": 70}])]),
    Case(
        "S3 기간 변경(앞 조건 누적 금지)",
        [
            Turn("월 저축액을 50만원으로 바꾸면?", ("RECALC_RESULT",), [MONTHLY_50]),
            Turn("그럼 기간을 4년으로 하면?", ("RECALC_RESULT",), [{"target_period_months": 48}]),
        ],
    ),
    Case(
        "S4a 범위 밖 질문",
        [Turn("삼성전자 주식 지금 사도 돼?", ("OUT_OF_SCOPE",), None, OUT_OF_SCOPE_COPIES)],
    ),
    Case(
        "S4b 범위 밖 값",
        [Turn("월 저축액을 1억원으로 바꾸면?", ("CLARIFY",), [{"monthly_saving_amount": 100_000_000}])],
        rejected=True,
    ),
]


def _request(text: str, history: list[dict[str, str]]) -> ChatRequest:
    return ChatRequest.model_validate({
        "memberId": 1, "messageId": 9, "mode": "QUESTION", "text": text, "baselineScenario": "AVG",
        "planContext": PLAN, "history": history,
    })


def _violations(turn: Turn, resp: Any, calls: list[dict[str, Any]]) -> list[str]:
    """사용자에게 나갈 수 있는 규칙 위반. 기대와 다르기만 한 것은 여기서 세지 않는다."""
    found = []
    if turn.calls is None and calls:
        found.append("범위 밖인데 계산을 호출함")
    if turn.calls is not None and calls != turn.calls:
        found.append(f"도구 호출 조건이 다름(누적 의심): {calls}")
    if turn.fixed_content and resp.message_type == "OUT_OF_SCOPE" and resp.content not in turn.fixed_content:
        found.append("범위 밖인데 고정 문구가 아님")
    if resp.message_type == "OUT_OF_SCOPE" and resp.citation_chunk_ids:
        found.append("범위 밖 답에 인용이 있음")
    if INTERNAL_KEY.search(resp.content):
        found.append("본문에 내부 표시 키 이름이 보임")
    if any(name in resp.content for name in INDIVIDUAL_STOCKS) and resp.message_type != "OUT_OF_SCOPE":
        found.append("본문에 개별 종목명")
    return found


async def _run_once(case: Case, agent_for: Any) -> tuple[str, str]:
    history: list[dict[str, str]] = []
    status, detail = "ok", ""
    for turn in case.turns:
        calc = ScenarioCalc(error=CalcRejected("VALIDATION_FAILED", OUT_OF_RANGE["errors"]) if case.rejected else None)
        agent = agent_for(calc)
        try:
            resp = await agent.run(_request(turn.text, history))
        except AgentError as e:
            return "blocked", e.code
        found = _violations(turn, resp, calc.calls)
        if found:
            return "violation", "; ".join(found)
        if resp.message_type not in turn.types:
            status, detail = "unexpected", f"{resp.message_type}"
        elif not case.sample and status == "ok":
            case.sample = resp.content
        history += [{"role": "user", "content": turn.text}, {"role": "assistant", "content": resp.content}]
    return status, detail


@pytest.mark.parametrize("case", CASES, ids=[c.name.split()[0] for c in CASES])
async def test_demo_scenario_has_no_rule_violation(case: Case):
    s = get_settings()
    assert s.anthropic_api_key is not None
    client = anthropic.AsyncAnthropic(api_key=s.anthropic_api_key.get_secret_value())

    def agent_for(calc: FakeCalc) -> ChatAgent:
        return ChatAgent(client, calc, NoDocs(), model=s.anthropic_model, effort=s.anthropic_effort,
                         max_rounds=s.max_tool_rounds)

    results = [await _run_once(case, agent_for) for _ in range(RUNS)]
    await client.close()

    counts = Counter(status for status, _ in results)
    print(f"\n[{case.name}] {RUNS}회: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    for status, detail in results:
        if status != "ok":
            print(f"  - {status}: {detail}")
    if case.sample:
        print(f"  표본: {case.sample}")
    assert counts["violation"] == 0, [d for st, d in results if st == "violation"]
