"""여러 사용자 상황(페르소나) 데이터. 와이어프레임 숫자와 무관하게, 서버가 '목표 기준으로' 맞게 동작하는지 보려고 만든 표다.

expect: 결과가 이론상 확실한 경우만 적는다(ETF 수익률과 무관하게 정해지는 것).
  EXCEEDED      보유 자산 >= 목표
  ALL_MISSED    원금에 비해 목표가 너무 커서 좋았던 때도 미달
  ALL_ACHIEVED  원금이 목표보다 훨씬 커서 나빴던 때도 달성
  None          시장 경로에 따라 달라짐(불변 조건만 검사)
"""

def P(pid, name, goal_type, goal, months, held, monthly, rate=None, expect=None, custom=None, note=""):
    return {
        "id": pid, "name": name, "note": note, "expect": expect,
        "plan": {
            "goalType": goal_type, "goalCustomName": custom, "goalAmount": goal,
            "targetPeriodMonths": months, "heldAssets": held, "monthlySavingAmount": monthly,
            "savingsRateInputPct": rate,
            "savingsRatioPct": None,   # #241부터 키 필수. null이면 기본 비중(5:5)
        },
    }


만 = 10_000

PERSONAS = [
    P("P01", "사회초년생 비상금", "EMERGENCY_FUND", 1_000 * 만, 12, 0, 50 * 만,
      expect="ALL_MISSED", note="원금 600만, 1년에 목표 1,000만은 불가능"),
    P("P02", "전세자금(와이어프레임형)", "HOUSE", 5_000 * 만, 36, 600 * 만, 120 * 만, 3.2,
      note="원금 4,920만, 시장에 따라 달라짐"),
    P("P03", "빠듯한 결혼자금", "WEDDING", 3_000 * 만, 24, 500 * 만, 80 * 만, 3.5,
      expect="ALL_MISSED", note="원금 2,420만, 2년에 +580만 불가능"),
    P("P04", "원금 = 목표 여행", "TRAVEL", 300 * 만, 6, 60 * 만, 40 * 만,
      note="원금이 목표와 딱 같음. 적금만이면 이자로 달성, ETF 손실이면 미달 가능"),
    P("P05", "여유 있는 자동차", "CAR", 2_500 * 만, 30, 1_500 * 만, 60 * 만,
      expect="ALL_ACHIEVED", note="원금 3,300만, 나빴던 때도 달성"),
    P("P06", "10년 내 집 마련", "HOUSE", 30_000 * 만, 120, 3_000 * 만, 150 * 만, 3.0,
      note="장기·큰 금액. 그래프 121점, 계산 시간 확인"),
    P("P07", "이미 목표 초과", "TRAVEL", 500 * 만, 12, 700 * 만, 30 * 만,
      expect="EXCEEDED"),
    P("P08", "보유 = 목표", "EDUCATION", 2_000 * 만, 24, 2_000 * 만, 50 * 만,
      expect="EXCEEDED", note="같으면 초과(G3)"),
    P("P09", "비현실적 목표", "HOUSE", 100_000 * 만, 12, 0, 10 * 만,
      expect="ALL_MISSED", note="10억을 1년에 월 10만으로"),
    P("P10", "모든 값 최소", "CUSTOM", 1 * 만, 1, 0, 1 * 만, custom="최소값 확인",
      note="1개월, 원금 = 목표 1만원"),
    P("P11", "금리 0%", "CAR", 1_000 * 만, 24, 0, 30 * 만, 0,
      expect="ALL_MISSED", note="적금만이면 자산 = 원금 정확히"),
    P("P12", "금리 상한 20%", "WEDDING", 2_000 * 만, 24, 0, 80 * 만, 20,
      note="적금만이면 이자로 달성(약 2,258만)"),
    P("P13", "직접 입력 유학자금", "CUSTOM", 8_000 * 만, 60, 2_000 * 만, 100 * 만, 3.3,
      custom="유학자금", note="목표명 직접 입력, 원금 = 목표"),
    P("P14", "3개월 단기", "EMERGENCY_FUND", 300 * 만, 3, 0, 100 * 만,
      note="원금 = 목표, 아주 짧은 기간"),
    P("P15", "보유 0 장기 소액", "EDUCATION", 3_000 * 만, 84, 0, 30 * 만, 2.8,
      note="원금 2,520만, 7년 운용에 따라 갈림"),
    P("P16", "최대 기간·최대 저축", "CUSTOM", 1_000_000 * 만, 360, 0, 1_000 * 만,
      custom="최대값 확인", note="30년 361점. 계산 시간 스트레스"),
]


# 대화로 조건을 바꾸는 질문들. key = 기대하는 변경 키, value(plan) = 기대 값
def manwon(won: int) -> str:
    return f"{won // 만:,}만원"


CHAT_CASES = [
    ("월저축_절대값", lambda p: f"월 저축액을 {manwon(p['monthlySavingAmount'] + 20 * 만)}으로 하면 어떻게 돼요?",
     "monthlySavingAmount", lambda p: p["monthlySavingAmount"] + 20 * 만),
    ("월저축_한글숫자", lambda p: "매달 백만 원씩 모으면요?", "monthlySavingAmount", lambda p: 100 * 만),
    ("기간_년", lambda p: f"목표 기간을 {p['targetPeriodMonths'] // 12 + 1}년으로 하면요?",
     "targetPeriodMonths", lambda p: (p["targetPeriodMonths"] // 12 + 1) * 12),
    ("비중", lambda p: "적금 70, ETF 30으로 나누면 어때요?", "savingsRatioPct", lambda p: 70),
    ("금리", lambda p: "적금 금리가 4.5%라면요?", "savingsRateInputPct", lambda p: 4.5),
    ("목표금액", lambda p: f"목표 금액을 {manwon(p['goalAmount'] - 500 * 만)}으로 낮추면요?",
     "goalAmount", lambda p: p["goalAmount"] - 500 * 만),
]

# 대화 시나리오에 쓸 페르소나(시장에 따라 갈리거나 미달인 계획)
CHAT_PERSONAS = ["P02", "P03", "P15"]
