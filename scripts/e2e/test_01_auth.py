"""S0. 로그인·인증 (모든 화면의 전제)."""
from conftest import Api, LOGIN_ID, expect


def test_s0_1_without_login_is_401():
    """로그인 안 한 채 결과 화면을 열면 401 UNAUTHENTICATED → 프론트는 로그인 화면으로."""
    expect(Api().get("/api/plan"), 401, "UNAUTHENTICATED")


def test_s0_2_wrong_password_is_invalid_credentials():
    """비밀번호가 틀리면 401 INVALID_CREDENTIALS(로그인 화면에 머무름, 화면 이동 아님)."""
    expect(Api().login(LOGIN_ID, "definitely-wrong-password"), 401, "INVALID_CREDENTIALS")


def test_s0_3_empty_password_is_validation_failed():
    body = expect(Api().login(LOGIN_ID, ""), 422, "VALIDATION_FAILED")
    assert {"field": "password", "reason": "REQUIRED"} in [
        {k: e[k] for k in ("field", "reason")} for e in body["errors"]
    ]


def test_s0_4_login_sets_cookie_and_opens_plan(api):
    """로그인하면 쿠키가 생기고 GET /api/plan이 200(계획 있음) 또는 409 NO_PLAN(입력 화면)."""
    assert api.token
    r = api.get("/api/plan")
    assert (r.status_code, r.json().get("code")) in [(200, None), (409, "NO_PLAN")], r.text


def test_s0_5_login_clears_previous_chat(api):
    """다시 로그인하면 이전 대화가 지워진다(D-051). ai-server 없이도 메시지 행은 생기므로 확인 가능."""
    expect(api.post("/api/chat/messages", json={"text": "삭제 확인용 질문"}), 202)
    other = Api()
    expect(other.login(), 200)
    body = expect(api.get("/api/chat/messages"), 200)
    assert body["messages"] == [], f"재로그인 뒤에도 대화가 남음: {body['messages']}"
