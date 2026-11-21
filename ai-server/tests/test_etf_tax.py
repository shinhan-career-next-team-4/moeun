"""Issue #155 수용 테스트: 종목 이름·종목 세율을 AI 설명에 반영(FR-AI-02, D-060, D-067, CP-06, 계약 C1a-5·C1a-6·C1a-8).

네트워크 없이 `docs/api/examples/`의 예시와 `docs/api/plan-context.md`만 읽는다. 비과세 종목 계획은 새 예시
`plan-context-etf-tax-free.json`, 과세 종목(15.4%)은 `plan-context-basic.json`에 raw 세율만 더해 만든다.
"""

import json
import re
from typing import Any

import pytest

from app.llm.agent import build_messages
from app.llm.prompts import SYSTEM_PROMPT
from app.schemas import DISPLAY_KEY, ChatRequest
from tests.test_explain import run
from tests.test_plan_context import COPY, EXAMPLES, load, make_request, model_input, text_resp, without

DOCS_API = EXAMPLES.parents[1]
TAX_NOTE = COPY.get("tax.note")
TAX_NOTE_FREE = COPY.get("tax.noteEtfTaxFree")

ETF_NAME_KEY = "assumptions.etfProductName"
ETF_RATE_KEY = "assumptions.etfGainTaxRatePct"
TAX_FREE_DISPLAY = "0%(국내 주식형 ETF 매매차익 비과세)"
ETF_RAW_KEYS = ("etfProductCode", "etfProductName", "etfGainTaxRatePct")


def taxable_plan(rate: Any = 15.4) -> dict[str, Any]:
    """대표 S&P500 상품(과세 15.4%) 계획. raw 세율과 두 표시 키를 basic 예시에 더한다."""
    plan = load("plan-context-basic.json")
    plan["result"]["assumptions"].update(etfProductCode="133690", etfProductName="S&P500 추종 ETF", etfGainTaxRatePct=rate)
    plan["display"][ETF_NAME_KEY] = "S&P500 추종 ETF"
    plan["display"][ETF_RATE_KEY] = "15.4%"
    return plan


def etf_unused_plan() -> dict[str, Any]:
    """ETF 비중 0%: raw etf* 전부 null, 두 표시 키 없음."""
    plan = load("plan-context-etf0.json")
    plan["result"]["assumptions"].update(etfProductCode=None, etfProductName=None, etfGainTaxRatePct=None)
    assert ETF_NAME_KEY not in plan["display"] and ETF_RATE_KEY not in plan["display"]
    return plan


async def answer_of(plan: dict[str, Any], answer: str) -> str:
    resp, _ = await run(make_request(plan, "EXPLAIN", plan["calcRunId"]), text_resp(answer))
    assert resp.message_type == "EXPLANATION"
    return str(resp.content)


def bullets_with(token: str) -> list[str]:
    """시스템 프롬프트에서 token이 들어 있는 규칙 줄(`- ` 항목)."""
    return [b for b in re.split(r"\n(?=- )", SYSTEM_PROMPT) if token in b]


# ---- AC-1: 두 ETF 표시 키는 선택 키로 필터를 통과해 모델 입력에 들어간다 ----

def test_ac1_etf_display_keys_pass_the_filter_and_reach_the_model():
    plan = load("plan-context-etf-tax-free.json")
    req = make_request(plan, "QUESTION")

    assert req.plan_context is not None
    assert req.plan_context.display[ETF_NAME_KEY] == "코스피200 추종 ETF"
    assert req.plan_context.display[ETF_RATE_KEY] == TAX_FREE_DISPLAY
    shown = model_input(req)["display"]
    assert shown[ETF_NAME_KEY] == "코스피200 추종 ETF" and shown[ETF_RATE_KEY] == TAX_FREE_DISPLAY


