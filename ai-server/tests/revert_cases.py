"""대화로 되돌리기 평가 세트(#419). 표현 → 기대 도구 호출(scope, condition). condition은 snake_case 조건 이름이다.

구현(app/llm/revert.py)과 따로 둔다. 서버 보정 테스트(test_revert_conditions.py)와 실제 모델 평가
(scenarios/test_revert_scenarios.py)가 같이 쓴다.
"""

from typing import Literal

Scope = Literal["last", "all", "condition"]

# 표현만으로 범위가 분명한 경우. 서버가 모델의 선택을 이 범위로 바로잡는다
DEFINITE: dict[str, tuple[Scope, str | None]] = {
    "원래대로": ("last", None),
    "원래대로 해줘": ("last", None),
    "아까 거로": ("last", None),
    "아까 거 취소": ("last", None),
    "취소": ("last", None),
    "취소해 줘": ("last", None),
    "없던 걸로": ("last", None),
    "없던 걸로 해줘": ("last", None),
    "방금 거 되돌려줘": ("last", None),
    "되돌려줘": ("last", None),
    "이전으로 돌려줘": ("last", None),
    "처음으로": ("all", None),
    "다 원래대로": ("all", None),
    "전부 원래대로": ("all", None),
    "내 계획대로": ("all", None),
    "아니 전부": ("all", None),
    "모두 취소": ("all", None),
    "싹 다 되돌려줘": ("all", None),
    "원래 계획으로": ("all", None),
    "비중은 원래대로": ("condition", "savings_ratio_pct"),
    "저축 비중 취소": ("condition", "savings_ratio_pct"),
    "아까 바꾼 비중은 원래대로": ("condition", "savings_ratio_pct"),
    "월 저축액은 원래대로": ("condition", "monthly_saving_amount"),
    "금리는 원래대로": ("condition", "savings_rate_input_pct"),
    "기간은 원래대로": ("condition", "target_period_months"),
    "목표 금액 취소": ("condition", "goal_amount"),
    "종목은 원래대로": ("condition", "etf_product_code"),
    # 조건 이름과 '모두'·'처음' 계열 말이 함께 있으면 그 조건이다(목록 전부를 지우지 않는다)
    "비중만 원래 계획대로": ("condition", "savings_ratio_pct"),
    "금리는 원래 계획대로": ("condition", "savings_rate_input_pct"),
    "기간은 처음 계획대로 해줘": ("condition", "target_period_months"),
    "처음에 바꾼 비중 취소": ("condition", "savings_ratio_pct"),
    "전체 기간은 원래대로": ("condition", "target_period_months"),
}

# 표현만으로는 범위를 정하지 않는 경우(제외·조건 둘 이상·값으로 지목). 서버는 모델의 선택을 따른다.
# 값은 이 표현에서 기대하는 모델의 선택이다(실제 모델 평가에서 비교한다)
MODEL_DECIDES: dict[str, tuple[Scope, str | None]] = {
    "150은 취소": ("condition", "monthly_saving_amount"),
    "월 저축액은 그대로 두고 나머지 다 원래대로": ("condition", "savings_ratio_pct"),
    "비중이랑 월 저축액 다 원래대로": ("all", None),
}
