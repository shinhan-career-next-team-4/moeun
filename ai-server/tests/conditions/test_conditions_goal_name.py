"""Issue #345: 대화로 목표 이름 바꾸기(계약 internal-calc C2-2 `goalCustomName`).

이름은 계산 조건이 아니지만 적용(F-5)이 계산 기록으로 기준 계획을 찾으므로 recalculate로 Spring에 함께 보낸다.
모델이 이름을 지어내지 않게 사용자가 이번 발화에서 말한 이름만 받는다(공백·대소문자는 무시). 모델이 프롬프트를
지키는지(이름 바꾸기 발화를 알아보는지)는 FakeClaude로 보장할 수 없어 실모델 e2e 몫이다(D-073).
"""

import copy
import json
from types import SimpleNamespace
from typing import Any

import pytest
from anthropic.types.beta import BetaTextBlock, BetaToolUseBlock
from pydantic import ValidationError

from app.llm.agent import GOAL_NAME_ONLY_NOTE, TOOLS, ChatAgent
from app.llm.conditions import names_goal
from app.llm.prompts import SYSTEM_PROMPT
from app.schemas import ChatRequest, ConditionChanges, DocType, RetrievedChunk
from tests.fakes import FakeCalc
from tests.paths import REPO

PLAN = REPO / "docs" / "api" / "examples" / "plan-context" / "plan-context-basic.json"
NULLS: dict[str, Any] = dict.fromkeys(
    ["goal_amount", "monthly_saving_amount", "savings_ratio_pct", "savings_rate_input_pct", "target_period_months",
     "etf_product_code", "goal_custom_name"]
)


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

    def tool_results(self) -> list[dict[str, Any]]:
        """모델에게 돌려준 도구 결과 블록."""
        return [
            block
            for req in self.requests for msg in req["messages"] if msg["role"] == "user" and isinstance(msg["content"], list)
            for block in msg["content"] if isinstance(block, dict) and block.get("type") == "tool_result"
        ]


class FakeRetriever:
    async def search(self, query: str, doc_type: DocType | None = None, top_k: int | None = None) -> list[RetrievedChunk]:
        return []


async def run(
    *responses: SimpleNamespace, text: str, history: list[dict[str, str]] | None = None,
) -> tuple[Any, FakeClaude, FakeCalc]:
    claude = FakeClaude(list(responses))
    calc = FakeCalc()
    agent = ChatAgent(claude, calc, FakeRetriever(), model="claude-opus-5-5", effort="medium", max_rounds=4)
    req = ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG",
                      planContext=json.loads(PLAN.read_text(encoding="utf-8")), history=history or [])
    return await agent.run(req), claude, calc


# ---- 도구 스키마·모델 ----

def test_recalculate_tool_has_nullable_goal_name():
    field = next(t for t in TOOLS if t["name"] == "recalculate")["input_schema"]["properties"]["goal_custom_name"]
    assert field["type"] == ["string", "null"]
    assert "목표 이름" in field["description"]


def test_condition_changes_takes_goal_name_as_string_and_sends_camel_case():
    changes = ConditionChanges.model_validate({"goal_custom_name": "가게 보증금"})
    assert changes.changed() == {"goal_custom_name": "가게 보증금"}
    assert changes.model_dump(by_alias=True, exclude_none=True) == {"goalCustomName": "가게 보증금"}
    with pytest.raises(ValidationError):
        ConditionChanges.model_validate({"goal_custom_name": 1})


def test_prompt_routes_goal_name_change_to_recalculate():
    assert "goal_custom_name" in SYSTEM_PROMPT
    assert "목표 이름" in SYSTEM_PROMPT


# ---- 계산 인자 ----

async def test_goal_name_only_is_sent_to_spring_with_note():
    """이름만 바꿔도 Spring에 보내 계산 기록을 남긴다. 결과가 같다는 안내를 도구 결과에 붙인다"""
    resp, claude, calc = await run(
        tool_resp({"goal_custom_name": "가게 보증금"}),
        text_resp("RECALC_RESULT", "목표 이름을 가게 보증금으로 바꿨어요. 예상 결과는 그대로예요."),
        text="목표 이름을 가게 보증금으로 바꿔줘",
    )
    assert calc.calls == [{"goal_custom_name": "가게 보증금"}]
    assert resp.message_type == "RECALC_RESULT"
    assert resp.condition_changes == {"goal_custom_name": "가게 보증금"}
    assert json.loads(claude.tool_results()[0]["content"])["goalNameNote"] == GOAL_NAME_ONLY_NOTE


async def test_goal_name_with_goal_amount_sends_both_without_name_note():
    resp, claude, calc = await run(
        tool_resp({"goal_custom_name": "가게 보증금", "goal_amount": 50_000_000}),
        text_resp("RECALC_RESULT", "목표를 가게 보증금으로 바꿔 계산했어요."),
        text="목표를 가게 보증금 5천만원으로 바꿔줘",
    )
    assert calc.calls == [{"goal_amount": 50_000_000, "goal_custom_name": "가게 보증금"}]
    assert resp.condition_changes == {"goal_amount": 50_000_000, "goal_custom_name": "가게 보증금"}
    assert "goalNameNote" not in json.loads(claude.tool_results()[0]["content"])


async def test_goal_name_is_trimmed():
    _, _, calc = await run(
        tool_resp({"goal_custom_name": "  가게 보증금 "}),
        text_resp("RECALC_RESULT", "목표 이름을 바꿨어요."),
        text="목표 이름을 가게 보증금으로 바꿔줘",
    )
    assert calc.calls == [{"goal_custom_name": "가게 보증금"}]


