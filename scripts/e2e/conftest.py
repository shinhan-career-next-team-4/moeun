"""로컬 E2E 공통 준비: Spring 로그인, 계획 저장, 대화 폴링.

환경 변수
  E2E_BASE_URL   Spring 주소 (기본 http://localhost:8080)
  E2E_AI_URL     ai-server 주소 (기본 http://localhost:8000, ai 시나리오 사전 점검용)
  E2E_LOGIN_ID   데모 계정 아이디 (backend DEMO_ACCOUNTS에 있는 것)
  E2E_PASSWORD   데모 계정 비밀번호
주의: 이 테스트는 그 계정의 계획과 대화를 바꾼다. 시연용 계정과 다른 계정을 쓰는 게 좋다.
"""
from __future__ import annotations

import os
import sys
import time
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).parent))

BASE_URL = os.getenv("E2E_BASE_URL", "http://localhost:8080")
AI_URL = os.getenv("E2E_AI_URL", "http://localhost:8000")
LOGIN_ID = os.getenv("E2E_LOGIN_ID")
PASSWORD = os.getenv("E2E_PASSWORD")
COOKIE = "moeun_at"

# 와이어프레임 예시 계획: 전세자금 5,000만원 / 3년 / 보유 600만원 / 월 120만원 / 적금 금리 3.2%
WIREFRAME_PLAN = {
    "goalType": "HOUSE",
    "goalCustomName": None,
    "goalAmount": 50_000_000,
    "targetPeriodMonths": 36,
    "heldAssets": 6_000_000,
    "monthlySavingAmount": 1_200_000,
    "savingsRateInputPct": 3.2,
}


class Api:
    """쿠키 토큰을 직접 들고 다니는 얇은 클라이언트.

    로컬 http에서 Secure 쿠키가 버려지는 문제를 피하려고 Set-Cookie를 직접 읽어 Cookie 헤더로 보낸다.
    """

    def __init__(self, base_url: str = BASE_URL):
        self.http = httpx.Client(base_url=base_url, timeout=60)
        self.token: str | None = None

    def request(self, method: str, path: str, **kw) -> httpx.Response:
        headers = dict(kw.pop("headers", {}) or {})
        if self.token:
            headers["Cookie"] = f"{COOKIE}={self.token}"
        r = self.http.request(method, path, headers=headers, **kw)
        self.http.cookies.clear()
        for raw in r.headers.get_list("set-cookie"):
            if raw.startswith(COOKIE + "="):
                value = raw.split(";", 1)[0].split("=", 1)[1]
                expired = "max-age=0" in raw.lower().replace(" ", "")
                self.token = None if (expired or not value) else value
        return r

    def get(self, path, **kw):
        return self.request("GET", path, **kw)

    def post(self, path, **kw):
        return self.request("POST", path, **kw)

    def put(self, path, **kw):
        return self.request("PUT", path, **kw)

    def login(self, login_id: str | None = LOGIN_ID, password: str | None = PASSWORD) -> httpx.Response:
        return self.post("/api/auth/login", json={"loginId": login_id, "password": password})


def code_of(r: httpx.Response) -> str | None:
    try:
        return r.json().get("code")
    except Exception:
        return None


def expect(r: httpx.Response, status: int, code: str | None = None) -> dict:
    assert r.status_code == status, f"{r.request.method} {r.request.url.path} -> {r.status_code}, 기대 {status}\n{r.text[:800]}"
    body = r.json() if r.content else {}
    if code is not None:
        assert body.get("code") == code, f"code {body.get('code')}, 기대 {code}\n{r.text[:800]}"
    return body


# ---------- 계획 ----------

def current_view(api: Api) -> dict | None:
    r = api.get("/api/plan")
    if r.status_code == 409 and code_of(r) == "NO_PLAN":
        return None
    return expect(r, 200)


def save_plan(api: Api, **overrides) -> httpx.Response:
    """현재 revision으로 입력 저장(P-3). 응답을 그대로 돌려준다(검증은 호출한 쪽)."""
    view = current_view(api)
    body = {**WIREFRAME_PLAN, **overrides, "expectedRevision": view["revision"] if view else None}
    return api.put("/api/plan", json=body)


def save_plan_ok(api: Api, **overrides) -> dict:
    return expect(save_plan(api, **overrides), 200)


