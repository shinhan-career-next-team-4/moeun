"""S9. 타깃 사용자 7명의 여정(journeys.py)을 처음부터 끝까지 따라간다.

test_s9_1: 계획 입력 → 판정 → 대안 (ai-server 불필요)
test_s9_2: 질문 → 다시 계산 → 적용 → 되돌리기 → ETF 위험 질문 (ai-server + LLM, -m ai)
실행이 끝나면 journeys.md에 사람별 여정 기록(질문, AI 답, 결과 숫자)이 남는다. 발표·영상 대본의 재료로 쓴다.
"""
import pytest

from conftest import ask, current_view, expect, journal, ratio_of, save_plan_ok
from journeys import JOURNEYS
from oracle import check_display, check_result, citation_orgs, savings_only_after_tax, unknown_numbers

IDS = [f"{j['id']}-{j['name']}" for j in JOURNEYS]
DEFAULT_ETF = "SP500"


# ---------- 공통 ----------

def scen(result, name):
    return next((s for s in result["scenarios"] if s["scenario"] == name), None)


def width(result):
    g, b = scen(result, "GOOD"), scen(result, "BAD")
    return (g["afterTaxAsset"] - b["afterTaxAsset"]) if g and b else 0


def reset_etf_product(api):
    """앞선 시나리오가 종목을 바꿔 두었으면 대표 종목으로 되돌린다(입력 저장은 종목을 되돌리지 않음)."""
    view = current_view(api)
    if view and view["plan"].get("etfProductCode") not in (None, DEFAULT_ETF):
        expect(api.post("/api/plan/apply", json={"path": "COMPARE", "expectedRevision": view["revision"],
                                                "conditionChanges": {"etfProductCode": DEFAULT_ETF}}), 200)


def start(api, j) -> dict:
    """대화 기록을 비우고(재로그인) 페르소나 계획을 저장한다."""
    expect(api.login(), 200)
    reset_etf_product(api)
    return save_plan_ok(api, **j["plan"])


def judgement(view) -> str:
    result = view["result"]
    if result["status"] == "ALREADY_EXCEEDED":
        return "EXCEEDED"
    achieved = [s["isAchieved"] for s in result["scenarios"]]
    if all(achieved):
        return "ALL_ACHIEVED"
    if not any(achieved):
        return "ALL_MISSED"
    return "MIXED"


def summary(view) -> str:
    """판정 한 줄(화면 문자열 그대로)."""
    if view["result"]["status"] == "ALREADY_EXCEEDED":
        return f"이미 목표 초과 (목표 {view['display']['goalAmount']})"
    d = view["display"]
    parts = [f"{n} {d[n + '.afterTaxAsset']} ({d[n + '.gapToGoal']})"
             for n in ("GOOD", "AVG", "BAD") if scen(view["result"], n)]
    return " / ".join(parts)


# ---------- 추가 확인(journeys.py의 "check") ----------

def all_scenarios_improve(cmp, j):
    for cur, chg in zip(cmp["current"]["scenarios"], cmp["changed"]["scenarios"]):
        assert chg["afterTaxAsset"] > cur["afterTaxAsset"], f"{cur['scenario']} 저축을 늘렸는데 자산이 늘지 않음"


def assets_unchanged(cmp, j):
    for cur, chg in zip(cmp["current"]["scenarios"], cmp["changed"]["scenarios"]):
        assert chg["afterTaxAsset"] == cur["afterTaxAsset"], "목표만 바꿨는데 예상 자산이 바뀜"


def savings_only_still_achieved(cmp, j):
    names = [s["scenario"] for s in cmp["changed"]["scenarios"]]
    assert names == ["AVG"], f"적금 100%면 결과는 하나: {names}"
    assert cmp["changed"]["scenarios"][0]["isAchieved"], "적금만으로도 달성이어야 함(원금 > 목표)"


def savings_only_now_achieved(cmp, j):
    """적금 100% 계획: 바꾼 결과를 명세 공식으로 다시 계산해 원 단위까지 맞춘다."""
    p = j["plan"]
    changed = cmp["changed"]
    a = changed["assumptions"]
    want = savings_only_after_tax(p["heldAssets"], 45 * 10_000, p["targetPeriodMonths"],
                                  a["savingsRatePct"], a["taxRatePct"])
    got = changed["scenarios"][0]["afterTaxAsset"]
    assert got == want, f"적금만: 명세 {want:,}원, 서버 {got:,}원"
    assert changed["scenarios"][0]["isAchieved"], "월 45만이면 1년에 500만 달성이어야 함"


def not_accumulated_from_previous(cmp, j):
    assert cmp["conditions"]["changed"]["targetPeriodMonths"] == j["plan"]["targetPeriodMonths"], \
        "앞 질문(기간 3년)이 누적됨. 비교 기준은 항상 현재 계획(D-041)"


def risk_range_wider(cmp, j):
    w0, w1 = width(cmp["current"]), width(cmp["changed"])
    assert w1 > w0, f"ETF 비중을 늘렸는데 좋았던 때~나빴던 때 폭이 넓어지지 않음: {w0:,} → {w1:,}"


CHECKS = {f.__name__: f for f in (all_scenarios_improve, assets_unchanged, savings_only_still_achieved,
                                  savings_only_now_achieved, not_accumulated_from_previous, risk_range_wider)}


# ---------- S9-1 입력 → 판정 → 대안 ----------

