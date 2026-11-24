"""Issue #267: 대화로 ETF 종목 바꾸기(D-097, 계약 internal-calc C2-2·C2-6).

네트워크 없이 FakeClaude/FakeCalc로 본다. 모델이 프롬프트를 지키는지(종목 이름 인용, ETF 0% 한 문장, 목록 밖 종목
되묻기)는 FakeClaude로 보장할 수 없어 실모델 e2e(S8-11) 몫이다(D-073). 여기서는 서버 사실(도구 스키마, 계산 인자,
이어받은 값 거르기, 같은 종목 처리, 안내 붙이기, 본문 검사)만 본다.
"""

import copy
import json
from types import SimpleNamespace
from typing import Any

import pytest
from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock
from pydantic import ValidationError

from app.llm.agent import ETF_ZERO_NOTE, ETF_ZERO_SENTENCE, TOOLS, ChatAgent
from app.llm.conditions import drop_carried_conditions
from app.llm.prompts import SYSTEM_PROMPT
from app.schemas import ChatRequest, ConditionChanges, DocType, HistoryItem, RetrievedChunk
from app.tools.calc import CalcRejected
from tests.fakes import SUCCESS_RESPONSE, FakeCalc
from tests.paths import REPO

PLAN_DIR = REPO / "docs" / "api" / "examples" / "plan-context"
NULLS: dict[str, Any] = dict.fromkeys(
    ["goal_amount", "monthly_saving_amount", "savings_ratio_pct", "savings_rate_input_pct", "target_period_months",
     "etf_product_code"]
)
KOSPI = "코스피200 추종 ETF"
NASDAQ = "나스닥100 추종 ETF"


def text_resp(message_type: str, content: str) -> SimpleNamespace:
    payload = {"message_type": message_type, "content": content, "citation_chunk_ids": []}
    return SimpleNamespace(stop_reason="end_turn", content=[BetaTextBlock(type="text", text=json.dumps(payload))])


def tool_resp(input_: dict[str, Any]) -> SimpleNamespace:
    block = BetaToolUseBlock(type="tool_use", id="tu_1", name="recalculate", input={**NULLS, **input_})
    return SimpleNamespace(stop_reason="tool_use", content=[block])


class FakeClaude:
    def __init__(self, responses: list[SimpleNamespace]):
        self.responses = list(responses)
        self.requests: list[dict[str, Any]] = []
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs: Any) -> SimpleNamespace:
        self.requests.append(copy.deepcopy(kwargs))
        return self.responses.pop(0)

    def tool_results(self) -> list[str]:
        """모델에게 돌려준 도구 결과 본문."""
        return [
            block["content"]
            for req in self.requests for msg in req["messages"] if msg["role"] == "user" and isinstance(msg["content"], list)
            for block in msg["content"] if isinstance(block, dict) and block.get("type") == "tool_result"
        ]


class FakeRetriever:
    async def search(self, query: str, doc_type: DocType | None = None, top_k: int | None = None) -> list[RetrievedChunk]:
        return []


class NoStockJudge:
    async def recommends_individual_stock(self, text: str) -> bool:
        return False


def plan(savings_ratio_pct: int | None = None, stored_product: str | None = "SP500") -> dict[str, Any]:
    """계획 종목 S&P500(SP500)인 planContext. ETF 0% 계획은 종목 표시 키가 없다.

    stored_product는 conditions.etfProductCode(계획에 저장된 종목, #297)다. None이면 그 키를 뺀다(#297 전 Spring).
    """
    name = "plan-context-basic.json" if savings_ratio_pct is None else "plan-context-etf0.json"
    context: dict[str, Any] = json.loads((PLAN_DIR / name).read_text(encoding="utf-8"))
    if stored_product is None:
        context["conditions"].pop("etfProductCode", None)
    else:
        context["conditions"]["etfProductCode"] = stored_product
    return context


