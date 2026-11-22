from app.core.errors import ErrorCode


class AgentError(Exception):
    """설명 생성 실패. Spring은 chat_message를 FAILED로 바꾸고 실패 안내를 보여준다(FR-AI-04)."""

    def __init__(self, code: ErrorCode, detail: str = ""):
        super().__init__(f"{code}: {detail}")
        self.code = code


class ForbiddenAnswer(AgentError):
    """본문 검사(M-4)에 걸린 답. run이 사유로 재시도 여부를 정한다. 최종 거부 코드는 FORBIDDEN_EXPRESSION이다."""

    def __init__(self, reason: str, *, retry: bool = True):
        super().__init__(ErrorCode.FORBIDDEN_EXPRESSION, reason)
        self.reason = reason
        self.retry = retry   # 재시도해도 고칠 수 없는 답이면 False