def test_ac1_etf_display_pattern_matches_exactly_the_two_keys():
    assert DISPLAY_KEY.fullmatch(ETF_NAME_KEY) and DISPLAY_KEY.fullmatch(ETF_RATE_KEY)
    for other in ("assumptions.etfProductCode", "assumptions.etfGainTaxRatePct ", "current.assumptions.etfProductName"):
        assert not DISPLAY_KEY.fullmatch(other), other


def test_ac1_non_string_etf_display_values_are_dropped_not_rejected():
    plan = load("plan-context-etf-tax-free.json")
    plan["display"][ETF_NAME_KEY] = 69500
    plan["display"][ETF_RATE_KEY] = None
    shown = model_input(make_request(plan, "QUESTION"))["display"]
    assert ETF_NAME_KEY not in shown and ETF_RATE_KEY not in shown


async def test_ac1_etf_display_keys_are_optional_not_required():
    plan = without(load("plan-context-etf-tax-free.json"), ETF_NAME_KEY, ETF_RATE_KEY)
    resp, claude = await run(make_request(plan, "EXPLAIN", plan["calcRunId"]), text_resp("설명이에요"))   # 502가 아니다
    assert resp.message_type == "EXPLANATION" and len(claude.calls) == 1


def test_ac1_raw_etf_fields_never_reach_the_model():
    plan = load("plan-context-etf-tax-free.json")
    text = json.dumps(build_messages(make_request(plan, "EXPLAIN", plan["calcRunId"])), ensure_ascii=False)
    for raw in ("etfProductCode", "etf_product_code", "069500", "etf_gain_tax_rate_pct", "\"etfGainTaxRatePct\""):
        assert raw not in text, raw
    assert COPY.get("tax.noteEtfTaxFree") not in text + SYSTEM_PROMPT   # 단서는 코드가 응답에 붙인다


# ---- AC-2: ETF 미사용 계획(회귀 보호: 지금도 성공해야 한다) ----

def test_ac2_etf_unused_plan_has_no_etf_keys_in_model_input():
    shown = model_input(make_request(etf_unused_plan(), "QUESTION"))
    assert ETF_NAME_KEY not in shown["display"] and ETF_RATE_KEY not in shown["display"]
    assert "etfProductName" not in json.dumps(shown) and "etfGainTaxRatePct" not in json.dumps(shown)
    assert shown["display"]["assumptions.taxRatePct"] == "15.4%"   # 적금 이자 세율은 별도 키에서 읽는다


async def test_ac2_etf_unused_plan_gets_only_tax_note():
    content = await answer_of(etf_unused_plan(), "적금 이자 세율은 15.4%로 가정했어요.")
    assert content.endswith(TAX_NOTE) and content.count(TAX_NOTE) == 1
    assert TAX_NOTE_FREE not in content


# ---- AC-3: 비과세 ETF 계획 ----

@pytest.mark.parametrize("raw_rate", [0, 0.0], ids=["int0", "float0"])
@pytest.mark.parametrize(
    "answer",
    [f"ETF 매매차익 세율은 {TAX_FREE_DISPLAY}이에요.", "적금 이자 세율은 15.4%로 가정했어요."],
    ids=["quotes-etf-rate", "quotes-savings-rate-only"],
)
async def test_ac3_tax_free_etf_gets_only_note_etf_tax_free(raw_rate: float, answer: str):
    plan = load("plan-context-etf-tax-free.json")
    plan["result"]["assumptions"]["etfGainTaxRatePct"] = raw_rate

    content = await answer_of(plan, answer)

    assert content.startswith(answer) and content.endswith(TAX_NOTE_FREE)   # 글자 그대로, 본문 끝에
    assert content.count(TAX_NOTE_FREE) == 1
    assert TAX_NOTE not in content


async def test_ac3_tax_free_note_already_written_by_model_is_not_duplicated():
    answer = f"ETF 매매차익 세율은 {TAX_FREE_DISPLAY}이에요. {TAX_NOTE_FREE}"
    assert await answer_of(load("plan-context-etf-tax-free.json"), answer) == answer


