"""S2~S4. 결과 화면 ②(대안 카드)·③(조건 바꿔 비교)."""
from decimal import ROUND_HALF_UP, Decimal

import pytest

from conftest import WIREFRAME_PLAN, current_view, expect, ratio_of, save_plan_ok
from oracle import check_display, check_result, fmt_period, savings_only_after_tax

P = WIREFRAME_PLAN
CONDITION_KEYS = ("goalAmount", "monthlySavingAmount", "savingsRatioPct", "savingsRateInputPct", "targetPeriodMonths")


def compare(api, **changes):
    return api.post("/api/compare", json={"conditionChanges": changes})


def side_dict(side: dict) -> dict:
    return {k: (float(v) if k == "savingsRateInputPct" and v is not None else v) for k, v in side.items()}


# ---------- S2. 계산 엔진 정확값 (적금 100%) ----------

@pytest.mark.parametrize("rate", [3.2, None])
def test_s2_1_savings_only_exact_value(api, rate):
    """ETF 0%면 결과가 하나(평균)이고, 명세의 단리 공식으로 손계산한 값과 원 단위까지 같다."""
    save_plan_ok(api, savingsRateInputPct=rate)
    body = expect(compare(api, savingsRatioPct=100), 200)
    changed = body["changed"]
    check_result(changed, held=P["heldAssets"], monthly=P["monthlySavingAmount"],
                 months=P["targetPeriodMonths"], goal=P["goalAmount"], ratio_pct=100)

    a = changed["assumptions"]
    assert a["isDefaultSavingsRate"] is (rate is None)
    expected = savings_only_after_tax(P["heldAssets"], P["monthlySavingAmount"], P["targetPeriodMonths"],
                                      a["savingsRatePct"], a["taxRatePct"])
    actual = changed["scenarios"][0]["afterTaxAsset"]
    assert actual == expected, f"적금만 세후 자산: 명세 계산 {expected:,}원, 서버 {actual:,}원"


# ---------- S3. 조건 바꿔 비교 (결과 화면 ③) ----------

def test_s3_1_compare_monthly_150_side_by_side(api):
    """월 120만 → 150만: 현재 조건과 바꾼 조건을 나란히. 적용 전에는 내 계획이 바뀌지 않는다."""
    view = save_plan_ok(api)
    ratio = ratio_of(view)
    body = expect(compare(api, monthlySavingAmount=1_500_000), 200)

    assert body["changedKeys"] == ["monthlySavingAmount"]
    cur, chg = side_dict(body["conditions"]["current"]), side_dict(body["conditions"]["changed"])
    assert chg["monthlySavingAmount"] == 1_500_000
    assert {k: cur[k] for k in CONDITION_KEYS if k != "monthlySavingAmount"} == \
           {k: chg[k] for k in CONDITION_KEYS if k != "monthlySavingAmount"}, "바꾼 조건 하나만 달라야 함"

    # 현재 조건 쪽 = 결과 화면과 같은 숫자
    cur_assets = [s["afterTaxAsset"] for s in body["current"]["scenarios"]]
    plan_assets = [s["afterTaxAsset"] for s in view["result"]["scenarios"]]
    assert cur_assets == plan_assets, "비교표의 '현재 조건'이 결과 화면 숫자와 다름"

    check_result(body["changed"], held=P["heldAssets"], monthly=1_500_000, months=P["targetPeriodMonths"],
                 goal=P["goalAmount"], ratio_pct=ratio)
    # 와이어프레임 '총 납입 원금 6,000만 원'
    assert body["changed"]["scenarios"][0]["principal"] == 60_000_000
    check_display(body["changed"], body["display"], "changed.")
    check_display(body["current"], body["display"], "current.")

    after = current_view(api)
    assert after["revision"] == view["revision"], "비교만 했는데 계획 revision이 바뀜"


def test_s3_2_compare_period_and_rate(api):
    save_plan_ok(api)
    body = expect(compare(api, targetPeriodMonths=48, savingsRateInputPct=4.0), 200)
    assert set(body["changedKeys"]) == {"targetPeriodMonths", "savingsRateInputPct"}
    assert body["display"]["conditions.changed.targetPeriodMonths"] == fmt_period(48)
    assert body["display"]["conditions.changed.savingsRateInputPct"] == "연 4.00%"
    assert body["changed"]["targetMonth"] > body["current"]["targetMonth"]


def test_s3_3_compare_goal_amount_allowed(api):
    """비교에서는 목표 금액도 바꿀 수 있다(D-090)."""
    save_plan_ok(api)
    body = expect(compare(api, goalAmount=40_000_000), 200)
    assert body["changed"]["goalAmount"] == 40_000_000
    assert body["current"]["goalAmount"] == P["goalAmount"]


