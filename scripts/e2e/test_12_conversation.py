"""S8. 실제 대화에서 나온 실패를 다중 턴으로 되풀이한다(#234). ai-server + LLM 필요.

출처가 된 실패:
  #227 목표 금액 변경 비교(F-4)가 500       → 목표 금액 비교는 200이고 changedKeys가 goalAmount
  #231 '1억5천만원'·금액만 답하기를 못 알아들음 → 여러 금액 표현, 되묻기 뒤 금액만 답해도 계산
  #229 묻지 않은 것까지 길게 답함              → 값 조회는 짧게, 조건 변경 답도 짧게
  #233 세금 안내 문장이 대화마다 반복          → 대화 답에 세금 안내 없음
  #239 역할 단어 없는 후속 금액에 되묻기 반복   → 직전 대화의 조건으로 계산
  #267 대화로 ETF 종목 바꾸기(D-097)          → 목록 안 종목만 계산, ETF 0%는 한 문장, 적용 불가(#268)
LLM 문장은 매번 달라서 글자를 비교하지 않는다. 메시지 종류, F-4 conditions, 문장 수, 금지 문장만 본다.
실패 메시지에는 본문 전체를 넣어 프롬프트 문제인지 바로 판단할 수 있게 한다.
"""
import re

import pytest

from conftest import ask, current_view, expect, explain, save_plan_ok
from oracle import unknown_numbers

pytestmark = [pytest.mark.ai, pytest.mark.usefixtures("ai_ready")]

ONE_AND_HALF_EOK = 150_000_000

# 서버가 본문 뒤에 붙이는 안내 문장. 답 길이를 셀 때 뺀다(문구표 chat.goalAmountChangedNote·changedAlreadyExceeded)
_SERVER_NOTES = [
    re.compile(r"목표 금액이 .+?(?:으로|로) 바뀌어, 달성 여부는 각각 자기 목표 기준이에요\.?"),
    re.compile(r"이 조건으로 적용하면 이미 목표를 넘은 상태가 돼요\.?"),
]
# 화면이 고정으로 고지하는 내용(docs/chat-response-guide.md 1절). 대화 답에 나오면 안 된다(#233, #254).
# 같은 뜻을 말을 바꿔 쓴 것까지 다 잡지는 못한다(기준표 Q2). 새 고지 문구가 생기면 여기에 한 줄 더한다.
# "원금 손실"은 ETF 위험 설명에 필요한 답이라 넣지 않는다
_NOTICE_PHRASES = (
    "세금은 일반 과세를 기준으로 가정했어요",                 # tax.note
    "매매차익은 비과세로, 적금 이자는 일반 과세를 기준으로",   # tax.noteEtfTaxFree
    "과거 데이터로 만든 추정",                                # disclaimer와 같은 뜻
    "수익을 약속하지 않",
    "수익을 보장하지 않",
    "결과를 보장하지 않",
    "모의 계산",
    "투자 결정은 본인",
    "참고용",
    "미래를 예측한 값이 아니",                                # result.scenarioNotice
)
# 결과 설명이 나열하면 안 되는 입력 조건의 display 키(조건 바에 있다, 기준표 Q3). 목표 금액은 '목표 대비'에 쓰일 수 있어 뺀다
_CONDITION_KEYS = (
    "conditions.monthlySavingAmount",
    "conditions.targetPeriodMonths",
    "conditions.savingsRatioPct",
    "conditions.savingsRateInputPct",
)


def body_sentences(content: str) -> list[str]:
    """서버 안내 문장을 뺀 본문 문장. '.', '?', '!'와 줄바꿈으로 나눈다(소수점 3.2%는 나누지 않는다)."""
    for note in _SERVER_NOTES:
        content = note.sub("", content)
    parts = re.split(r"(?<!\d)[.?!](?!\d)|\n+", content)
    return [p.strip() for p in parts if p.strip()]


def assert_no_notice(msg: dict) -> None:
    found = [p for p in _NOTICE_PHRASES if p in msg["content"]]
    assert not found, f"화면 고정 고지를 대화 답에서 반복함 {found}(기준표 1절)\n{msg['content']}"


