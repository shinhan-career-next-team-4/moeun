import re

from app.schemas import ConditionChanges

# 이번 발화가 그 조건을 가리키는지 보는 느슨한 표현. 자연어를 해석하거나 계산하지 않고, 표현이 없을 때만 값을 뺀다.
# "개월"의 월, "원래"의 원, "한 달"의 달처럼 다른 조건의 글자가 겹치지 않게 정규식으로 가린다(M-3)
# 한글 수사. 이/일/사/오 한 글자는 이번·일단·사람·오늘 같은 어휘와 겹치므로 십·백·천이 붙은 것만 수사로 보고,
# 한 글자+만(오만, 삼만)은 뒤에 금액 동작이나 단위(원·씩·으로·정도·더·올·늘 등)가 올 때만 금액으로 본다.
# 문장 끝의 "오늘은 이만", "일만 하자"는 금액이 아니다. 앞에 한글이 붙은 것(하십시오)은 제외하고,
# 십·백·천 수사 뒤에는 조사·단위나 한글이 아닌 글자가 와야 한다(이만큼, 백화점, 십분 제외).
# 오류 방향이 둘이다. 십·백·천 수사의 조사 목록을 넓히면 세는 수가 늘어 필터가 꺼지는 쪽(이전 값이 남는 쪽)으로만
# 틀리지만, 한 글자+만을 못 세면 사용자가 말한 금액이 지워지는 쪽으로 틀린다. 그래서 한 글자+만의 뒤 말 목록은
# 금액 동작을 넓게 잡고, 어느 쪽이 맞는지 알 수 없는 입력은 센다(값을 남긴다). "천만에요"류 관용구만 뺀다
_KO_DIGIT = "[일이삼사오육칠팔구]"
_KO_FOLLOW = (
    r"(?=원|으로|로|이|가|은|는|를|을|씩|짜리|퍼|프로|개월|년|달|도|면|에(?!요)|쯤|까지|부터|정도|나|밖에|마다|과|와|보다"
    r"|(?![가-힣]))"
)
_KO_ONE_DIGIT_MAN_FOLLOW = r"(?=\s?(?:원|씩|으로|로|정도|쯤|이나|더|덜|올|늘|줄|넣|빼|추가))"
_KO_IDIOM = r"(?!천만에\s*(?:요|그|아니|별|무슨|괜찮|말씀|뭘))"
_KO_TENS = rf"(?:{_KO_DIGIT}?[십백천])+{_KO_DIGIT}?"
_KO_MAN = (
    rf"(?<![가-힣]){_KO_IDIOM}(?:{_KO_TENS}만{_KO_FOLLOW}|{_KO_DIGIT}만{_KO_ONE_DIGIT_MAN_FOLLOW})"
)
_KO_NUMERAL = rf"{_KO_MAN}|(?<![가-힣]){_KO_TENS}{_KO_FOLLOW}"
_GOAL = re.compile(r"목표(?!\s*(?:기간|기한|일|까지))|모으려|모을")
_HELD = re.compile(r"보유|가진|모아\s?둔|모았|이미|지금\s*있는")
_MONTHLY_EXPLICIT = re.compile(r"(?<!개)월|매달|한\s?달|저축(?!\s*기간)|납입|적립")
_MONTHLY = re.compile(rf"{_MONTHLY_EXPLICIT.pattern}|\d\s*만|{_KO_MAN}|만원|\d\s*원")
_PERIOD = re.compile(r"기간|기한|개월|년|\d\s*달")
# 적금·예금 바로 뒤에 금리 단어가 오면 상품 이름이 아니라 금리를 꾸미는 말이다
_RATIO_WORDS = re.compile(r"비중|비율|반반|etf|주식|배분|(?:예금|적금)(?!\s*(?:금리|이율|이자))")
# '줄이자'·'높이자'의 -이자는 제외한다. 이자율, 앞에 한글이 없는 이자, 적금이자·예금이자만 금리로 본다
_RATE_WORDS = re.compile(r"금리|이율|이자율|(?<![가-힣])이자|(?<=금)이자")
_PERCENT = re.compile(r"%|퍼센트|프로|퍼")
_NUMBER = re.compile(rf"\d+(?:\.\d+)?|{_KO_NUMERAL}")
_THOUSANDS_COMMA = re.compile(r"(?<=\d),(?=\d{3})")
# "3년 6개월", "5년(60개월)", "60개월(5년)"은 기간 하나를 두 단위로 말한 것이다
_PERIOD_COMPOUND = re.compile(r"\d+\s*년\s*\(?\s*\d+\s*개월\s*\)?|\d+\s*개월\s*\(?\s*\d+\s*년\s*\)?")
_KO_DIGIT_VALUES = dict(zip("일이삼사오육칠팔구", range(1, 10), strict=True))
_KO_UNIT_VALUES = {"십": 10, "백": 100, "천": 1000}
_AMOUNT_EXPR = re.compile(
    rf"({_NUMBER.pattern})\s*(?:억\s*(?:({_NUMBER.pattern})\s*만\s*원?)?|만\s*원?|원)?"
)