async def test_ac3_general_tax_note_written_by_model_is_replaced_by_the_tax_free_note():
    """비과세 계획에 일반 과세 안내가 보이는 모순을 막는다(사용자 결정 3, D-073)."""
    body = f"ETF 매매차익 세율은 {TAX_FREE_DISPLAY}이에요."
    content = await answer_of(load("plan-context-etf-tax-free.json"), f"{body} {TAX_NOTE}")
    assert content.count(TAX_NOTE_FREE) == 1 and TAX_NOTE not in content
    assert content.startswith(body)


async def test_ac3_no_note_when_answer_quotes_no_tax_rate():
    answer = "보통 경우에는 약 1,712만원이 돼요."
    assert await answer_of(load("plan-context-etf-tax-free.json"), answer) == answer


def test_ac3_model_input_has_both_rates_under_their_own_keys():
    shown = model_input(make_request(load("plan-context-etf-tax-free.json"), "QUESTION"))["display"]
    assert shown["assumptions.taxRatePct"] == "15.4%"   # 적금 이자 세율
    assert shown[ETF_RATE_KEY] == TAX_FREE_DISPLAY       # ETF 차익 세율
    assert shown[ETF_NAME_KEY] == "코스피200 추종 ETF"


async def test_ac3_note_etf_tax_free_of_previous_answer_is_not_sent_back_to_the_model():
    plan = load("plan-context-etf-tax-free.json")
    first = await answer_of(plan, f"ETF 매매차익 세율은 {TAX_FREE_DISPLAY}이에요.")
    assert TAX_NOTE_FREE in first

    history = [{"role": "user", "content": "세금 얼마 떼요?"}, {"role": "assistant", "content": first}]
    messages = build_messages(make_request(plan, "QUESTION", text="그럼요?", history=history))
    sent = "\n".join(str(m["content"]) for m in messages)
    body = f"ETF 매매차익 세율은 {TAX_FREE_DISPLAY}이에요."
    assert TAX_NOTE_FREE not in sent and body in sent   # 본문은 남고 단서만 빠진다

    only_note = [{"role": "user", "content": "첫 질문"}, {"role": "assistant", "content": TAX_NOTE_FREE}]
    messages = build_messages(make_request(plan, "QUESTION", text="다음 질문", history=only_note))
    assert [m["role"] for m in messages] == ["user"]


# ---- AC-4: 과세 종목은 동작 변화 없음 ----

@pytest.mark.parametrize("raw_rate", [15.4, None], ids=["raw-15.4", "raw-missing"])
async def test_ac4_taxable_etf_gets_tax_note_once(raw_rate: float | None):
    plan = taxable_plan(raw_rate)
    if raw_rate is None:
        del plan["result"]["assumptions"]["etfGainTaxRatePct"]   # raw 값이 없으면 과세로 본다
    answer = "ETF 차익 세율은 15.4%로 가정했어요."

    content = await answer_of(plan, answer)

    assert content.startswith(answer) and content.endswith(TAX_NOTE) and content.count(TAX_NOTE) == 1
    assert TAX_NOTE_FREE not in content


async def test_ac4_taxable_etf_does_not_duplicate_tax_note():
    answer = f"세금은 15.4%로 가정했어요. {TAX_NOTE}"
    assert await answer_of(taxable_plan(), answer) == answer


async def test_ac4_plan_without_etf_fields_keeps_old_behaviour():
    """raw etf* 필드가 아예 없는 기존 요청(basic 예시)도 tax.note만 붙는다."""
    plan = load("plan-context-basic.json")
    plan["result"]["assumptions"] = {k: v for k, v in plan["result"]["assumptions"].items() if k not in ETF_RAW_KEYS}
    content = await answer_of(plan, "세금은 15.4%로 가정했어요.")
    assert content.endswith(TAX_NOTE) and TAX_NOTE_FREE not in content


