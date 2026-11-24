"""S9. 목표가 분명한 타깃 사용자 7명의 여정(발표·데모 시나리오).

페르소나는 '목표까지의 거리'(이미 넘음 / 여유 / 빠듯 / 불가능에 가까움)와
'위험을 대하는 태도'(적금만 / ETF도 고려) 두 축으로 골랐다. 나이·금액은 가설(프로토 페르소나)이다.

각 여정 = 들어온 계기(trigger) → 하려는 일(job) → 계획 입력 → 질문·적용 단계.
expect: 입력 직후 판정. 시장과 무관하게 정해지는 것만 적는다.
  EXCEEDED      보유 자산 >= 목표
  ALL_MISSED    좋았던 때도 미달
  ALL_ACHIEVED  나빴던 때도 달성
  None          시장에 따라 갈림(불변 조건만 검사)
steps:
  {"ask": 질문, "changes": {키: 값}}   → RECALC_RESULT, F-4 changedKeys·값 일치
  {"ask": 질문, "changes": None}       → 비교가 생기지 않아야 함(예: 초과 계획)
  {"apply": True}                      → 직전 비교를 내 계획에 적용(F-5) → 계획 값 확인
  {"undo": True}                       → 되돌리기 → 입력 직후 계획으로
  {"ask": 질문, "risk": True}          → ETF 위험 질문: ANSWER, 서로 다른 운용사 출처 2곳 이상(FR-AI-03).
                                         적금 100% 계획이면 출처 개수는 보지 않는다
  "check": 이름                         → test_20_journeys.py의 추가 확인 함수

결과 설명(F-3)은 결과만 말하고 출처를 붙이지 않는다(기준표 Q3). ETF 위험 출처는 결과 화면 고정 블록(#374)과
위험 질문의 답으로 본다(#376).
"""

만 = 10_000
# 종목을 집지 않은 일반 질문. 종목을 집으면 그 종목 자료만 찾아 운용사가 1곳일 수 있다(ai-server _cover_orgs, #183)
RISK_QUESTION = "ETF로 하면 원금 손실이 날 수 있어요?"


def J(jid, name, trigger, job, plan, expect, alternatives, steps):
    base = {"goalCustomName": None, "savingsRateInputPct": None, "savingsRatioPct": None}
    return {"id": jid, "name": name, "trigger": trigger, "job": job, "plan": {**base, **plan},
            "expect": expect, "alternatives": alternatives, "steps": steps}