@pytest.mark.parametrize("j", JOURNEYS, ids=IDS)
def test_s9_1_plan_and_judgement(api, j):
    view = start(api, j)
    p = j["plan"]
    log = journal(j["id"], j)
    log["판정"] = summary(view)

    if view["result"]["status"] == "CALCULATED":
        ratio = p["savingsRatioPct"] if p["savingsRatioPct"] is not None else ratio_of(view)
        check_result(view["result"], held=p["heldAssets"], monthly=p["monthlySavingAmount"],
                     months=p["targetPeriodMonths"], goal=p["goalAmount"], ratio_pct=ratio)
        check_display(view["result"], view["display"])
        if ratio == 100:   # 적금만이면 명세 공식과 원 단위 일치
            a = view["result"]["assumptions"]
            want = savings_only_after_tax(p["heldAssets"], p["monthlySavingAmount"], p["targetPeriodMonths"],
                                          a["savingsRatePct"], a["taxRatePct"])
            assert scen(view["result"], "AVG")["afterTaxAsset"] == want

    if j["expect"]:
        assert judgement(view) == j["expect"], f"판정 {judgement(view)}, 기대 {j['expect']}\n{log['판정']}"

    longer = min(p["targetPeriodMonths"] + 12, 360)
    alt = expect(api.post("/api/alternatives", json={"targetPeriodMonths": longer}), 200)
    base = scen(view["result"], view["baselineResultScenario"]) if view["result"]["scenarios"] else None
    short = base is not None and not base["isAchieved"]
    assert alt["hasAlternatives"] is short, f"기준 판정 미달={short}인데 대안={alt['hasAlternatives']}"
    if j["alternatives"] is not None:
        assert alt["hasAlternatives"] is j["alternatives"]
    log["대안"] = ", ".join(
        f"{c['key']}→{list(c['changes'].values())[0]:,}" for c in alt["alternatives"] if c["changes"]) or "없음"


# ---------- S9-2 대화 여정 ----------

@pytest.mark.ai
@pytest.mark.usefixtures("ai_ready")
@pytest.mark.parametrize("j", JOURNEYS, ids=IDS)
def test_s9_2_conversation_journey(api, j):
    view = start(api, j)
    log = journal(j["id"], j)
    log.setdefault("단계", [])
    steps = log["단계"] = []
    last_recalc = None

    for n, step in enumerate(j["steps"], 1):
        if "ask" in step:
            q = step["ask"]
            msg = ask(api, q)
            entry = {"no": n, "질문": q, "답": msg["content"], "종류": msg["messageType"]}
            steps.append(entry)
            if step.get("risk"):
                assert msg["messageType"] == "ANSWER", f"[{q}] → {msg['messageType']}: {msg['content']}"
                for c in msg["citations"]:
                    assert c["title"] and c["sourceUrl"] and c["orgName"], c
                entry["출처"] = ", ".join(f"{c['orgName']} {c['title']}" for c in msg["citations"]) or "없음"
                if ratio_of(view) != 100:   # 적금만이면 ETF가 없어 출처 개수는 보지 않는다
                    orgs = citation_orgs(msg["citations"])
                    assert len(orgs) >= 2, f"[{q}] 서로 다른 운용사 출처 2곳 이상이어야 함: {orgs}\n{msg['content']}"
                continue
            if step["changes"] is None:
                assert msg["messageType"] != "RECALC_RESULT", f"[{q}] 비교가 생기면 안 됨: {msg['content']}"
                continue
            assert msg["messageType"] == "RECALC_RESULT", f"[{q}] → {msg['messageType']}: {msg['content']}"
            cmp = expect(api.get(f"/api/chat/messages/{msg['id']}/comparison"), 200)
            assert set(cmp["changedKeys"]) == set(step["changes"]), \
                f"[{q}] 바뀐 조건 {cmp['changedKeys']}, 기대 {list(step['changes'])}"
            for key, value in step["changes"].items():
                got = cmp["conditions"]["changed"][key]
                assert (float(got) if key == "savingsRateInputPct" else got) == value, f"[{q}] {key}={got}, 기대 {value}"
            assert cmp["applicable"] is True, f"[{q}] 가장 최근 비교인데 적용 불가"
            bad = unknown_numbers(msg["content"], cmp["display"], view["display"], q)
            assert not bad, f"[{q}] AI 문장에 계산 결과에 없는 금액 {sorted(bad)}만원\n{msg['content']}"
            if step.get("check"):
                CHECKS[step["check"]](cmp, j)
            entry["바뀐 결과(평균)"] = cmp["display"].get("changed.AVG.afterTaxAsset", "")
            last_recalc = (msg, step["changes"])

        elif step.get("apply"):
            assert last_recalc, "적용할 비교가 없음"
            msg, changes = last_recalc
            before = current_view(api)
            applied = expect(api.post(f"/api/chat/messages/{msg['id']}/apply",
                                      json={"expectedRevision": before["revision"]}), 200)
            after = current_view(api)
            assert applied["revision"] == before["revision"] + 1
            for key, value in changes.items():
                assert after["plan"][key] == value, f"적용 뒤 계획 {key}={after['plan'][key]}, 기대 {value}"
            steps.append({"no": n, "동작": "내 계획으로 적용", "결과": summary(after)})

        elif step.get("undo"):
            before = current_view(api)
            expect(api.post("/api/plan/undo", json={"expectedRevision": before["revision"]}), 200)
            after = current_view(api)
            for key in ("monthlySavingAmount", "targetPeriodMonths", "goalAmount"):
                assert after["plan"][key] == j["plan"][key], f"되돌린 뒤 {key}가 입력값과 다름"
            assert after["result"]["scenarios"] == view["result"]["scenarios"], "되돌린 뒤 숫자가 처음과 다름"
            steps.append({"no": n, "동작": "되돌리기", "결과": summary(after)})