@pytest.mark.parametrize("plan_of", [taxable_plan, etf_unused_plan], ids=["taxable", "etf-unused"])
async def test_ac4_tax_free_note_written_by_model_is_replaced_by_the_general_tax_note(plan_of: Any):
    body = "세금은 15.4%로 가정했어요."
    content = await answer_of(plan_of(), f"{body} {TAX_NOTE_FREE}")
    assert content.count(TAX_NOTE) == 1 and TAX_NOTE_FREE not in content
    assert content.startswith(body)


@pytest.mark.parametrize("rate_key", [ETF_RATE_KEY, "assumptions.taxRatePct"], ids=["etf-rate", "savings-rate"])
async def test_ac4_empty_rate_display_is_not_a_tax_rate_quote(rate_key: str):
    """빈 문자열 표시값은 어떤 답에도 들어 있으므로 세율 인용 후보로 쓰지 않는다(사용자 결정 5)."""
    plan = load("plan-context-etf-tax-free.json")
    plan["display"][rate_key] = ""
    answer = "보통 경우에는 약 1,712만원이 돼요."
    assert await answer_of(plan, answer) == answer


# ---- AC-5·AC-6: 프롬프트 규칙 ----

def test_ac5_rule_8_splits_etf_gain_rate_and_savings_interest_rate():
    rules = bullets_with("assumptions.etfGainTaxRatePct")
    assert len(rules) == 1, rules
    rule = rules[0]
    assert re.search(r"ETF 차익 세율은[\s\S]{0,40}assumptions\.etfGainTaxRatePct", rule)
    assert re.search(r"적금 이자 세율은[\s\S]{0,40}assumptions\.taxRatePct", rule)
    assert "기억" in rule   # 기억 속 세율을 말하지 않는다는 기존 뜻 유지(D-060)


def test_ac5_no_rule_still_calls_tax_rate_pct_the_whole_plan_rate():
    for rule in bullets_with("assumptions.taxRatePct"):
        assert "적금 이자 세율" in rule, rule


def test_ac6_product_name_rule_uses_only_the_display_value():
    rules = bullets_with("assumptions.etfProductName")
    assert len(rules) == 1, rules
    rule = rules[0]
    assert "종목" in rule and "지어내지" in rule
    assert "없으면" in rule and "말하지 않" in rule   # 키가 없으면 종목명을 말하지 않는다


def test_ac6_prompt_tells_the_model_to_skip_etf_content_when_the_etf_display_keys_are_absent():
    """ETF 비중 0% 계획(표시 키 없음)에서는 종목·ETF 차익 세율을 말하지 않는다(정정 10, P-7)."""
    rules = bullets_with("assumptions.etfProductName") + bullets_with("assumptions.etfGainTaxRatePct")
    sentences = [x for rule in rules for x in re.split(r"(?<=[.])\s+", rule) if "없으면" in x]
    assert any("종목" in x and "ETF 차익 세율" in x and "말하지 않" in x for x in sentences), sentences


# ---- AC-7: 공유 계약 예시 ----

def plan_context_example_names() -> list[str]:
    return sorted(p.name for p in EXAMPLES.glob("*.json") if p.name.startswith(("plan-context-", "chat-request-")))


def plan_of(name: str) -> dict[str, Any] | None:
    raw = load(name)
    return raw if name.startswith("plan-context-") else raw["planContext"]