def comparison(api, message_id):
    return expect(api.get(f"/api/chat/messages/{message_id}/comparison"), 200)


@pytest.fixture
def fresh(api):
    """대화 기록을 비우고(재로그인) 와이어프레임 계획(목표 5,000만원)으로 시작."""
    expect(api.login(), 200)
    return save_plan_ok(api)


# ---------- #231·#227 목표 금액 표현 ----------

GOAL_EXPRESSIONS = [
    "1억5천만원",
    "1억 5천만원",
    "1억 5000만원",
    "15000만원",
    "15,000만원",
    "1.5억",
    "일억 오천만원",
]


@pytest.mark.parametrize("amount", GOAL_EXPRESSIONS)
def test_s8_1_goal_amount_expressions(api, fresh, amount):
    q = f"목표 금액을 {amount}으로 바꾸면 어떻게 돼요?"
    msg = ask(api, q)
    assert msg["messageType"] == "RECALC_RESULT", f"[{q}] → {msg['messageType']}: {msg['content']}"

    cmp = comparison(api, msg["id"])   # #227: 목표 금액 비교가 500이면 여기서 실패
    assert cmp["changedKeys"] == ["goalAmount"], f"[{q}] 바뀐 키 {cmp['changedKeys']}"
    assert cmp["conditions"]["changed"]["goalAmount"] == ONE_AND_HALF_EOK, cmp["conditions"]["changed"]
    bad = unknown_numbers(msg["content"], cmp["display"], fresh["display"], q)
    assert not bad, f"[{q}] 계산 결과에 없는 금액 {sorted(bad)}만원\n{msg['content']}"


def test_s8_2_bare_amount_after_clarify(api, fresh):
    """값 없이 조건만 고르면 되묻고, 금액만 답해도 그 조건으로 계산한다(#231)."""
    first = ask(api, "목표 금액을 바꿔 보고 싶어요")
    assert first["messageType"] == "CLARIFY", f"{first['messageType']}: {first['content']}"

    second = ask(api, "15000만원")
    assert second["messageType"] == "RECALC_RESULT", f"{second['messageType']}: {second['content']}"
    cmp = comparison(api, second["id"])
    assert cmp["changedKeys"] == ["goalAmount"], cmp["changedKeys"]
    assert cmp["conditions"]["changed"]["goalAmount"] == ONE_AND_HALF_EOK


def test_s8_3_follow_up_amount_without_condition_word(api, fresh):
    """앞에서 목표 금액을 바꿔 본 뒤 '그럼 N까지 하려면 얼마나 걸려'도 목표 금액으로 계산한다(#231)."""
    first = ask(api, "목표 금액을 1억으로 바꾸면요?")
    assert first["messageType"] == "RECALC_RESULT", f"{first['messageType']}: {first['content']}"

    second = ask(api, "그럼 15,000만원까지 하려면 얼마나 걸려?")
    assert second["messageType"] == "RECALC_RESULT", f"{second['messageType']}: {second['content']}"
    cmp = comparison(api, second["id"])
    assert cmp["conditions"]["changed"]["goalAmount"] == ONE_AND_HALF_EOK, cmp["conditions"]["changed"]
    # 비교의 기준은 늘 내 계획이다(D-041). 앞 턴의 1억을 기준으로 쌓지 않는다
    assert cmp["conditions"]["current"]["goalAmount"] == fresh["plan"]["goalAmount"]


# ---------- #239 역할 단어 없는 후속 금액 ----------

ONE_EOK = 100_000_000
# 보유 자산을 가리키는 말. "바꿀 수 없다"는 안내 속 언급은 선택지가 아니다
_HELD_ASSETS = re.compile(r"보유\s*자산|이미\s*모은\s*돈|모아\s*둔\s*돈")


def assert_goal_recalc(api, msg, amount):
    assert msg["messageType"] == "RECALC_RESULT", f"{msg['messageType']}: {msg['content']}"
    cmp = comparison(api, msg["id"])
    assert cmp["changedKeys"] == ["goalAmount"], cmp["changedKeys"]
    assert cmp["conditions"]["changed"]["goalAmount"] == amount, cmp["conditions"]["changed"]


