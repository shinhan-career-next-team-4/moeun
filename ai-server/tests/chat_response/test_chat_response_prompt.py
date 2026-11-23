"""#252 대화 응답 기준표(docs/chat-response-guide.md)에 맞춘 프롬프트 규칙. 문구만 검사한다(모델 행동은 e2e S8)."""

from app.llm.prompts import EXPLAIN_INSTRUCTION, SYSTEM_PROMPT


def _section(title: str) -> str:
    return SYSTEM_PROMPT.split(f"## {title}", 1)[1].split("\n## ", 1)[0]


def test_no_notice_repetition_rule() -> None:
    assert "한 번만 밝힙니다" not in SYSTEM_PROMPT
    section = _section("답의 범위와 길이")
    assert "화면에 고정" in section
    for phrase in ("과거 데이터로 만든 추정", "수익을 약속하지 않아요", "보장하지 않아요"):
        assert phrase in section, phrase
    assert "원금 손실" in section   # ETF 위험 설명은 막지 않는다


def test_return_question_does_not_state_promise() -> None:
    rule = next(line for line in SYSTEM_PROMPT.splitlines() if "연 수익률 숫자" in line)
    rule_block = SYSTEM_PROMPT[SYSTEM_PROMPT.index(rule):].split("\n- ", 1)[0]
    assert "세 경우" in rule_block
    assert "쓰지 않습니다" in rule_block


def test_explanation_does_not_list_inputs() -> None:
    assert "어떤 조건으로 계산했는지" not in EXPLAIN_INSTRUCTION
    section = _section("답의 범위와 길이")
    assert "EXPLANATION은 3문장 이내" in section
    assert "입력 조건" in section and "나열하지 않습니다" in section
