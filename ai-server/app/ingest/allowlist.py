"""이용 허락이 확인된 자료만 적재하게 하는 허락 목록(FR-AI-10, NFR-INT-04).

목록은 `scripts/ingest/allowlist.yml`이다. 팀 승인을 받은 자료를 거기에 더한 PR이 허락의 기록이다.
"""

from dataclasses import dataclass
from pathlib import Path

import yaml

# 저장소 루트의 scripts/ingest/allowlist.yml (ai-server/app/ingest/allowlist.py 기준)
DEFAULT_ALLOWLIST = Path(__file__).resolve().parents[3] / "scripts" / "ingest" / "allowlist.yml"

_KEYS = ("url", "org", "org_type", "doc_type")


class NotLicensedError(Exception):
    """이용 허락이 확인되지 않은 자료이거나 허락 목록을 읽을 수 없다."""


@dataclass(frozen=True)
class AllowedSource:
    url: str
    org: str
    org_type: str
    doc_type: str


def load_allowlist(path: Path) -> list[AllowedSource]:
    try:
        raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError) as e:
        raise NotLicensedError(f"허락 목록을 읽지 못했습니다: {path}") from e
    except yaml.YAMLError as e:
        raise NotLicensedError(f"허락 목록 형식이 올바르지 않습니다: {path}") from e

    if not isinstance(raw, dict) or not isinstance(raw.get("sources"), list):
        raise NotLicensedError(f"허락 목록에 sources 리스트가 없습니다: {path}")

    sources: list[AllowedSource] = []
    for item in raw["sources"]:
        if not isinstance(item, dict) or not all(isinstance(item.get(k), str) for k in _KEYS):
            raise NotLicensedError(f"허락 목록 항목에는 {', '.join(_KEYS)}가 모두 문자열로 있어야 합니다: {item!r}")
        sources.append(AllowedSource(**{k: item[k] for k in _KEYS}))
    return sources


def check_allowed(allowlist: list[AllowedSource], *, url: str, org: str, org_type: str, doc_type: str) -> None:
    # DB의 ck_rag_disclosure_org와 같은 규칙. 목록에 있어도 어긋나면 적재 전에 막는다
    if doc_type == "DISCLOSURE" and org_type != "ASSET_MANAGER":
        raise NotLicensedError("공시(DISCLOSURE)는 운용사(ASSET_MANAGER) 자료만 적재할 수 있습니다")
    if AllowedSource(url=url, org=org, org_type=org_type, doc_type=doc_type) not in allowlist:
        raise NotLicensedError(f"이용 허락이 확인되지 않은 자료입니다: {url}")
