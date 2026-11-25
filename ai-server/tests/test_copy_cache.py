"""문구표 로더의 기본 경로 캐시(#213). 읽는 횟수는 줄이되, 파일을 고치면 바로 반영되고 오류가 매번 나는 지금 동작은 그대로다.

읽은 횟수는 yaml.load 호출 수로 센다.
"""

import json
import os
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
import yaml
from anthropic.types.beta import BetaTextBlock

import app.core.copy as copy_module
from app.core.copy import CopyKeyError, load_copy
from app.llm.agent import ChatAgent
from app.schemas import ChatRequest

ROOT = Path(__file__).resolve().parents[2]
COPY_PATH = ROOT / "copy" / "copy.ko.yml"
ENV = "MOEUN_COPY_PATH"


@pytest.fixture(autouse=True)
def fresh_cache(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.delenv(ENV, raising=False)
    copy_module._load_cached.cache_clear()
    yield
    copy_module._load_cached.cache_clear()


@pytest.fixture
def reads(monkeypatch: pytest.MonkeyPatch) -> list[int]:
    calls: list[int] = []
    real = copy_module.yaml.load

    def counting(*args: Any, **kwargs: Any) -> Any:
        if kwargs.get("Loader") is copy_module._UniqueKeyLoader:   # yaml.safe_load도 yaml.load를 거치므로 로더로 가린다
            calls.append(1)
        return real(*args, **kwargs)

    monkeypatch.setattr(copy_module.yaml, "load", counting)
    return calls


def write_variant(path: Path, house: str) -> Path:
    raw = yaml.safe_load(COPY_PATH.read_text(encoding="utf-8"))
    raw["goal"]["HOUSE"] = house
    path.write_text(yaml.safe_dump(raw, allow_unicode=True), encoding="utf-8")
    return path


def test_default_path_is_read_once(reads: list[int]):
    first = load_copy()
    second = load_copy()

    assert first is second
    assert len(reads) == 1


def test_environment_variable_selects_the_file(monkeypatch: pytest.MonkeyPatch, tmp_path: Path, reads: list[int]):
    default_house = load_copy().get("goal.HOUSE")
    monkeypatch.setenv(ENV, str(write_variant(tmp_path / "copy.yml", "환경변수로 바꾼 문구")))

    assert load_copy().get("goal.HOUSE") == "환경변수로 바꾼 문구"
    monkeypatch.delenv(ENV)
    assert load_copy().get("goal.HOUSE") == default_house
    assert len(reads) == 2   # 기본 파일은 캐시에 남아 있어 다시 읽지 않는다


def test_rewriting_the_same_file_is_picked_up(monkeypatch: pytest.MonkeyPatch, tmp_path: Path, reads: list[int]):
    path = tmp_path / "copy.yml"
    monkeypatch.setenv(ENV, str(write_variant(path, "첫 문구")))
    assert load_copy().get("goal.HOUSE") == "첫 문구"

    write_variant(path, "고친 뒤의 문구는 조금 더 깁니다")

    assert load_copy().get("goal.HOUSE") == "고친 뒤의 문구는 조금 더 깁니다"
    assert len(reads) == 2


def test_same_size_but_newer_file_is_picked_up(monkeypatch: pytest.MonkeyPatch, tmp_path: Path, reads: list[int]):
    """크기가 같아도 수정 시각이 달라지면 다시 읽는다. 키의 수정 시각 쪽 분기를 따로 확인한다."""
    path = tmp_path / "copy.yml"
    monkeypatch.setenv(ENV, str(write_variant(path, "가나다")))
    assert load_copy().get("goal.HOUSE") == "가나다"

    write_variant(path, "라마바")   # 글자 수가 같아 크기도 같다
    stamp = path.stat().st_mtime_ns + 5_000_000_000
    os.utime(path, ns=(stamp, stamp))

    assert load_copy().get("goal.HOUSE") == "라마바"
    assert len(reads) == 2


def test_explicit_path_is_read_every_time(reads: list[int]):
    load_copy(COPY_PATH)
    load_copy(COPY_PATH)

    assert len(reads) == 2


def test_broken_file_raises_every_time_and_is_not_cached(monkeypatch: pytest.MonkeyPatch, tmp_path: Path, reads: list[int]):
    path = tmp_path / "copy.yml"
    path.write_text("disclaimer: [열리고 닫히지 않은 목록\n", encoding="utf-8")
    monkeypatch.setenv(ENV, str(path))

    for _ in range(2):
        with pytest.raises(CopyKeyError):
            load_copy()
    assert len(reads) == 2


def test_missing_file_raises_the_same_error_every_time(monkeypatch: pytest.MonkeyPatch, tmp_path: Path):
    monkeypatch.setenv(ENV, str(tmp_path / "없는파일.yml"))

    for _ in range(2):
        with pytest.raises(CopyKeyError, match="문구표를 읽지 못했어요"):
            load_copy()


class FakeClaude:
    def __init__(self, contents: list[str]):
        self.responses = [self._answer(c) for c in contents]
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    @staticmethod
    def _answer(content: str) -> SimpleNamespace:
        payload = {"message_type": "ANSWER", "content": content, "citation_chunk_ids": []}
        return SimpleNamespace(stop_reason="end_turn", content=[BetaTextBlock(type="text", text=json.dumps(payload))])

    async def _create(self, **kwargs: Any) -> SimpleNamespace:
        return self.responses.pop(0)


class NoCalc:
    async def recalculate(self, member_id: int, baseline: Any, changes: Any) -> dict[str, Any]:
        raise AssertionError("계산을 부르지 않는 요청이다")


class NoDocs:
    async def search(self, query: str, doc_type: Any = None, top_k: Any = None, etf_codes: Any = None) -> list[Any]:
        raise AssertionError("검색을 부르지 않는 요청이다")


async def test_two_chat_requests_read_the_file_at_most_once(reads: list[int]):
    agent = ChatAgent(
        FakeClaude(["ETF는 여러 종목을 묶어 사고파는 상품이에요.", "분산 투자는 한곳에 몰지 않는 거예요."]),
        NoCalc(),
        NoDocs(),
        model="claude-opus-5-5",
        effort="medium",
        max_rounds=2,
    )

    def question(text: str) -> ChatRequest:
        return ChatRequest(memberId=1, messageId=9, mode="QUESTION", text=text, baselineScenario="AVG")

    first = await agent.run(question("ETF가 뭐야?"))
    second = await agent.run(question("분산 투자는?"))

    assert first.message_type == second.message_type == "ANSWER"
    assert len(reads) == 1   # 캐시를 비운 뒤라 첫 요청이 한 번 읽고 둘째 요청은 읽지 않는다
