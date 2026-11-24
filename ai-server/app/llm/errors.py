from enum import StrEnum

from app.core.errors import ErrorCode


class ForbiddenReason(StrEnum):
    """본문 검사에 걸린 사유. Spring으로 나가지 않고 재시도 교정 지시와 로그에만 쓰는 내부 값이다."""

    STOCK_RECOMMENDATION = "stock_recommendation"
    INTERNAL_KEY = "internal_key"
    INTERNAL_LEAK = "internal_leak"
    PRODUCT_NAME = "product_name"
    EMPTY_BODY = "empty_body"


class AgentError(Exception):
    """설명 생성 실패. Spring은 chat_message를 FAILED로 바꾸고 실패 안내를 보여준다(FR-AI-04)."""

    def __init__(self, code: ErrorCode, detail: str = ""):
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail   # 원인 추적용 로그에만 쓴다. Spring에는 code만 간다(#316)


class ForbiddenAnswer(AgentError):
    """본문 검사(M-4)에 걸린 답. run이 사유로 재시도 여부를 정한다. 최종 거부 코드는 FORBIDDEN_EXPRESSION이다."""

    def __init__(self, reason: ForbiddenReason, *, retry: bool = True):
        super().__init__(ErrorCode.FORBIDDEN_EXPRESSION, reason)
        self.reason = reason
        self.retry = retry   # 재시도해도 고칠 수 없는 답이면 False
