"""S7. 여러 페르소나 x 여러 말투로 조건을 바꿔 묻기 (대화형 재분석, 목표 6). ai-server + LLM 필요.

질문마다: AI가 계산 도구를 '그 조건 하나만, 그 값으로' 불렀는지(F-4 conditions·changedKeys),
결과 숫자가 Spring 계산과 같은지(문장 속 N만원 ⊂ display), 계획 기준이 누적되지 않는지 본다.
"""
import pytest

from conftest import ask, expect, explain, save_plan_ok
from oracle import unknown_numbers
from scenarios import CHAT_CASES, CHAT_PERSONAS, PERSONAS

pytestmark = [pytest.mark.ai, pytest.mark.usefixtures("ai_ready")]
BY_ID = {p["id"]: p for p in PERSONAS}
CASES = [(pid, c) for pid in CHAT_PERSONAS for c in CHAT_CASES]


@pytest.mark.parametrize("pid, case", CASES, ids=[f"{pid}-{c[0]}" for pid, c in CASES])
def test_s7_1_condition_change_by_chat(api, pid, case):
    _, make_q, key, make_v = case
    plan = BY_ID[pid]["plan"]
    expect(api.login(), 200)                       # 대화 기록 비우기
    view = save_plan_ok(api, **plan)
    question, value = make_q(plan), make_v(plan)

    msg = ask(api, question)
    assert msg["messageType"] == "RECALC_RESULT", f"[{question}] → {msg['messageType']}: {msg['content']}"

    cmp = expect(api.get(f"/api/chat/messages/{msg['id']}/comparison"), 200)
    assert cmp["changedKeys"] == [key], f"[{question}] 바뀐 키 {cmp['changedKeys']}, 기대 [{key}]"
    got = cmp["conditions"]["changed"][key]
    assert (float(got) if key == "savingsRateInputPct" else got) == value, \
        f"[{question}] {key} = {got}, 기대 {value}"

    bad = unknown_numbers(msg["content"], cmp["display"], view["display"], question)
    assert not bad, f"[{question}] AI 문장에 계산 결과에 없는 금액 {sorted(bad)}만원\n{msg['content']}"


@pytest.mark.parametrize("pid", CHAT_PERSONAS + ["P05", "P09"])
def test_s7_2_explanation_matches_judgement(api, pid):
    """설명이 계산 판정과 반대로 말하지 않는다(미달인데 '달성할 수 있어요' 등)."""
    expect(api.login(), 200)
    view = save_plan_ok(api, **BY_ID[pid]["plan"])
    msg = explain(api, view["calcRunId"])
    assert msg["messageType"] == "EXPLANATION", msg
    assert not unknown_numbers(msg["content"], view["display"]), msg["content"]

    base = next(s for s in view["result"]["scenarios"] if s["scenario"] == view["baselineResultScenario"])
    text = msg["content"]
    if base["isAchieved"]:
        assert "달성" in text or "넘" in text, f"달성 계획인데 설명에 달성 언급 없음\n{text}"
    else:
        assert any(w in text for w in ("부족", "미달", "모자")), f"미달 계획인데 설명에 부족 언급 없음\n{text}"
