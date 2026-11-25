"""S6. 여러 사용자 상황(scenarios.PERSONAS)을 같은 흐름으로 돌려, 서버가 프로젝트 목표대로 동작하는지 본다.

목표(프로젝트 개요)       → 이 파일에서 보는 것
 3. 목표 달성 시뮬레이션   → 판정·부족/초과 금액·원금·그래프 점이 명세대로인가, 이론상 확실한 판정(expect)이 맞는가
 2. 자산별 위험 구분       → ETF 비중이 클수록 좋았던 때~나빴던 때 폭이 넓어지고, ETF 0%면 결과가 하나인가
 4. 자금 마련 전략 비교     → 저축액·금리를 올리면 결과가 좋아지고, 목표만 바꾸면 자산은 그대로인가.
                            미달일 때만 대안이 나오고 대안이 실제로 결과를 개선하는가
 정확한 계산(핵심특징 4)   → 적금 100%일 때 명세 공식 손계산과 원 단위까지 같은가
"""
import os
import time

import pytest

from conftest import expect, ratio_of, report, save_plan_ok
from oracle import check_display, check_monthly_points, check_result, fmt_period, savings_only_after_tax
from scenarios import PERSONAS

MAX_SAVE_SECONDS = float(os.getenv("E2E_MAX_SAVE_SECONDS", "3"))
IDS = [f"{p['id']}-{p['name']}" for p in PERSONAS]


def scen(result, name):
    return next((s for s in result["scenarios"] if s["scenario"] == name), None)


def compare(api, **changes):
    return expect(api.post("/api/compare", json={"conditionChanges": changes}), 200)


@pytest.fixture(params=PERSONAS, ids=IDS)
def persona(request, api):
    """페르소나 계획을 저장하고 (페르소나, PlanView, 저장 시간)을 돌려준다."""
    p = request.param
    started = time.perf_counter()
    view = save_plan_ok(api, **p["plan"])
    elapsed = time.perf_counter() - started
    return p, view, elapsed


def test_s6_1_simulation(api, persona):
    """저장 → 결과: 불변 조건, 화면 문자열, 그래프, 이론상 판정, 응답 시간."""
    p, view, elapsed = persona
    plan, result, d = p["plan"], view["result"], view["display"]
    principal = plan["heldAssets"] + plan["monthlySavingAmount"] * plan["targetPeriodMonths"]
    row = dict(이름=p["name"], 목표=d["goalAmount"], 기간=fmt_period(plan["targetPeriodMonths"]),
               원금=f"{principal // 10_000:,}만원")
    row["저장 시간"] = f"{elapsed:.2f}s"

    if p["expect"] == "EXCEEDED" or result["status"] == "ALREADY_EXCEEDED":
        assert p["expect"] == "EXCEEDED", "초과가 아닌데 ALREADY_EXCEEDED"
        assert result["status"] == "ALREADY_EXCEEDED" and result["scenarios"] == []
        report(p["id"], **row, 판정="이미 초과")
        return

    check_result(result, held=plan["heldAssets"], monthly=plan["monthlySavingAmount"],
                 months=plan["targetPeriodMonths"], goal=plan["goalAmount"], ratio_pct=ratio_of(view))
    check_monthly_points(result, held=plan["heldAssets"], months=plan["targetPeriodMonths"])
    check_display(result, d)

    achieved = [s["isAchieved"] for s in result["scenarios"]]
    if p["expect"] == "ALL_MISSED":
        assert not any(achieved), f"이론상 모두 미달이어야 함: {achieved}"
    if p["expect"] == "ALL_ACHIEVED":
        assert all(achieved), f"이론상 모두 달성이어야 함: {achieved}"

    for name, col in (("GOOD", "좋았던 때"), ("AVG", "평균"), ("BAD", "나빴던 때")):
        s = scen(result, name)
        row[col] = f"{d[name + '.afterTaxAsset']} ({d[name + '.gapToGoal']})" if s else "-"
    base = scen(result, view["baselineResultScenario"])
    row["판정"] = "달성" if base["isAchieved"] else "미달"
    report(p["id"], **row)

    assert elapsed < MAX_SAVE_SECONDS, f"저장·계산 {elapsed:.2f}초 > {MAX_SAVE_SECONDS}초(E2E_MAX_SAVE_SECONDS)"


def test_s6_2_savings_only_exact(api, persona):
    """적금 100%로 바꿔 비교하면 결과는 평균 하나이고 명세 공식 값과 원 단위까지 같다."""
    p, view, _ = persona
    if view["result"]["status"] == "ALREADY_EXCEEDED":
        pytest.skip("초과 계획")
    plan = p["plan"]
    changed = compare(api, savingsRatioPct=100)["changed"]
    assert [s["scenario"] for s in changed["scenarios"]] == ["AVG"]
    a = changed["assumptions"]
    want = savings_only_after_tax(plan["heldAssets"], plan["monthlySavingAmount"], plan["targetPeriodMonths"],
                                  a["savingsRatePct"], a["taxRatePct"])
    got = changed["scenarios"][0]["afterTaxAsset"]
    report(p["id"], **{"적금만(명세 계산)": f"{want:,}원 {'= 일치' if got == want else f'≠ 서버 {got:,}원'}"})
    assert got == want, f"적금만: 명세 {want:,}원, 서버 {got:,}원 (차이 {got - want:+,}원)"
    if float(a["savingsRatePct"]) == 0:
        assert got == changed["scenarios"][0]["principal"], "금리 0%면 자산 = 원금"


