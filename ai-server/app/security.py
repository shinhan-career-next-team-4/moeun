import hmac
from typing import Annotated

from fastapi import Depends, Header, HTTPException, status

from app.config import Settings, get_settings

SettingsDep = Annotated[Settings, Depends(get_settings)]


def require_internal_key(
    settings: SettingsDep,
    x_internal_api_key: Annotated[str | None, Header()] = None,
) -> None:
    """Spring만 호출할 수 있게 내부 API 키를 검사한다. 브라우저에는 노출하지 않는다."""
    expected = settings.internal_api_key.get_secret_value()
    if not x_internal_api_key or not hmac.compare_digest(x_internal_api_key, expected):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid internal api key")


InternalOnly = Annotated[None, Depends(require_internal_key)]