def _parse_ko_numeral(token: str) -> float | None:
    try:
        return float(token)
    except ValueError:
        pass
    if token.endswith("만"):
        base = _parse_ko_numeral(token[:-1])
        return None if base is None else base * 10_000
    total = 0
    current = 0
    for ch in token:
        if ch in _KO_DIGIT_VALUES:
            current = _KO_DIGIT_VALUES[ch]
        elif ch in _KO_UNIT_VALUES:
            total += (current or 1) * _KO_UNIT_VALUES[ch]
            current = 0
        else:
            return None
    return float(total + current)


def _candidate_krw_amounts(text: str) -> set[int]:
    """발화의 숫자·단위 표현에서 나올 수 있는 원 단위 금액 후보 집합(기존 _NUMBER·_THOUSANDS_COMMA 재사용)."""
    cleaned = _THOUSANDS_COMMA.sub("", text)
    candidates: set[int] = set()
    for match in _AMOUNT_EXPR.finditer(cleaned):
        full = match.group(0)
        n1 = _parse_ko_numeral(match.group(1))
        if n1 is None:
            continue
        if "억" in full:
            total = round(n1 * 100_000_000)
            if match.group(2):
                n2 = _parse_ko_numeral(match.group(2))
                if n2 is not None:
                    total += round(n2 * 10_000)
            candidates.add(total)
        elif "만" in full:
            candidates.add(round(n1 if match.group(1).endswith("만") else n1 * 10_000))
        else:
            candidates.add(round(n1))
    return candidates


def check_amount_units(text: str, changes: ConditionChanges) -> bool:
    """1단계 goal_amount 추출 값이 발화의 숫자·단위 표현과 맞는지 대조한다(CF12-9-3)."""
    if changes.goal_amount is None:
        return True
    lowered = text.lower()
    if _count_numbers(lowered) == 0:
        return True
    return changes.goal_amount in _candidate_krw_amounts(lowered)


def _count_numbers(text: str) -> int:
    """발화 속 숫자 덩어리 수. 쉼표 금액(500,000)과 한 기간의 복수 단위(3년 6개월)는 하나로 센다."""
    return len(_NUMBER.findall(_PERIOD_COMPOUND.sub("0년", _THOUSANDS_COMMA.sub("", text))))


def _stated_conditions(text: str) -> set[str]:
    """발화가 가리키는 조건. %는 금리·비중 단어가 있으면 그쪽에만 붙이고, 둘 다 없으면 둘 모두로 본다."""
    ratio_words = bool(_RATIO_WORDS.search(text))
    rate_words = bool(_RATE_WORDS.search(text))
    percent = bool(_PERCENT.search(text))
    goal_hit = bool(_GOAL.search(text))
    held_hit = bool(_HELD.search(text))
    monthly_hit = bool(_MONTHLY_EXPLICIT.search(text)) if (goal_hit or held_hit) else bool(_MONTHLY.search(text))
    stated = {
        "goal_amount": goal_hit,
        "held_assets": held_hit,
        "monthly_saving_amount": monthly_hit,
        "savings_ratio_pct": ratio_words or (not rate_words and percent),
        "savings_rate_input_pct": rate_words or (not ratio_words and percent),
        "target_period_months": bool(_PERIOD.search(text)),
    }
    return {name for name, hit in stated.items() if hit}


def drop_carried_conditions(text: str, changes: ConditionChanges) -> ConditionChanges:
    """history에서 이어받은 조건을 도구 인자에서 뺀다. 비교 기준은 항상 내 계획이다(FR-AI-06, D-041).

    사용자가 말한 값을 지우는 쪽이 이어받은 값이 남는 쪽보다 나쁘므로, 모호하면 남긴다. 발화의 숫자가
    인식한 조건보다 많으면("50으로 하고 기간은 3년") 단위를 못 알아본 값이 있다고 보고 그대로 둔다.
    쉼표 금액과 한 기간의 복수 단위는 숫자 하나로 센다.
    표현도 숫자도 없으면 말한 값이 없으므로 전부 뺀다.
    """
    lowered = text.lower()
    stated = _stated_conditions(lowered)
    if _count_numbers(lowered) > len(stated):
        return changes
    return ConditionChanges.model_validate({k: v for k, v in changes.changed().items() if k in stated})