def test_s6_3_risk_width_grows_with_etf(api, persona):
    """ETF 비중을 0 → 30 → 50 → 70 → 100%로 올리면 좋았던 때~나빴던 때 폭이 넓어진다(자산별 위험 구분)."""
    p, view, _ = persona
    if view["result"]["status"] == "ALREADY_EXCEEDED":
        pytest.skip("초과 계획")
    if p["plan"]["targetPeriodMonths"] > 120:
        pytest.skip("긴 기간은 계산이 오래 걸려 생략")
    widths = []
    for savings_ratio in (100, 70, 50, 30, 0):
        changed = compare(api, savingsRatioPct=savings_ratio)["changed"]
        names = [s["scenario"] for s in changed["scenarios"]]
        if savings_ratio == 100:
            assert names == ["AVG"], "ETF 0%면 결과가 하나"
            widths.append(0)
            continue
        assert names == ["GOOD", "AVG", "BAD"]
        widths.append(scen(changed, "GOOD")["afterTaxAsset"] - scen(changed, "BAD")["afterTaxAsset"])
    report(p["id"], **{"ETF 비중별 폭": " → ".join(f"{w // 10_000:,}" for w in widths) + " (만원)"})
    assert widths == sorted(widths), f"ETF 0/30/50/70/100% 폭이 커져야 함: {widths}"
    assert widths[-1] > 0


def test_s6_4_strategy_levers(api, persona):
    """전략 비교의 기본 성질: 월 저축↑ → 모든 구간 자산↑, 금리↑ → 자산↑, 목표만 바꾸면 자산 그대로·판정만 바뀜."""
    p, view, _ = persona
    if view["result"]["status"] == "ALREADY_EXCEEDED":
        pytest.skip("초과 계획")
    plan = p["plan"]

    if plan["monthlySavingAmount"] + 100_000 <= 10_000_000:
        body = compare(api, monthlySavingAmount=plan["monthlySavingAmount"] + 100_000)
        for cur, chg in zip(body["current"]["scenarios"], body["changed"]["scenarios"]):
            assert chg["afterTaxAsset"] > cur["afterTaxAsset"], f"{cur['scenario']} 월 저축을 늘렸는데 자산이 늘지 않음"
            assert chg["principal"] - cur["principal"] == 100_000 * plan["targetPeriodMonths"]

    cur_rate = float(view["result"]["assumptions"]["savingsRatePct"])
    if cur_rate + 1 <= 20 and ratio_of(view) > 0:
        body = compare(api, savingsRateInputPct=round(cur_rate + 1, 2))
        for cur, chg in zip(body["current"]["scenarios"], body["changed"]["scenarios"]):
            assert chg["afterTaxAsset"] > cur["afterTaxAsset"], f"{cur['scenario']} 금리를 올렸는데 자산이 늘지 않음"

    new_goal = max(10_000, plan["goalAmount"] // 2)
    body = compare(api, goalAmount=new_goal)
    for cur, chg in zip(body["current"]["scenarios"], body["changed"]["scenarios"]):
        assert chg["afterTaxAsset"] == cur["afterTaxAsset"], "목표만 바꿨는데 예상 자산이 바뀜"
        assert chg["gapToGoal"] == chg["afterTaxAsset"] - new_goal
        assert chg["isAchieved"] == (chg["afterTaxAsset"] >= new_goal)


def test_s6_5_alternatives_only_when_short_and_they_help(api, persona):
    """미달일 때만 대안이 나오고, 월 저축·기한 대안은 실제로 평균 결과를 개선한다."""
    p, view, _ = persona
    plan = p["plan"]
    longer = min(plan["targetPeriodMonths"] + 12, 360)
    body = expect(api.post("/api/alternatives", json={"targetPeriodMonths": longer}), 200)

    base = scen(view["result"], view["baselineResultScenario"]) if view["result"]["scenarios"] else None
    short = base is not None and not base["isAchieved"]
    report(p["id"], 대안="있음" if body["hasAlternatives"] else "없음")
    assert body["hasAlternatives"] is short, f"미달={short}인데 대안={body['hasAlternatives']}"
    if not short:
        assert body["alternatives"] == []
        return

    for card in body["alternatives"]:
        if not card["changes"]:
            continue
        cur_avg = scen(card["current"], "AVG")["afterTaxAsset"]
        chg_avg = scen(card["changed"], "AVG")["afterTaxAsset"]
        if card["key"] in ("monthlySavingAmount", "targetPeriodMonths") and longer > plan["targetPeriodMonths"]:
            assert chg_avg > cur_avg, f"{card['key']} 대안이 평균 결과를 개선하지 않음: {cur_avg:,} → {chg_avg:,}"
