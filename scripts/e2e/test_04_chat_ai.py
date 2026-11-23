"""S5. AI 설명·대화 패널 (결과 화면 ①의 오른쪽). ai-server + LLM 키 필요.

LLM 문장은 매번 달라서 문장을 비교하지 않는다. 대신 확인하는 것:
  - 메시지 종류(messageType)가 맞는가
  - AI가 계산 도구를 '올바른 값으로' 불렀는가 → 비교 조회(F-4)의 conditions로 확인
  - 문장 속 'N만원'이 모두 Spring display(또는 사용자 질문)에 있는가 → AI가 숫자를 지어내지 않았는가
LLM 시나리오는 가끔 흔들릴 수 있다. 실패하면 메시지 본문(assert 메시지)을 보고 프롬프트 문제인지 판단한다.
"""
import pytest

from conftest import WIREFRAME_PLAN, ask, current_view, expect, explain, ratio_of, save_plan_ok
from oracle import unknown_numbers

pytestmark = [pytest.mark.ai, pytest.mark.usefixtures("ai_ready")]
P = WIREFRAME_PLAN


@pytest.fixture
def fresh(api):
    """대화 기록을 비우고(재로그인) 와이어프레임 계획으로 시작."""
    expect(api.login(), 200)
    return save_plan_ok(api)


def comparison(api, message_id):
    return expect(api.get(f"/api/chat/messages/{message_id}/comparison"), 200)


# ---------- 설명 ----------

def test_s5_1_explanation_uses_only_calc_numbers(api, fresh):
    """결과 화면을 열면 AI 설명이 뒤이어 도착. 숫자는 계산 엔진 것만."""
    msg = explain(api, fresh["calcRunId"])
    assert msg["status"] == "COMPLETED", msg
    assert msg["messageType"] == "EXPLANATION", msg
    bad = unknown_numbers(msg["content"], fresh["display"])
    assert not bad, f"설명에 계산 결과에 없는 금액 {sorted(bad)}만원\n{msg['content']}"


def test_s5_2_explanation_cites_source_for_etf(api, fresh):
    """ETF가 섞인 계획의 위험 설명에는 출처(공시 문서)가 붙는다(와이어프레임 'ETF 위험 설명 + 출처')."""
    if ratio_of(fresh) == 100:
        pytest.skip("ETF 0% 계획")
    msg = explain(api, fresh["calcRunId"])
    assert msg["citations"], f"출처 없음. RAG 문서를 적재했는지 확인(scripts/ingest)\n{msg['content']}"
    for c in msg["citations"]:
        assert c["title"] and c["sourceUrl"] and c["orgName"], c


def test_s5_3_explanation_for_exceeded_plan_is_fixed_sentence(api):
    """이미 초과한 계획은 LLM 없이 고정 문장(프론트는 원래 F-3을 부르지 않음, 방어 동작 확인)."""
    expect(api.login(), 200)
    view = save_plan_ok(api, heldAssets=60_000_000)
    msg = explain(api, view["calcRunId"])
    assert msg["messageType"] == "EXPLANATION" and msg["status"] == "COMPLETED", msg
    assert "초과" in msg["content"] or "넘" in msg["content"], msg["content"]


def test_s5_4_explanation_of_unknown_run_is_404(api, fresh):
    expect(api.post("/api/chat/explanations", json={"calcRunId": 999_999_999}), 404, "NOT_FOUND")


# ---------- 조건 바꿔 다시 계산 ----------

def test_s5_5_recalc_monthly_80(api, fresh):
    """'월 80만 원이면요?' → 계산 엔진이 월 80만으로 다시 계산, AI는 그 숫자만 말함."""
    q = "월 저축액을 80만원으로 하면 어떻게 돼요?"
    msg = ask(api, q)
    assert msg["messageType"] == "RECALC_RESULT", f"{msg['messageType']}: {msg['content']}"

    cmp = comparison(api, msg["id"])
    assert cmp["changedKeys"] == ["monthlySavingAmount"], cmp["changedKeys"]
    assert cmp["conditions"]["changed"]["monthlySavingAmount"] == 800_000
    assert cmp["conditions"]["current"]["monthlySavingAmount"] == P["monthlySavingAmount"]
    assert cmp["changed"]["scenarios"][0]["principal"] == P["heldAssets"] + 800_000 * 36
    assert cmp["applicable"] is True

    bad = unknown_numbers(msg["content"], cmp["display"], fresh["display"], q)
    assert not bad, f"AI 문장에 계산 결과에 없는 금액 {sorted(bad)}만원\n{msg['content']}"