async def test_name_the_user_did_not_say_is_not_calculated():
    """(예외) 모델이 발화에 없는 이름을 지어내면 계산하지 않고 되묻게 한다"""
    resp, claude, calc = await run(
        tool_resp({"goal_custom_name": "창업 자금"}),
        text_resp("CLARIFY", "어떤 이름으로 바꿀까요?"),
        text="목표 이름 바꾸고 싶어",
    )
    assert calc.calls == []
    assert claude.tool_results()[0]["is_error"] is True
    assert resp.message_type == "CLARIFY"
    assert resp.condition_changes is None


async def test_name_with_numbers_passes_number_check():
    """(예외) 이름 속 숫자("2호점", "1억")는 사용자가 말한 것이라 숫자 대조에 걸리지 않는다"""
    resp, _, _ = await run(
        tool_resp({"goal_custom_name": "2호점 1억 보증금"}),
        text_resp("RECALC_RESULT", "목표 이름을 2호점 1억 보증금으로 바꿨어요."),
        text="목표 이름을 2호점 1억 보증금으로 바꿔줘",
    )
    assert resp.content.startswith("목표 이름을 2호점 1억 보증금으로 바꿨어요.")


# ---- 이어받은 값 거르기 ----

@pytest.mark.parametrize(
    ("text", "name"),
    [
        ("목표를 가게보증금으로 바꿔줘", "가게 보증금"),      # 띄어쓰기를 보지 않는다
        ("목표 이름은 My Shop이라고 해줘", "my shop"),       # 대소문자를 보지 않는다
        ("목표를 가게 보증금 5천만원으로 바꿔줘", "가게 보증금"),
        ("목표 이름을 2호점 보증금으로 바꿔줘", "2호점 보증금"),
    ],
)
def test_names_goal_accepts_rename_utterances(text, name):
    assert names_goal(text, name)


@pytest.mark.parametrize(
    ("text", "name"),
    [
        ("월 50만원으로", "가게 보증금"),                    # 발화에 없는 이름
        ("가게 보증금 마련하려면 얼마 필요해?", "가게 보증금"),  # 이름을 바꾸라는 말이 아니다
        ("목표 금액을 1억으로 바꿔줘", "1억"),                # 금액을 이름으로 지어냄
        ("목표 금액을 1억으로 바꿔줘", "금액"),
        ("목표 이름을 가게 보증금으로 바꿔줘", "목표"),
        ("목표 종류를 결혼 자금으로 바꿔줘", "결혼 자금"),      # 목표 종류는 바꾸지 않는다(C10)
    ],
)
def test_names_goal_rejects_names_the_user_did_not_give(text, name):
    assert not names_goal(text, name)


async def test_amount_utterance_with_invented_name_is_not_calculated():
    """(예외) 목표 금액 발화에 모델이 이름까지 덧붙이면 계산하지 않는다(리뷰 반례)"""
    _, claude, calc = await run(
        tool_resp({"goal_amount": 100_000_000, "goal_custom_name": "1억"}),
        text_resp("CLARIFY", "어떤 이름으로 바꿀까요?"),
        text="목표 금액을 1억으로 바꿔줘",
    )
    assert calc.calls == []
    assert claude.tool_results()[0]["is_error"] is True


async def test_goal_type_utterance_is_not_renamed():
    """(예외) '목표 종류를 …로'는 이름 바꾸기가 아니다. 계산하지 않는다(C10)"""
    _, claude, calc = await run(
        tool_resp({"goal_custom_name": "결혼 자금"}),
        text_resp("ANSWER", "목표 종류·기존 납입액은 여기서 바꿀 수 없어요. 보유 자산은 입력 수정에서 바꿀 수 있어요."),
        text="목표 종류를 결혼 자금으로 바꿔줘",
    )
    assert calc.calls == []
    assert "종류" in claude.tool_results()[0]["content"]


async def test_goal_name_from_history_is_not_carried():
    """앞 턴에서 말한 이름을 이번 계산에 이어받지 않는다(FR-AI-06, D-041)"""
    history = [{"role": "user", "content": "목표 이름을 가게 보증금으로"},
               {"role": "assistant", "content": "목표 이름을 바꿨어요."}]
    _, claude, calc = await run(
        tool_resp({"goal_custom_name": "가게 보증금", "monthly_saving_amount": 500_000}),
        text_resp("RECALC_RESULT", "월 저축액을 바꿔 계산했어요."),
        text="월 50만원으로 해줘", history=history,
    )
    assert calc.calls == []
    assert claude.tool_results()[0]["is_error"] is True


async def test_conditions_carried_into_a_rename_are_dropped():
    """이름 속 숫자·'목표 이름'의 목표 때문에 앞 턴의 조건이 남지 않는다(리뷰 반례)"""
    history = [{"role": "user", "content": "목표 1억, 월 50만원이면?"}, {"role": "assistant", "content": "답"}]
    _, _, calc = await run(
        tool_resp({"goal_custom_name": "2호점 보증금", "goal_amount": 100_000_000,
                   "monthly_saving_amount": 500_000}),
        text_resp("RECALC_RESULT", "목표 이름을 2호점 보증금으로 바꿨어요."),
        text="목표 이름을 2호점 보증금으로 바꿔줘", history=history,
    )
    assert calc.calls == [{"goal_custom_name": "2호점 보증금"}]


async def test_number_inside_name_is_not_a_goal_amount():
    """이름 속 '1억'을 목표 금액으로 쓰지 않는다(리뷰 반례). history가 없어도 거른다"""
    _, _, calc = await run(
        tool_resp({"goal_custom_name": "1억 모으기", "goal_amount": 100_000_000}),
        text_resp("RECALC_RESULT", "목표 이름을 1억 모으기로 바꿨어요."),
        text="목표 이름을 1억 모으기로 바꿔줘",
    )
    assert calc.calls == [{"goal_custom_name": "1억 모으기"}]