JOURNEYS = [
    J("J1", "전세 계약을 앞둔 사회초년생",
      "3년 뒤 전세 만기. 지금 계획으로 보증금을 마련할 수 있을지 불안하다",
      "이대로 가면 닿는지, 시장이 나쁘면 얼마나 모자라는지 알고 싶다",
      {"goalType": "HOUSE", "goalAmount": 5_000 * 만, "targetPeriodMonths": 36,
       "heldAssets": 600 * 만, "monthlySavingAmount": 120 * 만, "savingsRateInputPct": 3.2},
      expect=None, alternatives=None,
      steps=[
          {"ask": "우대금리를 받아서 적금 금리가 4%면 어때요?", "changes": {"savingsRateInputPct": 4.0}},
          {"ask": "월 150만 원씩 넣으면요?", "changes": {"monthlySavingAmount": 150 * 만},
           "check": "all_scenarios_improve"},
          {"apply": True},
          {"undo": True},
      ]),
    J("J2", "결혼 날짜를 정한 커플",
      "2년 뒤 결혼식. 둘이 모은 돈으로 결혼 자금이 될지 확인하고 싶다",
      "목표를 낮출지, 날짜를 미룰지 비교하고 싶다",
      {"goalType": "WEDDING", "goalAmount": 4_500 * 만, "targetPeriodMonths": 24,
       "heldAssets": 1_200 * 만, "monthlySavingAmount": 100 * 만},
      expect="ALL_MISSED", alternatives=True,
      steps=[
          {"ask": "목표를 3,500만 원으로 낮추면요?", "changes": {"goalAmount": 3_500 * 만},
           "check": "assets_unchanged"},
          {"ask": "결혼을 2년 6개월 뒤로 하면요?", "changes": {"targetPeriodMonths": 30}},
      ]),
    J("J3", "첫 차를 사려는 직장인",
      "1년 반 뒤 차를 사려는데, 투자로 더 불려야 하나 고민된다",
      "지금도 충분한지, 굳이 위험을 질 필요가 있는지 확인하고 싶다",
      {"goalType": "CAR", "goalAmount": 2_500 * 만, "targetPeriodMonths": 18,
       "heldAssets": 1_500 * 만, "monthlySavingAmount": 70 * 만},
      expect="ALL_ACHIEVED", alternatives=False,
      steps=[
          {"ask": "ETF는 빼고 적금으로만 하면요?", "changes": {"savingsRatioPct": 100},
           "check": "savings_only_still_achieved"},
      ]),
    J("J4", "첫 월급을 받은 신입",
      "첫 월급. 비상금부터 만들고 싶은데 투자는 무섭다",
      "적금만으로 1년 안에 비상금 500만 원이 되는지 확인하고 싶다",
      {"goalType": "EMERGENCY_FUND", "goalAmount": 500 * 만, "targetPeriodMonths": 12,
       "heldAssets": 0, "monthlySavingAmount": 40 * 만, "savingsRatioPct": 100},
      expect="ALL_MISSED", alternatives=True,
      steps=[
          {"ask": "월 45만 원이면요?", "changes": {"monthlySavingAmount": 45 * 만},
           "check": "savings_only_now_achieved"},
          {"ask": RISK_QUESTION, "risk": True},
      ]),
    J("J5", "유학을 준비하는 대학원 지망생",
      "2년 뒤 유학. 학비와 생활비가 부족할 것 같다",
      "얼마나 부족한지, 무엇을 바꿔야 닿는지 알고 싶다",
      {"goalType": "EDUCATION", "goalAmount": 4_000 * 만, "targetPeriodMonths": 24,
       "heldAssets": 800 * 만, "monthlySavingAmount": 90 * 만},
      expect="ALL_MISSED", alternatives=True,
      steps=[
          {"ask": "출국을 3년 뒤로 미루면요?", "changes": {"targetPeriodMonths": 36}},
          {"ask": "월 100만 원으로 늘리면요?", "changes": {"monthlySavingAmount": 100 * 만},
           "check": "not_accumulated_from_previous"},
      ]),
    J("J6", "10년 뒤 내 집 마련을 꿈꾸는 30대",
      "청약·대출을 알아보다 계약금을 언제 마련할 수 있을지 궁금해졌다",
      "장기 계획에서 ETF 비중이 결과를 얼마나 흔드는지 보고 싶다",
      {"goalType": "HOUSE", "goalAmount": 30_000 * 만, "targetPeriodMonths": 120,
       "heldAssets": 3_000 * 만, "monthlySavingAmount": 150 * 만, "savingsRateInputPct": 3.0},
      expect=None, alternatives=None,
      steps=[
          {"ask": "ETF 비중을 70%로 늘리면요?", "changes": {"savingsRatioPct": 30},
           "check": "risk_range_wider"},
          {"ask": RISK_QUESTION, "risk": True},
      ]),
    J("J7", "이미 여행 자금을 모은 사람",
      "모아 둔 돈이 충분한지 확인만 하려고 들어왔다",
      "확인하고 바로 나가고 싶다",
      {"goalType": "TRAVEL", "goalAmount": 500 * 만, "targetPeriodMonths": 12,
       "heldAssets": 700 * 만, "monthlySavingAmount": 30 * 만},
      expect="EXCEEDED", alternatives=False,
      steps=[
          {"ask": "월 50만 원이면요?", "changes": None},
      ]),
]
