"""ETF 0% 계획에서 ETF를 물으면 이름을 나열하지 않고 추가 의향과 비중을 되묻는다(#168, #155 P-4, CF-07).

모델이 이 규칙을 실제로 지키는지는 FakeClaude로 보장할 수 없어 수동 평가 몫이다(D-073).
여기서는 프롬프트 문장과, 되묻는 답이 서버 검사에 걸리지 않고 그대로 나가는지만 본다.
"""

from app.llm.prompts import SYSTEM_PROMPT
from tests.test_etf_name_correction import etf0_request
from tests.test_etf_tax import run, text_resp
from tests.test_plan_context import load, make_request

ASK = "ETF를 계획에 넣을 의향이 있으신가요? 넣으신다면 적금과 ETF의 비중을 어떻게 할지 알려 주세요."


def etf0_rule() -> str:
    """ETF 0% 계획에서 ETF를 물을 때의 규칙 한 줄(불릿)"""
    bullets = [b for b in SYSTEM_PROMPT.split("\n- ") if "의향" in b]
    assert len(bullets) == 1, bullets
    return bullets[0]


def test_system_prompt_tells_model_to_ask_etf_intent_and_weight_in_etf0_plan():
    """ETF 0% 계획에서 ETF를 물으면 이름을 나열하지 않고 추가 의향과 비중을 CLARIFY로 되묻는다"""
    rule = etf0_rule()
    assert "ETF 0%" in rule
    assert "나열하지 않" in rule
    assert "비중" in rule
    assert "CLARIFY" in rule


def test_etf0_rule_does_not_invent_example_weights_or_use_recommendation_words():
    """되묻는 문장에 예시 비중을 지어내지 않게 하고, 판정 호출을 부르는 낱말('추천' 등)을 쓰지 않는다"""
    rule = etf0_rule()
    assert "지어내" in rule
    assert not any(w in rule for w in ("추천", "권유", "권해", "매수", "사세요"))


def test_system_prompt_no_longer_refers_to_removed_general_name_rule():
    """삭제한 '일반 표기 규칙'을 가리키는 문장이 남지 않는다"""
    assert "일반 표기 규칙" not in SYSTEM_PROMPT


async def test_clarify_answer_asking_etf_intent_and_weight_passes_in_etf0_plan():
    """ETF 이름 없이 의향·비중을 묻는 CLARIFY 답은 거부·재시도 없이 그대로 나간다(모델 1번)"""
    plan = load("plan-context-etf0.json")
    req = make_request(plan, "QUESTION", text="어떤 ETF들이 있어?")

    resp, claude = await run(req, text_resp(ASK, "CLARIFY"))

    assert resp.message_type == "CLARIFY"
    assert resp.content == ASK
    assert len(claude.calls) == 1


async def test_etf0_plan_answer_listing_etf_names_is_still_rejected_once():
    """안전망: 모델이 규칙을 어기고 허용 ETF 표현을 나열하면 기존대로 재시도 없이 거부한다"""
    from tests.test_etf_name_correction import rejected

    error, claude = await rejected(
        etf0_request(), text_resp("코스피200 추종 ETF, 나스닥 추종 ETF가 있어요."), text_resp("호출되면 안 돼요")
    )

    assert error.code == "FORBIDDEN_EXPRESSION"
    assert len(claude.calls) == 1


def test_system_prompt_does_not_tell_model_to_deny_etf_products_or_recommendations():
    """ETF 추천·상품명은 허용이라, 모델이 '상품 이름은 쓸 수 없다/상품을 추천하지 않는다'고 말하게 두지 않는다(#168)"""
    rule = etf0_rule()
    assert "쓸 수 없다" in rule
    scope = next(b for b in SYSTEM_PROMPT.split("\n- ") if b.startswith("개별 주식 종목 추천 금지"))
    assert "개별 종목을 추천하지 않는다는 말은 해도 됩니다" in scope   # 개별 종목 한정
    assert "ETF" in scope and "쓸 수 없다" in scope
