"""#229 답의 범위·길이 규칙. 프롬프트 문구만 검사한다(모델 행동은 사람이 실제 모델로 확인한다)."""

from app.llm.prompts import SYSTEM_PROMPT


def _section(title: str) -> str:
    return SYSTEM_PROMPT.split(f"## {title}", 1)[1].split("\n## ", 1)[0]


def test_old_open_ended_length_hint_is_gone() -> None:
    assert "3~6문장" not in SYSTEM_PROMPT


def test_answers_only_what_was_asked() -> None:
    section = _section("답의 범위와 길이")
    assert "질문에 필요한 것만" in section
    for unasked in ("기준 월", "세율", "바꾸지 않은 조건", "좋았던 때·나빴던 때", "바꿔 보라는 권유"):
        assert unasked in section, unasked


def test_length_caps_per_answer_type() -> None:
    section = _section("답의 범위와 길이")
    assert "값 하나를 묻는 질문은 1문장" in section
    assert "RECALC_RESULT는 2문장 이내" in section
    assert "ANSWER는 3문장 이내" in section
    assert "EXPLANATION은 3문장 이내" in section   # #252: 입력 조건 나열을 빼면서 4→3(기준표 Q3)


def test_length_rule_does_not_cut_server_copy_or_quote_rules() -> None:
    section = _section("답의 범위와 길이")
    assert "서버가 붙이는" in section
    assert "인용 규칙" in section