def kospi_response(changed_ratio: int = 50, code: str = "069500", name: str = KOSPI) -> dict[str, Any]:
    """종목을 바꾼 /internal/calc 응답(C2-3·C2-6). 숫자는 부록 A-1 그대로다."""
    response = json.loads(SUCCESS_RESPONSE.read_text(encoding="utf-8"))
    response["conditions"]["current"]["etfProductCode"] = "SP500"
    response["conditions"]["changed"]["etfProductCode"] = code
    response["conditions"]["changed"]["savingsRatioPct"] = changed_ratio
    response["display"]["conditions.current.etfProductName"] = "S&P500 추종 ETF"
    response["display"]["conditions.changed.etfProductName"] = name
    return response


async def run(
    *responses: SimpleNamespace, text: str, calc: FakeCalc | None = None, plan_context: dict[str, Any] | None = None,
    history: list[dict[str, str]] | None = None,
) -> tuple[Any, FakeClaude, FakeCalc]:
    claude = FakeClaude(list(responses))
    calc = calc or FakeCalc(kospi_response())
    agent = ChatAgent(claude, calc, FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4,
                      stock_judge=NoStockJudge())
    req = ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG",
                      planContext=plan_context if plan_context is not None else plan(), history=history or [])
    return await agent.run(req), claude, calc


# ---- 도구 스키마·모델 ----

def test_recalculate_tool_offers_only_the_three_product_codes():
    """종목은 정해진 코드(SP500·069500·133690)와 null만 고른다. 지수 이름이 설명에 있다"""
    field = next(t for t in TOOLS if t["name"] == "recalculate")["input_schema"]["properties"]["etf_product_code"]
    assert field["anyOf"] == [{"type": "string", "enum": ["SP500", "069500", "133690"]}, {"type": "null"}]
    assert all(name in field["description"] for name in ("S&P500", "코스피200", "나스닥100"))


def test_condition_changes_takes_product_code_as_string_only():
    assert ConditionChanges.model_validate({"etf_product_code": "069500"}).changed() == {"etf_product_code": "069500"}
    with pytest.raises(ValidationError):
        ConditionChanges.model_validate({"etf_product_code": 133690})
    assert ConditionChanges(etf_product_code="069500").model_dump(by_alias=True, exclude_none=True) == {
        "etfProductCode": "069500"
    }


# ---- 계산 인자 ----

async def test_product_change_is_sent_to_spring_and_saved_in_snake_case():
    resp, _, calc = await run(
        tool_resp({"etf_product_code": "069500"}), text_resp("RECALC_RESULT", f"{KOSPI}로 바꿔 계산했어요."),
        text="코스피200으로 바꾸면 어때?",
    )
    assert calc.calls == [{"etf_product_code": "069500"}]
    assert resp.message_type == "RECALC_RESULT"
    assert resp.condition_changes == {"etf_product_code": "069500"}


async def test_product_and_monthly_saving_change_together():
    _, _, calc = await run(
        tool_resp({"etf_product_code": "133690", "monthly_saving_amount": 500_000}),
        text_resp("RECALC_RESULT", "나스닥100 추종 ETF로 바꾸고 월 50만원으로 계산했어요."),
        text="나스닥100으로 바꾸고 월 50만원 넣으면?", calc=FakeCalc(kospi_response(code="133690", name=NASDAQ)),
    )
    assert calc.calls == [{"etf_product_code": "133690", "monthly_saving_amount": 500_000}]


async def test_same_product_as_plan_is_not_a_change():
    """계획과 같은 종목 키는 바꾸지 않은 것이라 빼고 계산한다(D-097)"""
    resp, _, calc = await run(
        tool_resp({"etf_product_code": "SP500", "monthly_saving_amount": 500_000}),
        text_resp("RECALC_RESULT", "월 50만원으로 계산했어요."),
        text="S&P500 그대로 두고 월 50만원 넣으면?", calc=FakeCalc(),
    )
    assert calc.calls == [{"monthly_saving_amount": 500_000}]
    assert resp.condition_changes == {"monthly_saving_amount": 500_000}


async def test_only_the_same_product_is_not_calculated():
    _, claude, calc = await run(
        tool_resp({"etf_product_code": "SP500"}), text_resp("ANSWER", "지금 계획이 이미 S&P500 추종 ETF예요."),
        text="S&P500으로 바꾸면?",
    )
    assert calc.calls == []
    assert any("이미 계획의 종목" in r for r in claude.tool_results())