def test_s8_8a_amount_after_goal_lookup(api, fresh):
    """목표 금액을 물은 바로 뒤 '1억으로 바꿀게'는 되묻지 않고 목표 금액으로 계산한다(#239)."""
    first = ask(api, "목표금액 얼마로 해뒀지?")
    assert first["messageType"] == "ANSWER", f"{first['messageType']}: {first['content']}"
    assert_goal_recalc(api, ask(api, "1억으로 바꿀게"), ONE_EOK)


def test_s8_8b_condition_only_answer_to_role_question(api, fresh):
    """대화 첫 발화의 역할 없는 금액은 되묻고(보유 자산은 선택지에 없음), 조건만 답하면 그 금액으로 계산한다(#239)."""
    first = ask(api, "1억으로 바꿀게")
    assert first["messageType"] == "CLARIFY", f"{first['messageType']}: {first['content']}"
    offers_held = _HELD_ASSETS.search(first["content"]) and "바꿀 수 없" not in first["content"]
    assert not offers_held, f"바꿀 수 없는 보유 자산을 선택지로 냄(D-090)\n{first['content']}"
    assert_goal_recalc(api, ask(api, "목표 금액"), ONE_EOK)


def test_s8_8e_pointer_answer_to_role_question(api, fresh):
    """역할 없는 금액을 되물은 뒤 '모으는 돈이요'처럼 가리키는 말로 답해도 목표 금액으로 계산한다(#270)."""
    first = ask(api, "1억으로 바꿀게")
    assert first["messageType"] == "CLARIFY", f"{first['messageType']}: {first['content']}"
    assert_goal_recalc(api, ask(api, "모으는 돈이요"), ONE_EOK)


def test_s8_8f_bare_pointer_after_listed_choices_asks_again(api, fresh):
    """두 조건 이상을 나열한 되묻기에 '그거요'만 답하면 어느 것인지 몰라 다시 되묻는다(#270)."""
    first = ask(api, "1억으로 바꿀게")
    assert first["messageType"] == "CLARIFY", f"{first['messageType']}: {first['content']}"
    second = ask(api, "그거요")
    assert second["messageType"] == "CLARIFY", f"{second['messageType']}: {second['content']}"


def test_s8_8d_follow_up_amount_does_not_carry_previous_value(api, fresh):
    """앞에서 월 저축액을 바꿔 본 뒤 목표 금액을 묻고 '1억으로 바꿀게'면 목표 금액만 바꾼다(누적 없음, D-041, #239)."""
    first = ask(api, "매달 150만원으로 하면요?")
    assert first["messageType"] == "RECALC_RESULT", f"{first['messageType']}: {first['content']}"
    second = ask(api, "목표금액 얼마야?")
    assert second["messageType"] == "ANSWER", f"{second['messageType']}: {second['content']}"
    assert_goal_recalc(api, ask(api, "1억으로 바꿀게"), ONE_EOK)


def test_s8_8c_omitted_man_amount_after_value_question(api, fresh):
    """목표 금액 값을 되물은 뒤 '1억 2천'만 답해도 목표 금액 1억 2천만원으로 계산한다(#236, #239)."""
    first = ask(api, "목표 금액을 바꿔 보고 싶어요")
    assert first["messageType"] == "CLARIFY", f"{first['messageType']}: {first['content']}"
    assert_goal_recalc(api, ask(api, "1억 2천"), 120_000_000)


# ---------- #229 답 길이 · #233 세금 안내 ----------

@pytest.mark.parametrize("q", ["적금 금리 몇이야?", "지금 월 저축액이 얼마예요?", "적금 이자 세율이 몇 %예요?"])
def test_s8_4_value_lookup_is_short(api, fresh, q):
    msg = ask(api, q)
    assert msg["messageType"] == "ANSWER", f"[{q}] → {msg['messageType']}: {msg['content']}"
    sentences = body_sentences(msg["content"])
    assert len(sentences) <= 2, f"[{q}] 값 하나를 묻는데 {len(sentences)}문장(#229)\n{msg['content']}"
    assert_no_notice(msg)
    bad = unknown_numbers(msg["content"], fresh["display"], q)
    assert not bad, f"[{q}] 계획에 없는 금액 {sorted(bad)}만원\n{msg['content']}"