@pytest.mark.parametrize("name", plan_context_example_names())
def test_ac7_plan_context_examples_carry_etf_raw_fields_and_display_keys(name: str):
    plan = plan_of(name)
    if plan is None:
        pytest.skip("planContext가 null인 요청")
    assumptions = plan["result"]["assumptions"]
    assert all(k in assumptions for k in ETF_RAW_KEYS), assumptions

    if plan["result"]["status"] == "ALREADY_EXCEEDED":   # 계산을 생략하므로 ETF 필드가 없다(사용자 결정 4)
        assert all(assumptions[k] is None for k in ETF_RAW_KEYS)
        assert ETF_NAME_KEY not in plan["display"] and ETF_RATE_KEY not in plan["display"]
        return
    uses_etf = assumptions["etfProductName"] is not None
    assert uses_etf == (assumptions["savingsRatioPct"] < 100)   # ETF 비중이 있으면 종목이 있다
    if not uses_etf:
        assert ETF_NAME_KEY not in plan["display"]
        assert assumptions["etfProductCode"] is None and assumptions["etfGainTaxRatePct"] is None
        assert ETF_RATE_KEY not in plan["display"]
        return
    rate = assumptions["etfGainTaxRatePct"]
    assert assumptions["etfProductCode"] and plan["display"][ETF_NAME_KEY] == assumptions["etfProductName"]
    expected = TAX_FREE_DISPLAY if rate == 0 else f"{rate:g}%"
    assert plan["display"][ETF_RATE_KEY] == expected


@pytest.mark.parametrize("name", ["plan-context-exceeded.json", "chat-request-explain-exceeded.json"])
def test_ac9_exceeded_examples_keep_etf_ratio_but_have_no_etf_product_data(name: str):
    plan = plan_of(name)
    assert plan is not None and plan["result"]["status"] == "ALREADY_EXCEEDED"
    assert plan["result"]["assumptions"]["savingsRatioPct"] == 50
    assert all(plan["result"]["assumptions"][k] is None for k in ETF_RAW_KEYS)
    assert ETF_NAME_KEY not in plan["display"] and ETF_RATE_KEY not in plan["display"]


async def test_ac9_exceeded_explain_answers_with_copy_and_no_llm_even_with_etf_ratio():
    req = ChatRequest.model_validate(load("chat-request-explain-exceeded.json"))
    resp, claude = await run(req, text_resp("호출되면 안 돼요"))
    assert claude.calls == [] and resp.content == COPY.get("chat.alreadyExceeded")


def test_ac7_tax_free_example_is_the_non_taxable_boundary_case():
    assumptions = load("plan-context-etf-tax-free.json")["result"]["assumptions"]
    assert assumptions["etfProductCode"] == "069500" and assumptions["etfProductName"] == "코스피200 추종 ETF"
    assert assumptions["etfGainTaxRatePct"] == 0 and assumptions["taxRatePct"] == 15.4


def test_ac7_representative_examples_use_the_taxable_product():
    for name in ("plan-context-basic.json", "plan-context-achieved.json"):
        assumptions = load(name)["result"]["assumptions"]
        assert assumptions["etfGainTaxRatePct"] == 15.4, name


def test_ac7_internal_calc_example_adds_raw_fields_only_and_no_etf_display_keys():
    body = json.loads((DOCS_API / "examples" / "internal-calc" / "success-response.json").read_text(encoding="utf-8"))
    for side in ("current", "changed"):
        assert all(k in body[side]["assumptions"] for k in ETF_RAW_KEYS), side
    assert not [k for k in body["display"] if "etf" in k.lower()]   # 비교 display에는 ETF 키를 더하지 않는다


def test_ac7_every_plan_example_still_validates_as_chat_request():
    for name in plan_context_example_names():
        if name.startswith("chat-request-"):
            ChatRequest.model_validate(load(name))


# ---- AC-8: 계약 문서 확정 표시 ----

def doc_text() -> str:
    return (DOCS_API / "plan-context.md").read_text(encoding="utf-8")


def test_ac8_doc_has_no_pending_agreement_marker():
    assert "합의 대기" not in doc_text()


def test_ac8_doc_allowed_pattern_lists_the_two_optional_etf_keys():
    patterns = [line.strip() for line in doc_text().splitlines() if line.strip().startswith("(goalAmount|")]
    assert len(patterns) == 1
    pattern = re.compile(patterns[0])
    assert pattern.fullmatch(ETF_NAME_KEY) and pattern.fullmatch(ETF_RATE_KEY)
    assert pattern.fullmatch("assumptions.taxRatePct") and not pattern.fullmatch("assumptions.etfProductCode")
    for key in ("goalAmount", "AVG.afterTaxAsset", "conditions.heldAssets", "assumptions.sp500BaseDate"):
        assert bool(pattern.fullmatch(key)) == bool(DISPLAY_KEY.fullmatch(key)), key