# ---- 이어받은 값 거르기(D-041) ----

def test_carried_condition_is_dropped_but_the_product_stays():
    """앞 턴의 월 저축액을 모델이 다시 넣어도 이번 발화가 종목만 말했으면 종목만 남는다. 이름 속 숫자(200)는 세지 않는다"""
    history = [HistoryItem(role="user", content="월 50만원 넣으면?"), HistoryItem(role="assistant", content="계산했어요.")]
    changes = ConditionChanges(monthly_saving_amount=500_000, etf_product_code="069500")
    assert drop_carried_conditions("그럼 코스피200으로 바꾸면?", changes, history).changed() == {
        "etf_product_code": "069500"
    }


# ---- ETF 0% (D-097 합의 4) ----

async def test_etf_zero_comparison_gets_the_one_sentence_note():
    """ETF 0% 계획에서 종목만 바꾸면 결과가 같다는 한 문장 안내를 붙인다"""
    _, claude, _ = await run(
        tool_resp({"etf_product_code": "069500"}), text_resp("RECALC_RESULT", ETF_ZERO_SENTENCE),
        text="코스피200으로 바꾸면?", calc=FakeCalc(kospi_response(changed_ratio=100)),
        plan_context=plan(savings_ratio_pct=100),
    )
    assert any(ETF_ZERO_NOTE in r for r in claude.tool_results())


async def test_etf_zero_note_is_absent_when_another_condition_also_changes():
    """비중·월 저축액을 함께 바꿨으면 결과가 달라지므로 '결과는 같아요' 안내를 붙이지 않는다"""
    _, claude, _ = await run(
        tool_resp({"etf_product_code": "069500", "savings_ratio_pct": 100}),
        text_resp("RECALC_RESULT", "적금 100으로 바꿔 계산했어요."),
        text="적금 100으로 하고 코스피200으로 바꾸면?", calc=FakeCalc(kospi_response(changed_ratio=100)),
    )
    assert not any("etfNote" in r for r in claude.tool_results())


def unchanged_product_response(changed_ratio: int = 100) -> dict[str, Any]:
    """Spring이 계획과 같은 종목이라 바꾸지 않은 것으로 본 응답(C2-2·C2-3): 양쪽에 종목 코드·이름 키가 없다."""
    response = json.loads(SUCCESS_RESPONSE.read_text(encoding="utf-8"))
    response["conditions"]["changed"]["savingsRatioPct"] = changed_ratio
    return response


async def test_etf_zero_plan_sends_the_product_key_to_spring():
    """계획 종목을 모르면(ETF 0%이고 #297 전 Spring) 같은 종목이라고 가정해 빼지 않고 Spring에 넘긴다(#294)"""
    _, _, calc = await run(
        tool_resp({"etf_product_code": "SP500", "monthly_saving_amount": 500_000}),
        text_resp("RECALC_RESULT", "월 50만원으로 계산했어요."),
        text="S&P500으로 하고 월 50만원 넣으면?", calc=FakeCalc(unchanged_product_response()),
        plan_context=plan(savings_ratio_pct=100, stored_product=None),
    )
    assert calc.calls == [{"etf_product_code": "SP500", "monthly_saving_amount": 500_000}]


async def test_etf_zero_plan_drops_the_key_spring_saw_as_unchanged():
    """Spring이 계획과 같은 종목으로 본 키는 저장하지 않는다. 남기면 비교 카드에 값 없는 'ETF 종목' 줄이 생긴다(#294)"""
    resp, _, _ = await run(
        tool_resp({"etf_product_code": "SP500", "monthly_saving_amount": 500_000}),
        text_resp("RECALC_RESULT", "월 50만원으로 계산했어요."),
        text="S&P500으로 하고 월 50만원 넣으면?", calc=FakeCalc(unchanged_product_response()),
        plan_context=plan(savings_ratio_pct=100, stored_product=None),
    )
    assert resp.condition_changes == {"monthly_saving_amount": 500_000}