@pytest.mark.parametrize("changes, field", [
    ({"goalType": "CAR"}, "goalType"),                 # 비교로 못 바꾸는 키
    ({"heldAssets": 1_000_000}, "heldAssets"),         # 보유 자산은 비교 대상 아님(D-090)
    ({"monthlySavingAmount": 0}, "monthlySavingAmount"),
    ({"savingsRatioPct": 101}, "savingsRatioPct"),
    ({"savingsRatioPct": 33.3}, "savingsRatioPct"),
])
def test_s3_4_compare_rejects(api, changes, field):
    save_plan_ok(api)
    body = expect(api.post("/api/compare", json={"conditionChanges": changes}), 422, "VALIDATION_FAILED")
    assert field in [e["field"] for e in body["errors"]], body["errors"]


def test_s3_5_compare_malformed(api):
    save_plan_ok(api)
    expect(api.post("/api/compare", json={"conditionChanges": [1, 2]}), 400, "MALFORMED_REQUEST")


@pytest.mark.xfail(reason="와이어프레임 ③ '바꾼 조건을 내 계획으로 적용': POST /api/plan/apply(#68, Should) 미구현", strict=False)
def test_s3_6_apply_compared_condition(api):
    view = save_plan_ok(api)
    r = api.post("/api/plan/apply", json={"expectedRevision": view["revision"],
                                          "conditionChanges": {"monthlySavingAmount": 1_500_000}})
    assert r.status_code == 200, r.text


# ---------- S4. 대안 카드 (결과 화면 ②) ----------

def test_s4_1_alternatives_for_shortfall_plan(api):
    """목표에 못 미치면 대안 3장: 월 저축액·기한·비중, 같은 기준(현재 계획), 조건 하나씩만 바꿈."""
    view = save_plan_ok(api, goalAmount=70_000_000)   # 원금 4,920만이라 평균으로도 미달
    assert not next(s for s in view["result"]["scenarios"] if s["scenario"] == "AVG")["isAchieved"]
    ratio = ratio_of(view)

    body = expect(api.post("/api/alternatives", json={"targetPeriodMonths": 48}), 200)
    assert body["hasAlternatives"] is True
    cards = {c["key"]: c for c in body["alternatives"]}
    assert [c["key"] for c in body["alternatives"]] == ["monthlySavingAmount", "targetPeriodMonths", "savingsRatioPct"]

    raised = int((Decimal(P["monthlySavingAmount"]) * Decimal("1.10")).quantize(Decimal(1), ROUND_HALF_UP))
    assert cards["monthlySavingAmount"]["changes"] == {"monthlySavingAmount": raised}, "월 저축액 대안 = 현재 +10%"
    assert cards["targetPeriodMonths"]["changes"] == {"targetPeriodMonths": 48}, "기한 대안 = 사용자가 입력한 기간"
    if ratio - 10 >= 0:
        assert cards["savingsRatioPct"]["changes"] == {"savingsRatioPct": ratio - 10}, "비중 대안 = ETF +10%p"

    plan_avg = next(s for s in view["result"]["scenarios"] if s["scenario"] == "AVG")["afterTaxAsset"]
    for key, card in cards.items():
        if not card["changes"]:
            continue
        cur, chg = side_dict(card["conditions"]["current"]), side_dict(card["conditions"]["changed"])
        diff_keys = [k for k in CONDITION_KEYS if cur[k] != chg[k]]
        assert diff_keys == [key], f"{key} 카드에서 바뀐 조건이 {diff_keys}"
        card_avg = next(s for s in card["current"]["scenarios"] if s["scenario"] == "AVG")["afterTaxAsset"]
        assert card_avg == plan_avg, f"{key} 카드의 현재 기준이 결과 화면과 다름(같은 기준으로 계산해야 함)"
        assert card["display"], f"{key} 카드에 표시 문자열이 없음"

    assert current_view(api)["revision"] == view["revision"], "대안 조회만 했는데 계획이 바뀜"


def test_s4_2_period_card_empty_without_input(api):
    """기간을 입력하기 전 '기한을 늘리면' 카드는 빈 카드(D-039)."""
    save_plan_ok(api, goalAmount=70_000_000)
    body = expect(api.post("/api/alternatives", json={}), 200)
    period = next(c for c in body["alternatives"] if c["key"] == "targetPeriodMonths")
    assert period["changes"] == {} and period["changed"] is None


def test_s4_3_no_alternatives_when_achieved(api):
    save_plan_ok(api, goalAmount=30_000_000)
    body = expect(api.post("/api/alternatives", json={"targetPeriodMonths": 48}), 200)
    assert body == {"hasAlternatives": False, "alternatives": []}


def test_s4_4_no_alternatives_when_exceeded(api):
    save_plan_ok(api, heldAssets=60_000_000)
    body = expect(api.post("/api/alternatives", json={}), 200)
    assert body["hasAlternatives"] is False


def test_s4_5_alternative_period_out_of_range(api):
    save_plan_ok(api, goalAmount=70_000_000)
    expect(api.post("/api/alternatives", json={"targetPeriodMonths": 361}), 422, "VALIDATION_FAILED")
