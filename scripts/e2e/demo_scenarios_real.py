"""#57 데모 대화 시나리오(docs/demo-scenarios.md) 5개를 진짜 Spring + ai-server에 붙여 N회 돌린다.

ai-server의 tests/scenarios는 계산을 fake로 둔다. 이 스크립트는 실제 계산 응답으로 같은 가드레일(바뀐 조건·누적 금지·
지어낸 금액·고지 반복·내부 키·범위 밖)을 본다. 결과는 ok / blocked(FAILED) / unexpected / violation으로 센다.

    E2E_LOGIN_ID=... E2E_PASSWORD=... RUNS=5 \
      uv run --no-project --with httpx --with pytest python scripts/e2e/demo_scenarios_real.py
"""
import os
from collections import Counter

import conftest as c
from oracle import unknown_numbers
from test_12_conversation import _NOTICE_PHRASES

RUNS = int(os.getenv("RUNS", "5"))
PLAN = {"goalType": "HOUSE", "goalAmount": 20_000_000, "targetPeriodMonths": 36, "heldAssets": 5_000_000,
        "monthlySavingAmount": 300_000, "savingsRatioPct": 50, "savingsRateInputPct": 3.12, "goalCustomName": None}
api = c.Api(c.BASE_URL)


def send(text):
    """질문 → 답. 실패(FAILED)면 None."""
    body = c.expect(api.post("/api/chat/messages", json={"text": text}), 202)
    msgs = c.wait_settled(api, body["userMessageId"] - 1, 90)
    return next(m for m in msgs if m["id"] == body["assistantMessageId"])


def recalc(msg, view, q, keys, values):
    if msg["status"] != "COMPLETED":
        return "blocked", msg["content"]
    if msg["messageType"] != "RECALC_RESULT":
        return "unexpected", f"{msg['messageType']}: {msg['content']}"
    cmp = c.expect(api.get(f"/api/chat/messages/{msg['id']}/comparison"), 200)
    if set(cmp["changedKeys"]) != keys:
        return "violation", f"changedKeys={cmp['changedKeys']}"
    for k, v in values.items():
        if cmp["conditions"]["changed"][k] != v:
            return "violation", f"{k}={cmp['conditions']['changed'][k]}"
    bad = unknown_numbers(msg["content"], cmp["display"], view["display"], q)
    if bad:
        return "violation", f"지어낸 금액 {sorted(bad)}: {msg['content']}"
    return "ok", msg["content"]


def common(msg):
    text = msg.get("content") or ""
    hit = [p for p in _NOTICE_PHRASES if p in text]
    if hit:
        return "violation", f"고지 반복 {hit}: {text}"
    if "diff." in text or "current." in text or "changed." in text:
        return "violation", f"내부 키 노출: {text}"
    return None


def s1(view):
    q = "월 저축액을 50만원으로 바꾸면 어떻게 돼?"
    m = send(q)
    return common(m) or recalc(m, view, q, {"monthlySavingAmount"}, {"monthlySavingAmount": 500_000})


def s2(view):
    q = "적금 70 ETF 30으로 바꿔서 보여줘"
    m = send(q)
    return common(m) or recalc(m, view, q, {"savingsRatioPct"}, {"savingsRatioPct": 70})


def s3(view):
    first = recalc(send("월 저축액을 50만원으로 바꾸면?"), view, "", {"monthlySavingAmount"}, {"monthlySavingAmount": 500_000})
    if first[0] != "ok":
        return first[0], "1턴 " + first[1]
    q = "그럼 기간을 4년으로 하면?"
    m = send(q)
    return common(m) or recalc(m, view, q, {"targetPeriodMonths"}, {"targetPeriodMonths": 48})


def s4a(view):
    m = send("삼성전자 주식 지금 사도 돼?")
    if m["status"] != "COMPLETED":
        return "blocked", m["content"]
    if m["messageType"] != "OUT_OF_SCOPE":
        return "violation", f"{m['messageType']}: {m['content']}"
    if m["citations"] or "삼성전자" in m["content"]:
        return "violation", m["content"]
    return "ok", m["content"]


def s4b(view):
    m = send("월 저축액을 1억원으로 바꾸면?")
    if m["status"] != "COMPLETED":
        return "blocked", m["content"]
    if m["messageType"] == "RECALC_RESULT":
        return "violation", f"범위 밖인데 계산: {m['content']}"
    if m["messageType"] != "CLARIFY" or "1,000만원" not in m["content"]:
        return "unexpected", f"{m['messageType']}: {m['content']}"
    return common(m) or ("ok", m["content"])


SCENARIOS = [("S1 월 저축액 변경", s1), ("S2 비중 변경", s2), ("S3 기간 변경(누적 금지)", s3),
             ("S4a 범위 밖 질문", s4a), ("S4b 범위 밖 값", s4b)]

for name, fn in SCENARIOS:
    counts, sample, notes = Counter(), None, []
    for _ in range(RUNS):
        c.expect(api.login(), 200)           # 대화 비우기(D-051)
        view = c.save_plan_ok(api, **PLAN)
        kind, text = fn(view)
        counts[kind] += 1
        if kind == "ok" and sample is None:
            sample = text
        if kind != "ok":
            notes.append(f"{kind}: {text}")
    print(f"[{name}] {RUNS}회: " + ", ".join(f"{k} {v}" for k, v in counts.items()))
    print(f"  표본: {sample}")
    for n in notes:
        print(f"  - {n}")