async def test_etf_zero_plan_keeps_a_product_spring_saw_as_changed():
    """ETF 0% 계획에 다른 종목이 저장돼 있어 Spring이 바꾼 것으로 본 종목은 그대로 저장한다(#294)"""
    resp, _, _ = await run(
        tool_resp({"etf_product_code": "069500"}), text_resp("RECALC_RESULT", ETF_ZERO_SENTENCE),
        text="코스피200으로 바꾸면?", calc=FakeCalc(kospi_response(changed_ratio=100)),
        plan_context=plan(savings_ratio_pct=100),
    )
    assert resp.condition_changes == {"etf_product_code": "069500"}


async def test_etf_zero_plan_product_only_same_as_plan_is_the_plan_product():
    """ETF 0% 계획에서 종목만 바꾸라고 했는데 Spring이 계획과 같은 종목으로 보면 '이미 계획의 종목'으로 답한다(#294)"""
    resp, claude, calc = await run(
        tool_resp({"etf_product_code": "SP500"}), text_resp("ANSWER", "지금 계획의 종목과 같아서 바꿀 것이 없어요."),
        text="S&P500으로 바꾸면?", calc=FakeCalc(unchanged_product_response()),
        plan_context=plan(savings_ratio_pct=100, stored_product=None),
    )
    assert calc.calls == [{"etf_product_code": "SP500"}]
    assert any("이미 계획의 종목" in r for r in claude.tool_results())
    assert resp.message_type == "ANSWER"
    assert resp.condition_changes is None


async def test_etf_zero_plan_same_stored_product_is_not_calculated():
    """ETF 0% 계획도 planContext가 계획의 종목을 알려 주면(#297) 같은 종목만 바꾸라는 말은 계산하지 않는다"""
    resp, claude, calc = await run(
        tool_resp({"etf_product_code": "SP500"}), text_resp("ANSWER", "지금 계획의 종목과 같아서 바꿀 것이 없어요."),
        text="S&P500으로 바꾸면?", plan_context=plan(savings_ratio_pct=100),
    )
    assert calc.calls == []
    assert any("이미 계획의 종목" in r for r in claude.tool_results())
    assert resp.condition_changes is None


async def test_etf_zero_plan_same_stored_product_is_dropped_before_calc():
    """같은 종목 키는 계산 전에 빼고 나머지 조건만 계산한다(#297)"""
    resp, _, calc = await run(
        tool_resp({"etf_product_code": "SP500", "monthly_saving_amount": 500_000}),
        text_resp("RECALC_RESULT", "월 50만원으로 계산했어요."),
        text="S&P500으로 하고 월 50만원 넣으면?", calc=FakeCalc(unchanged_product_response()),
        plan_context=plan(savings_ratio_pct=100),
    )
    assert calc.calls == [{"monthly_saving_amount": 500_000}]
    assert resp.condition_changes == {"monthly_saving_amount": 500_000}


async def test_etf_zero_plan_with_another_stored_product_sends_sp500():
    """ETF 0% 계획에 다른 종목(069500)이 저장돼 있으면 S&P500은 바꾼 종목이라 Spring에 넘긴다(#297)"""
    _, _, calc = await run(
        tool_resp({"etf_product_code": "SP500"}), text_resp("RECALC_RESULT", ETF_ZERO_SENTENCE),
        text="S&P500으로 바꾸면?", calc=FakeCalc(kospi_response(changed_ratio=100, code="SP500", name="S&P500 추종 ETF")),
        plan_context=plan(savings_ratio_pct=100, stored_product="069500"),
    )
    assert calc.calls == [{"etf_product_code": "SP500"}]


async def test_etf_note_is_absent_when_the_changed_side_uses_etf():
    _, claude, _ = await run(
        tool_resp({"etf_product_code": "069500"}), text_resp("RECALC_RESULT", f"{KOSPI}로 바꿔 계산했어요."),
        text="코스피200으로 바꾸면?",
    )
    assert not any("etfNote" in r for r in claude.tool_results())


# ---- 본문 검사(#155와의 관계) ----