def ratio_of(view: dict) -> int:
    """계획의 적금 비중. 미입력(null)이면 기본 50(calc-spec 3절)."""
    r = view["plan"].get("savingsRatioPct")
    return 50 if r is None else r


# ---------- 대화 ----------

def max_message_id(api: Api) -> int:
    body = expect(api.get("/api/chat/messages"), 200)
    return max((m["id"] for m in body["messages"]), default=0)


def wait_settled(api: Api, after_id: int, timeout: float = 90) -> list[dict]:
    """after_id 뒤 메시지가 모두 PENDING이 아닐 때까지 1초 폴링(F-2)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        body = expect(api.get("/api/chat/messages", params={"afterId": after_id}), 200)
        msgs = body["messages"]
        if not body["hasPending"] and all(m["status"] != "PENDING" for m in msgs):
            return msgs
        time.sleep(1)
    pytest.fail(f"{timeout}초 안에 답이 오지 않음(afterId={after_id})")


def ask(api: Api, text: str, timeout: float = 90) -> dict:
    """질문(F-1) → 답이 끝날 때까지 폴링 → ASSISTANT 메시지."""
    body = expect(api.post("/api/chat/messages", json={"text": text}), 202)
    aid = body["assistantMessageId"]
    msgs = wait_settled(api, body["userMessageId"] - 1, timeout)
    answer = next(m for m in msgs if m["id"] == aid)
    assert answer["status"] == "COMPLETED", f"답변 실패: {answer}"
    return answer


def explain(api: Api, calc_run_id: int, timeout: float = 90) -> dict:
    """설명 요청(F-3). 응답에 id가 없어 요청 전 최대 id 뒤의 ASSISTANT 메시지를 찾는다."""
    before = max_message_id(api)
    expect(api.post("/api/chat/explanations", json={"calcRunId": calc_run_id}), 202)
    msgs = wait_settled(api, before, timeout)
    mine = [m for m in msgs if m["role"] == "ASSISTANT"]
    assert mine, "설명 메시지가 생기지 않음"
    return mine[-1]


# ---------- fixture ----------

@pytest.fixture(scope="session")
def api() -> Api:
    if not LOGIN_ID or not PASSWORD:
        pytest.exit("E2E_LOGIN_ID, E2E_PASSWORD를 설정하세요(backend DEMO_ACCOUNTS의 계정).", 2)
    a = Api()
    try:
        a.http.get("/actuator/health")
    except httpx.TransportError:
        pytest.exit(f"Spring({BASE_URL})에 연결하지 못했어요. backend를 먼저 띄우세요.", 2)
    r = a.login()
    if r.status_code != 200:
        pytest.exit(f"로그인 실패 {r.status_code} {r.text[:300]} - DEMO_ACCOUNTS 설정을 확인하세요.", 2)
    return a


@pytest.fixture(scope="session")
def ai_ready():
    try:
        r = httpx.get(AI_URL + "/health", timeout=5)
        ok = r.status_code == 200
    except httpx.TransportError:
        ok = False
    if not ok:
        pytest.skip(f"ai-server({AI_URL})가 응답하지 않아 AI 시나리오를 건너뜀")


@pytest.fixture
def wireframe_plan(api) -> dict:
    """와이어프레임 계획을 저장하고 PlanView를 돌려준다."""
    return save_plan_ok(api)


# ---------- 시나리오 결과 표 (report.md) ----------

REPORT: dict[str, dict] = {}


def report(pid: str, **cols) -> None:
    REPORT.setdefault(pid, {}).update(cols)


def pytest_sessionfinish(session, exitstatus):
    if not REPORT:
        return
    cols = ["이름", "목표", "기간", "원금", "판정", "좋았던 때", "평균", "나빴던 때", "적금만(명세 계산)",
            "대안", "ETF 비중별 폭", "저장 시간"]
    lines = ["# 시나리오 실행 결과", "", "pytest가 끝날 때 자동으로 만든다. 표의 숫자는 서버 응답의 display 문자열이다.", "",
             "| ID | " + " | ".join(cols) + " |", "|" + "---|" * (len(cols) + 1)]
    for pid in sorted(REPORT):
        row = REPORT[pid]
        lines.append(f"| {pid} | " + " | ".join(str(row.get(c, "")) for c in cols) + " |")
    (Path(__file__).parent / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
