"""S1. 입력 화면 → 결과 화면 ① (와이어프레임 '입력 화면', '결과 화면 ①')."""
import pytest

from conftest import WIREFRAME_PLAN, current_view, expect, ratio_of, save_plan, save_plan_ok
from oracle import check_display, check_monthly_points, check_result, fmt_amount

P = WIREFRAME_PLAN


def test_s1_1_save_wireframe_plan_shows_result(api):
    """전세 5,000만 / 3년 / 보유 600만 / 월 120만 / 3.2% 저장 → 결과 요약·3구간·월별 그래프 데이터."""
    view = save_plan_ok(api)
    result = view["result"]

    # 입력값이 그대로 저장됐는지(입력 수정 화면 채우기용)
    for k in ("goalType", "goalAmount", "targetPeriodMonths", "heldAssets", "monthlySavingAmount"):
        assert view["plan"][k] == P[k], k
    assert float(view["plan"]["savingsRateInputPct"]) == P["savingsRateInputPct"]

    check_result(result, held=P["heldAssets"], monthly=P["monthlySavingAmount"],
                 months=P["targetPeriodMonths"], goal=P["goalAmount"], ratio_pct=ratio_of(view))
    check_monthly_points(result, held=P["heldAssets"], months=P["targetPeriodMonths"])

    # 결과 요약 A: 기준 시나리오(기본 평균)와 판정 문자열
    assert view["baselineResultScenario"] == "AVG"
    display = view["display"]
    check_display(result, display)
    assert display["goalAmount"] == fmt_amount(P["goalAmount"]) == "약 5,000만원"
    assert display["conditions.monthlySavingAmount"] == "약 120만원"
    assert display["conditions.targetPeriodMonths"] == "3년 0개월"
    assert display["conditions.savingsRateInputPct"] == "연 3.20%"

    # 화면 전용 응답에서 빼기로 한 필드(C12a-6-1)
    assert "annualReturnPct" not in str(result), "연 수익률은 수익 약속처럼 읽혀 내보내지 않는다"
    assert result["assumptions"]["isDefaultSavingsRate"] is False


def test_s1_2_same_plan_same_numbers(api, wireframe_plan):
    """다시 조회해도 같은 calcRunId·같은 숫자(같은 revision = 같은 calcRunId, C12a-6-6)."""
    again = current_view(api)
    assert again["revision"] == wireframe_plan["revision"]
    assert again["calcRunId"] == wireframe_plan["calcRunId"]
    assert again["result"]["scenarios"] == wireframe_plan["result"]["scenarios"]
    assert again["display"] == wireframe_plan["display"]


def test_s1_3_empty_rate_uses_bank_average(api):
    """적금 금리를 비우면 은행연합회 평균금리를 쓴다(입력 화면 4번)."""
    view = save_plan_ok(api, savingsRateInputPct=None)
    a = view["result"]["assumptions"]
    assert view["plan"]["savingsRateInputPct"] is None
    assert a["isDefaultSavingsRate"] is True
    assert a.get("savingsRateBaseMonth"), "기본 금리면 기준월이 있어야 함"
    assert float(a["savingsRatePct"]) > 0


@pytest.mark.parametrize("held", [50_000_000, 60_000_000])
def test_s1_4_already_exceeded(api, held):
    """보유 자산 >= 목표면 계산 없이 '이미 목표 초과'(같아도 초과, calc-spec G3)."""
    view = save_plan_ok(api, heldAssets=held)
    assert view["result"]["status"] == "ALREADY_EXCEEDED"
    assert view["result"]["scenarios"] == []
    assert view["baselineResultScenario"] is None


def test_s1_5_held_zero_allowed(api):
    """보유 자산 0원도 저장된다(D-082)."""
    view = save_plan_ok(api, heldAssets=0)
    assert view["result"]["status"] == "CALCULATED"
    assert view["display"]["conditions.heldAssets"] == "0원"


@pytest.mark.parametrize("overrides, field, reason", [
    ({"goalAmount": 9_999}, "goalAmount", "OUT_OF_RANGE"),
    ({"goalAmount": 10_000_000_001}, "goalAmount", "OUT_OF_RANGE"),
    ({"monthlySavingAmount": 0}, "monthlySavingAmount", "OUT_OF_RANGE"),       # 와이어프레임은 0원 허용(차이 G2)
    ({"monthlySavingAmount": 10_000_001}, "monthlySavingAmount", "OUT_OF_RANGE"),
    ({"monthlySavingAmount": None}, "monthlySavingAmount", "REQUIRED"),       # '잘 모르겠어요'는 범위 밖(D-045)
    ({"targetPeriodMonths": None}, "targetPeriodMonths", "REQUIRED"),         # '아직 없어요'는 범위 밖(D-045)
    ({"targetPeriodMonths": 361}, "targetPeriodMonths", "OUT_OF_RANGE"),
    ({"heldAssets": -1}, "heldAssets", "OUT_OF_RANGE"),
    ({"heldAssets": None}, "heldAssets", "REQUIRED"),
    ({"monthlySavingAmount": 1_200_000.5}, "monthlySavingAmount", "NOT_INTEGER"),
    ({"savingsRateInputPct": 3.125}, "savingsRateInputPct", "TOO_MANY_DECIMALS"),
    ({"savingsRateInputPct": 20.01}, "savingsRateInputPct", "OUT_OF_RANGE"),
])
def test_s1_6_input_validation(api, overrides, field, reason):
    """입력 범위·형식 오류는 422 VALIDATION_FAILED + 걸린 필드. 계획은 바뀌지 않는다."""
    before = current_view(api)
    body = expect(save_plan(api, **overrides), 422, "VALIDATION_FAILED")
    hits = [e for e in body["errors"] if e["field"] == field]
    assert hits and hits[0]["reason"] == reason, body["errors"]
    if reason == "OUT_OF_RANGE":
        assert {"min", "max", "unit"} <= hits[0].keys()
        assert "minDisplay" not in hits[0], "프론트 API에는 minDisplay가 없다(C3-5)"
    after = current_view(api)
    assert (after and after["revision"]) == (before and before["revision"]), "422인데 계획이 바뀜"


def test_s1_7_string_amount_is_malformed(api):
    expect(save_plan(api, goalAmount="50000000"), 400, "MALFORMED_REQUEST")


def test_s1_8_stale_revision_is_plan_changed(api, wireframe_plan):
    """다른 탭에서 계획이 바뀐 뒤 옛 revision으로 저장하면 409 PLAN_CHANGED."""
    body = {**WIREFRAME_PLAN, "expectedRevision": wireframe_plan["revision"] + 1000}
    expect(api.put("/api/plan", json=body), 409, "PLAN_CHANGED")


def test_s1_9_edit_then_undo(api):
    """입력 수정 → 되돌리기 → 직전 계획. 한 번 더 되돌리면 NOTHING_TO_UNDO(1단계)."""
    first = save_plan_ok(api)
    edited = save_plan_ok(api, monthlySavingAmount=800_000)
    assert edited["revision"] == first["revision"] + 1

    body = expect(api.post("/api/plan/undo", json={"expectedRevision": edited["revision"]}), 200)
    assert body["revision"] == edited["revision"] + 1
    back = current_view(api)
    assert back["plan"]["monthlySavingAmount"] == P["monthlySavingAmount"]
    assert back["result"]["scenarios"] == first["result"]["scenarios"], "되돌린 계획은 같은 숫자여야 함"

    expect(api.post("/api/plan/undo", json={"expectedRevision": back["revision"]}), 409, "NOTHING_TO_UNDO")