@pytest.mark.parametrize("q", ["월 저축액을 150만원으로 하면요?", "목표 금액 1억으로", "기간을 4년으로 하면 어때요?"])
def test_s8_5_recalc_answer_is_short(api, fresh, q):
    msg = ask(api, q)
    assert msg["messageType"] == "RECALC_RESULT", f"[{q}] → {msg['messageType']}: {msg['content']}"
    sentences = body_sentences(msg["content"])
    assert len(sentences) <= 3, f"[{q}] 조건 변경 답이 {len(sentences)}문장(#229)\n{msg['content']}"
    assert_no_notice(msg)


# ---------- ETF 종목 (S&P500·나스닥100·코스피200) ----------

@pytest.mark.parametrize("q", ["나스닥100 ETF는 어떤 위험이 있어요?", "코스피200 ETF는 어떤 위험이 있어요?"])
def test_s8_6_other_etf_risk_has_sources(api, fresh, q):
    """나스닥100·코스피200 공시도 근거로 찾는다(#181). 실패하면 scripts/ingest/ingest.sh로 적재했는지 본다."""
    msg = ask(api, q)
    assert msg["messageType"] == "ANSWER", f"[{q}] → {msg['messageType']}: {msg['content']}"
    assert msg["citations"], f"[{q}] 출처 없음. 공시 4건을 적재했는지 확인(scripts/ingest/ingest.sh)\n{msg['content']}"
    assert "찾을 수 있는 자료가 없" not in msg["content"], msg["content"]
    assert_no_notice(msg)


def test_s8_7_etf_product_change_is_recalculated(api, fresh):
    """대화로 종목을 바꾼다(D-097, #267). 계획(S&P500)과 다른 종목으로 계산하고, 그 비교를 계획에 적용할 수 있다(#154)."""
    msg = ask(api, "ETF를 나스닥으로 바꾸고 싶어")
    assert msg["messageType"] == "RECALC_RESULT", f"종목 변경을 계산하지 않음: {msg['content']}"
    cmp = comparison(api, msg["id"])
    assert cmp["conditions"]["changed"].get("etfProductCode") == "133690", cmp["conditions"]
    assert cmp["applicable"] is True, "종목을 바꾼 비교는 계획에 적용할 수 있다(#154)"
    assert "추천" not in msg["content"], msg["content"]
    assert len(body_sentences(msg["content"])) <= 3, msg["content"]
    assert_no_notice(msg)

    # F-5로 적용하면 계획 종목이 바뀐다(#154). 입력 저장(fresh)은 종목을 되돌리지 않으므로 다음 테스트를 위해 되돌린다
    rev = fresh["revision"]
    applied = expect(api.post(f"/api/chat/messages/{msg['id']}/apply", json={"expectedRevision": rev}), 200)
    try:
        assert applied["revision"] == rev + 1
        assert current_view(api)["plan"]["etfProductCode"] == "133690"
    finally:
        expect(api.post("/api/plan/undo", json={"expectedRevision": applied["revision"]}), 200)


# ---------- S8-11 대화로 종목 바꾸기 (D-097, #267) ----------

def test_s8_11a_product_and_monthly_saving_together(api, fresh):
    msg = ask(api, "코스피200으로 바꾸고 월 150만원 넣으면?")
    assert msg["messageType"] == "RECALC_RESULT", f"{msg['messageType']}: {msg['content']}"
    changed = comparison(api, msg["id"])["conditions"]["changed"]
    assert changed.get("etfProductCode") == "069500", changed
    assert changed.get("monthlySavingAmount") == 1_500_000, changed