def test_ac8_doc_says_etf_keys_are_optional_and_rule_8_is_split():
    doc = doc_text()
    required_row = next(line for line in doc.splitlines() if line.startswith("| `CALCULATED` |"))
    assert "etfProductName" not in required_row and "etfGainTaxRatePct" not in required_row   # 요구 키가 아니다
    rule_8 = next(line for line in doc.splitlines() if line.startswith("| 8 |"))
    assert "assumptions.etfGainTaxRatePct" in rule_8 and "assumptions.taxRatePct" in rule_8 and "적금 이자 세율" in rule_8
    assert "plan-context-etf-tax-free.json" in doc   # 예시 표에 새 파일이 있다


def test_ac8_doc_records_the_independent_number_rule_and_drops_the_old_replace_always_sentence():
    doc = doc_text()
    assert "독립된 숫자" in doc   # 세율 인용 판정(사용자 결정 5, 정정 11)
    assert "세율 인용 여부와 상관없이" not in doc   # 인용 없는 답에는 단서를 붙이지도 바꾸지도 않는다(P-6)


def test_ac8_doc_says_etf_unused_plans_do_not_mention_the_product():
    lines = [x for x in doc_text().splitlines() if "etfProductName" in x and ("생략" in x or "말하지 않" in x)]
    assert any("ETF 0%" in x or "ETF 미사용" in x or "ETF 비중 0%" in x for x in lines), lines


def test_ac11_doc_records_the_allowed_etf_set_and_replacement_examples():
    """허용 ETF 세 표현과 대체 예 허용 정책을 적는다. 수정 전 CP-06·D-028과 다르다는 점도 남긴다(결정 13)."""
    doc = doc_text()
    for name in ("코스피200 추종 ETF", "S&P500 추종 ETF", "나스닥 추종 ETF"):
        assert name in doc, name
    lines = [x for x in doc.splitlines() if "ALLOWED_ETF_NAMES" in x or ("허용" in x and "대체 예" in x)]
    assert lines, "허용 ETF 집합과 대체 예 문장이 없다"
    assert any("CP-06" in x or "D-028" in x for x in lines), lines


def test_ac11_doc_records_that_etf_unused_plans_reject_etf_mentions_without_retry():
    """ETF 0% 계획의 ETF 종목 언급은 문장을 지우지 않고 재시도 없이 거부한다(결정 14·18). 삭제 설명은 남기지 않는다."""
    doc = doc_text()
    lines = [x for x in doc.splitlines() if "ETF 0%" in x and "재시도" in x and "FORBIDDEN_EXPRESSION" in x]
    assert lines, "ETF 0% 계획의 재시도 없는 거부를 적지 않았다"
    assert not [x for x in doc.splitlines() if "ETF 0%" in x and ("지운다" in x or "지워" in x or "제거" in x)]
    assert "문장을 본문에서 지운다" not in doc and "지운 뒤" not in doc


def test_ac11_doc_records_that_an_empty_body_after_correction_is_rejected():
    doc = doc_text()
    assert [x for x in doc.splitlines() if "본문" in x and ("비면" in x or "빈 본문" in x) and "FORBIDDEN_EXPRESSION" in x]


def test_ac11_doc_records_fullwidth_digits_in_the_independent_number_rule():
    doc = doc_text()
    assert [x for x in doc.splitlines() if "독립된 숫자" in x and "전각" in x]


def test_ac11_doc_records_that_a_wrong_note_is_replaced_once():
    doc = doc_text()
    assert [x for x in doc.splitlines() if "tax.noteEtfTaxFree" in x and "tax.note`" in x and "교체" in x]
