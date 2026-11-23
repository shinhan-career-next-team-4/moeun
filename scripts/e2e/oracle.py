"""계산 결과를 독립적으로 검증하는 기준값(오라클)과 불변 조건.

기준 문서: docs/calc-spec.md(계산), backend Formatter(표시 형식).
Spring 코드를 가져다 쓰지 않고 명세만 보고 다시 계산한다. 같은 버그를 공유하지 않게 하려는 것이다.
"""
from __future__ import annotations

import re
from decimal import ROUND_HALF_UP, Decimal

SCENARIO_ORDER_3 = ["GOOD", "AVG", "BAD"]
SCENARIO_ORDER_1 = ["AVG"]


# ---------- 계산 ----------

def savings_only_after_tax(held: int, monthly: int, months: int, rate_pct, tax_pct) -> int:
    """적금 100%(ETF 0%)일 때 목표월 세후 예상 자산(calc-spec 5절).

    월초 납입, 단리, 회차 j는 N-j+1개월 이자. 세전 이자 = u * N(N+1)/2, u = M * 금리 / 100 / 12.
    보유 자산은 운용 수익 없음(CP-03). 결과만 원 단위 HALF_UP.
    """
    m = Decimal(monthly)
    u = m * Decimal(str(rate_pct)) / Decimal(100) / Decimal(12)
    interest = u * Decimal(months * (months + 1)) / Decimal(2)
    after_tax_interest = interest * (Decimal(1) - Decimal(str(tax_pct)) / Decimal(100))
    total = Decimal(held) + m * months + after_tax_interest
    return int(total.quantize(Decimal(1), rounding=ROUND_HALF_UP))


def add_months(year_month: str, months: int) -> str:
    y, m = map(int, year_month.split("-"))
    idx = y * 12 + (m - 1) + months
    return f"{idx // 12:04d}-{idx % 12 + 1:02d}"


# ---------- 표시 형식 (Formatter와 같은 규칙) ----------

def _unsigned(won: int) -> str:
    man = int((Decimal(won) / Decimal(10000)).quantize(Decimal(1), rounding=ROUND_HALF_UP))
    return "1만원 미만" if man == 0 else f"약 {man:,}만원"


def fmt_amount(won: int) -> str:
    if won == 0:
        return "0원"
    return ("-" if won < 0 else "") + _unsigned(abs(won))


def fmt_gap(gap: int, achieved: bool) -> str:
    label = "달성" if achieved else "미달"
    if gap == 0:
        return f"0원 {label}"
    return ("-" if gap < 0 else "+") + _unsigned(abs(gap)) + f" {label}"


def fmt_month(year_month: str) -> str:
    y, m = map(int, year_month.split("-"))
    return f"{y}년 {m}월"


def fmt_period(months: int) -> str:
    return f"{months // 12}년 {months % 12}개월"


# ---------- 불변 조건 ----------

def check_result(result: dict, *, held: int, monthly: int, months: int, goal: int,
                 ratio_pct: int) -> None:
    """한 계산 결과(PlanView.result 또는 compare의 current/changed)가 명세를 지키는지."""
    assert result["status"] == "CALCULATED", result["status"]
    assert result["goalAmount"] == goal
    assert result["targetMonth"] == add_months(result["calcStartMonth"], months), \
        f"목표월 = 시작월 + 기간이어야 함: {result['calcStartMonth']} + {months} != {result['targetMonth']}"

    names = [s["scenario"] for s in result["scenarios"]]
    expected = SCENARIO_ORDER_1 if ratio_pct == 100 else SCENARIO_ORDER_3
    assert names == expected, f"ETF 비중 {100 - ratio_pct}%면 시나리오는 {expected}, 실제 {names}"

    principal = held + monthly * months
    for s in result["scenarios"]:
        tag = s["scenario"]
        assert s["principal"] == principal, f"{tag} 원금 = 보유 + 월저축 x 기간 = {principal:,}, 실제 {s['principal']:,}"
        assert s["afterTaxAsset"] == s["principal"] + s["afterTaxGain"], f"{tag} 자산 = 원금 + 세후 수익"
        assert s["gapToGoal"] == s["afterTaxAsset"] - goal, f"{tag} 목표 대비 = 자산 - 목표"
        assert s["isAchieved"] == (s["afterTaxAsset"] >= goal), f"{tag} 달성 판정 = 자산 >= 목표"
        if s["isAchieved"]:
            assert s.get("goalReachMonth"), f"{tag} 달성인데 도달 시점이 없음"
            assert s["goalReachMonth"] <= result["targetMonth"], f"{tag} 도달 시점이 목표월 뒤"
        else:
            # 중간에 닿았다가 내려오면 값이 있을 수 있다(calc-spec 8절). 있으면 목표월 이하.
            if s.get("goalReachMonth"):
                assert s["goalReachMonth"] <= result["targetMonth"]

    if names == SCENARIO_ORDER_3:
        g, a, b = (s["afterTaxAsset"] for s in result["scenarios"])
        assert g >= a >= b, f"좋았던 때 >= 평균 >= 나빴던 때 이어야 함: {g:,} / {a:,} / {b:,}"


def check_monthly_points(result: dict, *, held: int, months: int) -> None:
    """월별 그래프 점(C12a-6-2, calc-spec 4절)."""
    for s in result["scenarios"]:
        pts = s["monthlyPoints"]
        tag = s["scenario"]
        assert len(pts) == months + 1, f"{tag} 점 수 = 기간 + 1 = {months + 1}, 실제 {len(pts)}"
        assert pts[0]["month"] == result["calcStartMonth"]
        assert pts[0]["afterTaxAsset"] == held, f"{tag} 첫 점 = 보유 자산"
        assert pts[-1]["month"] == result["targetMonth"]
        assert pts[-1]["afterTaxAsset"] == s["afterTaxAsset"], f"{tag} 마지막 점 = 결과 자산"
        months_seen = [p["month"] for p in pts]
        assert months_seen == sorted(set(months_seen)), f"{tag} 월이 겹치거나 순서가 틀림"
        assert "principal" not in pts[0], "화면 응답에는 점별 principal을 내보내지 않는다"


def check_display(result: dict, display: dict, prefix: str = "") -> None:
    """화면 숫자 문자열이 raw 값과 같은 숫자인지(프론트는 숫자를 만들지 않으므로 여기가 유일한 원천)."""
    for s in result["scenarios"]:
        p = f"{prefix}{s['scenario']}."
        assert display[p + "afterTaxAsset"] == fmt_amount(s["afterTaxAsset"]), p + "afterTaxAsset"
        assert display[p + "principal"] == fmt_amount(s["principal"]), p + "principal"
        assert display[p + "gapToGoal"] == fmt_gap(s["gapToGoal"], s["isAchieved"]), p + "gapToGoal"
        if s.get("goalReachMonth") and s["isAchieved"]:
            assert display[p + "goalReachMonth"] == fmt_month(s["goalReachMonth"]), p + "goalReachMonth"


# ---------- AI 문장 숫자 검사 ----------

_MANWON = re.compile(r"([\d][\d,]*)\s*만\s*원")


def manwon_numbers(text: str) -> set[int]:
    return {int(x.replace(",", "")) for x in _MANWON.findall(text or "")}


def unknown_numbers(content: str, *sources) -> set[int]:
    """AI 문장에 나온 'N만원' 중 근거(display 문자열, 사용자 질문)에 없는 숫자."""
    allowed: set[int] = set()
    for src in sources:
        if isinstance(src, dict):
            src = " ".join(str(v) for v in src.values())
        allowed |= manwon_numbers(src)
    return manwon_numbers(content) - allowed