def test_s8_11b_follow_up_product_does_not_carry_previous_value(api, fresh):
    ask(api, "월 저축액을 150만원으로 하면요?")
    msg = ask(api, "그럼 코스피200으로 바꾸면?")
    assert msg["messageType"] == "RECALC_RESULT", f"{msg['messageType']}: {msg['content']}"
    changed = comparison(api, msg["id"])["conditions"]["changed"]
    assert changed.get("etfProductCode") == "069500", changed
    assert changed.get("monthlySavingAmount") == fresh["plan"]["monthlySavingAmount"], (
        f"앞 턴의 월 저축액을 이어받음(D-041) {changed}")


def test_s8_11c_etf_zero_says_product_does_not_matter(api, fresh):
    """ETF 0% 계획에서 종목만 바꾸면 결과가 같다고 한 문장으로 답한다(D-097 합의 4)."""
    save_plan_ok(api, savingsRatioPct=100)
    msg = ask(api, "코스피200으로 바꾸면?")
    assert msg["messageType"] == "RECALC_RESULT", f"{msg['messageType']}: {msg['content']}"
    assert "결과는 같" in msg["content"], msg["content"]
    assert len(body_sentences(msg["content"])) <= 2, msg["content"]


def test_s8_11f_etf_zero_with_ratio_change_explains_the_result(api, fresh):
    """비중을 함께 바꿔 ETF 0%가 된 비교는 결과가 달라지므로 '결과는 같아요'로 끝내지 않는다."""
    msg = ask(api, "적금 100%로 하고 코스피200으로 바꾸면?")
    assert msg["messageType"] == "RECALC_RESULT", f"{msg['messageType']}: {msg['content']}"
    assert "결과는 같" not in msg["content"], msg["content"]


@pytest.mark.parametrize("q", ["코스닥150으로 바꾸면?", "삼성전자로 바꿔 줘"])
def test_s8_11d_product_outside_the_list_asks_again(api, fresh, q):
    """S&P500·코스피200·나스닥100 밖의 종목은 계산하지 않고 되묻는다. 어느 종목이 낫다고 권하지 않는다."""
    msg = ask(api, q)
    assert msg["messageType"] != "RECALC_RESULT", f"[{q}] 목록 밖 종목을 계산함: {msg['content']}"
    assert "추천" not in msg["content"], msg["content"]


def test_s8_11e_same_product_as_plan_is_not_a_comparison(api, fresh):
    msg = ask(api, "S&P500으로 바꾸면?")
    assert msg["messageType"] != "RECALC_RESULT", f"계획과 같은 종목으로 비교함: {msg['content']}"


# ---------- #254 대화 응답 기준표: 결과 설명·수익 질문 ----------

def test_s8_9_explanation_is_short_without_inputs_or_notice(api, fresh):
    """결과 화면의 자동 설명은 평균 결과·목표 대비·달성 여부만 3문장 이하로(기준표 3절, Q3)."""
    msg = explain(api, fresh["calcRunId"])
    assert msg["messageType"] == "EXPLANATION", f"{msg['messageType']}: {msg['content']}"
    sentences = body_sentences(msg["content"])
    assert len(sentences) <= 3, f"결과 설명이 {len(sentences)}문장\n{msg['content']}"
    listed = [k for k in _CONDITION_KEYS if fresh["display"].get(k) and fresh["display"][k] in msg["content"]]
    assert not listed, f"입력 조건을 나열함 {listed}(조건 바와 중복)\n{msg['content']}"
    assert_no_notice(msg)
    bad = unknown_numbers(msg["content"], fresh["display"])
    assert not bad, f"계산 결과에 없는 금액 {sorted(bad)}만원\n{msg['content']}"


@pytest.mark.parametrize("q", ["수익이 얼마나 나요?", "ETF 수익률은 몇 %예요?"])
def test_s8_10_return_question_without_promise(api, fresh, q):
    """수익을 물어도 약속·보장 여부는 본문에 쓰지 않는다(기준표 Q1). 연 수익률 숫자도 말하지 않는다."""
    msg = ask(api, q)
    assert msg["messageType"] == "ANSWER", f"[{q}] → {msg['messageType']}: {msg['content']}"
    sentences = body_sentences(msg["content"])
    assert len(sentences) <= 2, f"[{q}] {len(sentences)}문장\n{msg['content']}"
    assert_no_notice(msg)