async def test_answer_about_the_plan_may_name_the_changed_product():
    """종목을 바꾼 비교에서 '계획'과 다른 종목 이름이 한 문장에 있어도 계획 종목을 바꿔 말한 것이 아니다"""
    answer = f"계획의 종목을 {KOSPI}로 바꾸면 세후 자산이 약 789만원 증가해요."
    resp, _, _ = await run(tool_resp({"etf_product_code": "069500"}), text_resp("RECALC_RESULT", answer),
                           text="코스피200으로 바꾸면?")
    assert resp.content == answer


async def test_etf_zero_plan_may_name_products_after_changing_ratio_and_product():
    """ETF 0% 계획에서 비중과 종목을 함께 바꾼 비교는 종목 이름을 말해도 거부하지 않는다"""
    answer = f"적금 50 : ETF 50에 {KOSPI}로 바꾸면 세후 자산이 약 789만원 증가해요."
    resp, _, _ = await run(
        tool_resp({"etf_product_code": "069500", "savings_ratio_pct": 50}), text_resp("RECALC_RESULT", answer),
        text="비중 반반에 코스피200으로 하면?", plan_context=plan(savings_ratio_pct=100),
    )
    assert resp.content == answer


async def test_comparison_answer_may_not_name_a_third_product():
    """종목을 바꾼 비교의 답은 두 쪽 종목 이름만 쓴다. 비교에 없는 종목 이름이 나오면 거부하고 다시 쓰게 한다"""
    bad = f"{NASDAQ}로 바꾸면 세후 자산이 약 789만원 증가해요."
    good = f"{KOSPI}로 바꾸면 세후 자산이 약 789만원 증가해요."
    resp, _, _ = await run(tool_resp({"etf_product_code": "069500"}), text_resp("RECALC_RESULT", bad),
                           text_resp("RECALC_RESULT", good), text="코스피200으로 바꾸면?")
    assert resp.content == good


async def test_clarify_after_an_unlisted_product_may_list_the_choices_even_in_an_etf_zero_plan():
    """목록 밖 종목을 말한 발화에 고를 수 있는 세 종목을 들어 되물어도 ETF 0% 계획의 종목 언급 거부에 걸리지 않는다"""
    ask = f"S&P500 추종 ETF, {KOSPI}, {NASDAQ} 중 무엇으로 바꿔 볼까요?"
    resp, _, calc = await run(text_resp("CLARIFY", ask), text="코스닥150으로 바꾸면?",
                              plan_context=plan(savings_ratio_pct=100))
    assert (resp.message_type, resp.content, calc.calls) == ("CLARIFY", ask, [])


async def test_unknown_product_rejection_becomes_clarify_without_range_numbers():
    """(예외) 422 etfProductCode OUT_OF_RANGE(min·max 없음)는 계약 위반이 아니다. 모델에 넘기고 CLARIFY로 끝난다"""
    rejected = CalcRejected("VALIDATION_FAILED", [{"field": "etfProductCode", "reason": "OUT_OF_RANGE"}])
    ask = f"{KOSPI}와 {NASDAQ} 중 무엇으로 바꿔 볼까요?"
    resp, claude, _ = await run(tool_resp({"etf_product_code": "133690"}), text_resp("RECALC_RESULT", ask),
                                text="나스닥으로 바꾸면?", calc=FakeCalc(error=rejected))
    assert (resp.message_type, resp.content) == ("CLARIFY", ask)
    assert resp.calc_run_id is None and resp.condition_changes is None
    assert any('"etfProductCode"' in r and "minDisplay" not in r for r in claude.tool_results())


# ---- 프롬프트 ----

def test_system_prompt_lists_six_conditions_and_product_rules():
    assert "ETF 종목 여섯 가지" in SYSTEM_PROMPT
    assert "S&P500·코스피200·나스닥100" in SYSTEM_PROMPT
    assert "conditions.changed.etfProductName" in SYSTEM_PROMPT
    assert "etfNote" in SYSTEM_PROMPT
    assert "권하지 않습니다" in SYSTEM_PROMPT
    assert "field가 etfProductCode이면" in SYSTEM_PROMPT