def test_s5_6_changes_do_not_accumulate_then_apply_and_undo(api, fresh):
    """두 번째 질문은 '현재 계획' 기준(D-041). 최신 비교만 적용 가능. 적용 → 되돌리기."""
    first = ask(api, "월 80만원으로 하면요?")
    assert first["messageType"] == "RECALC_RESULT", first["content"]
    second = ask(api, "그럼 목표 기간을 4년으로 하면요?")
    assert second["messageType"] == "RECALC_RESULT", second["content"]

    cmp2 = comparison(api, second["id"])
    assert cmp2["changedKeys"] == ["targetPeriodMonths"], f"변경이 누적됨: {cmp2['changedKeys']}"
    assert cmp2["conditions"]["changed"]["targetPeriodMonths"] == 48
    assert cmp2["conditions"]["changed"]["monthlySavingAmount"] == P["monthlySavingAmount"], "월 80만이 이어짐(누적)"

    # 이전 비교는 적용 불가
    assert comparison(api, first["id"])["applicable"] is False
    rev = fresh["revision"]
    expect(api.post(f"/api/chat/messages/{first['id']}/apply", json={"expectedRevision": rev}),
           409, "STALE_COMPARISON")

    # 최신 비교 적용
    applied = expect(api.post(f"/api/chat/messages/{second['id']}/apply", json={"expectedRevision": rev}), 200)
    assert applied["revision"] == rev + 1
    view = current_view(api)
    assert view["plan"]["targetPeriodMonths"] == 48
    assert view["plan"]["monthlySavingAmount"] == P["monthlySavingAmount"]
    assert comparison(api, second["id"])["applicable"] is False, "적용한 뒤에는 같은 비교를 다시 적용할 수 없음"

    # 같은 버튼 두 번(옛 revision)
    expect(api.post(f"/api/chat/messages/{second['id']}/apply", json={"expectedRevision": rev}),
           409, "PLAN_CHANGED")

    # 되돌리기
    undone = expect(api.post("/api/plan/undo", json={"expectedRevision": applied["revision"]}), 200)
    view = current_view(api)
    assert undone["revision"] == view["revision"]
    assert view["plan"]["targetPeriodMonths"] == 36
    assert view["result"]["scenarios"] == fresh["result"]["scenarios"], "되돌린 뒤 숫자가 원래와 다름"
    assert comparison(api, second["id"])["applicable"] is False, "되돌린 뒤에도 옛 비교는 비활성(D-068)"


# ---------- 되묻기·범위 밖 ----------

@pytest.mark.parametrize("text", [
    "조금 더 많이 모으면 어때요?",       # 상대 표현 → 절대값을 되묻는다(D-059)
    "월 2천만원씩 넣으면요?",            # 월 저축액 상한(1,000만원) 밖 → 범위 안내하며 되묻기
])
def test_s5_7_clarify(api, fresh, text):
    msg = ask(api, text)
    assert msg["messageType"] == "CLARIFY", f"{msg['messageType']}: {msg['content']}"
    bad = unknown_numbers(msg["content"], fresh["display"], text, "1만원 1,000만원")
    assert not bad, f"되묻기에서 지어낸 금액 {sorted(bad)}만원\n{msg['content']}"


def test_s5_8_individual_stock_is_out_of_scope(api, fresh):
    """개별 종목 추천은 하지 않는다."""
    msg = ask(api, "삼성전자 주식 지금 사도 될까요?")
    assert msg["messageType"] == "OUT_OF_SCOPE", f"{msg['messageType']}: {msg['content']}"


def test_s5_9_one_question_at_a_time(api, fresh):
    """답을 기다리는 중에 또 보내면 409 ANSWER_PENDING(보내기 버튼 비활성의 서버 방어선)."""
    first = expect(api.post("/api/chat/messages", json={"text": "월 90만원이면요?"}), 202)
    expect(api.post("/api/chat/messages", json={"text": "월 100만원이면요?"}), 409, "ANSWER_PENDING")
    from conftest import wait_settled
    wait_settled(api, first["userMessageId"] - 1)


def test_s5_10_question_without_plan_numbers_on_exceeded(api):
    """초과 계획에서 조건 변경 질문 → 비교 없이 고정 문장(D-071)."""
    expect(api.login(), 200)
    save_plan_ok(api, heldAssets=60_000_000)
    msg = ask(api, "월 80만원으로 하면요?")
    assert msg["messageType"] != "RECALC_RESULT", f"초과 계획인데 비교가 생김: {msg}"
