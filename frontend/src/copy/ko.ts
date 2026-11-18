// 화면 고정 문구(TBD-32: 해요체, 면책만 격식체. '추천'은 긍정형으로 쓰지 않는다).
// B팀이 같은 문구를 쓰게 되면 저장소 루트의 공용 문구표로 옮긴다.
import type { ErrorCode } from '@/lib/api/types'

export const copy = {
    appName: '모은',
    auth: {
        title: '로그인',
        lead: '로그인하면 내 목표와 계획을 이어서 볼 수 있어요.',
        loginId: '아이디',
        loginIdPlaceholder: '아이디를 입력하세요',
        password: '비밀번호',
        passwordPlaceholder: '비밀번호를 입력하세요',
        submit: '로그인',
        guard: '로그인하지 않으면 입력 화면과 결과 화면에 들어갈 수 없어요.',
        wrong: '아이디 또는 비밀번호를 확인해 주세요.',
        required: '아이디와 비밀번호를 입력해 주세요.',
        expired: '로그인 시간이 지나 다시 로그인해 주세요.',
        logout: '로그아웃',
        userSuffix: '님',
    },
    input: {
        title: '어떤 목돈을 모으고 싶으세요?',
        lead: '목표를 먼저 정하면, 필요한 정보만 이어서 물어볼게요.',
        goalSection: '1. 목표',
        goalHelp: '목표는 1개만 선택할 수 있어요.',
        goalNames: {
            JEONSE: '전세자금',
            WEDDING: '결혼자금',
            INDEPENDENCE: '독립자금',
            TRAVEL: '여행자금',
            CUSTOM: '직접 입력',
        },
        customName: '목표 이름',
        amountSection: '2. 목표 금액과 기간',
        goalAmount: '목표 금액',
        heldAssets: '지금 이 목표에 쓸 수 있는 돈',
        heldAssetsHelp: '"이미 목표를 넘었어요" 판단에 사용해요.',
        periodKnown: '목표 기간이 정해져 있나요?',
        periodYes: '정해져 있어요',
        periodNo: '아직 없어요',
        periodLabel: '목표 기간',
        periodYears: '년',
        periodMonths: '개월',
        savingSection: '3. 매달 저축할 수 있는 금액',
        savingKnown: '매달 얼마씩 저축할 수 있는지 알고 있나요?',
        savingYes: '알아요',
        savingNo: '잘 모르겠어요',
        monthlySaving: '월 저축 가능 금액',
        monthlySavingHelp:
            '1만원부터 시작해보아요. 자동으로 채운 금액은 이자·수익을 반영하지 않은 단순 나눗셈이에요.',
        savingUnknownHelp:
            '결과 화면에서 목표에 닿는 데 필요한 월 저축액을 보여 줘요.',
        split: (savings: string, etf: string) =>
            `기준 비중이면 적금 ${savings} · ETF ${etf}`,
        splitKeep: (savings: string, etf: string) =>
            `지금 비중이면 적금 ${savings} · ETF ${etf}`,
        periodPresetLabel: '기간 빠르게 고르기',
        rateSection: '4. 적금 금리',
        rate: '적금 금리 (연, 세전)',
        rateHelp:
            '비워 두면 은행연합회 전월 평균금리를 적용해요. 우대 금리를 받을 수 있다면 직접 입력하세요.',
        existingSection: '5. 지금 하고 있는 저축',
        existingToggle: '이미 저축 중이에요',
        existingSavings: '적금 월 납입액',
        existingEtf: 'ETF 월 납입액',
        ratioLegend: '비중을 어떻게 할까요?',
        ratioBase: (ratio: string) => `기준 비중으로 맞추기 · ${ratio}`,
        ratioKeep: (ratio: string) => `지금 비중 유지 · ${ratio}`,
        submit: '결과 보기',
        requiredMark: '필수',
        errorSummary: (count: number) =>
            `입력하지 않았거나 확인이 필요한 항목이 ${count}개 있어요.`,
    },
    errors: {
        REQUIRED: '값을 입력해 주세요.',
        OUT_OF_RANGE: '입력할 수 있는 범위를 벗어났어요.',
        NOT_UNIT: '만원 단위로 입력해 주세요.',
        INVALID_SCALE: '소수 둘째 자리까지 입력해 주세요.',
        TOO_LONG: '100자까지 입력할 수 있어요.',
        BLANK: '공백만 입력할 수는 없어요.',
        ONE_OF_PERIOD_OR_SAVING:
            '목표 기간 또는 월 저축액 중 하나를 알려주세요.',
        EXISTING_EXCEEDS_SAVING:
            '지금 납입액 합계가 월 저축 가능 금액보다 커요.',
        RATIO_CHOICE_REQUIRED: '비중을 어떻게 할지 골라 주세요.',
        FIELD_NOT_CHANGEABLE: '여기서는 바꿀 수 없는 조건이에요.',
        TOO_MANY_CHANGES: '한 번에 바꿀 수 있는 조건 수를 넘었어요.',
        NOT_NUMBER: '숫자만 입력해 주세요.',
    } satisfies Record<ErrorCode | 'NOT_NUMBER', string>,
    plan: {
        barLabel: '내 계획',
        editInput: '입력 수정',
        defaultRate: '기본금리',
        defaultSuffix: ' (기본)',
        savingUnknown: '월 저축액 미정',
        welcome: (loginId: string) =>
            `${loginId} 님, 지난번 적용한 계획이에요.`,
        resultPending: '결과 계산 화면은 준비 중이에요.',
        applyTitle: '이 조건을 내 계획으로 적용할까요?',
        applyNote:
            '적용하면 다음에 로그인했을 때도 이 계획이 보여요. 입력 화면에서 언제든 다시 수정할 수 있어요.',
        cancel: '취소',
        confirm: '적용하기',
        applied: '내 계획에 적용됐어요',
        undo: '되돌리기',
        undone: '직전 계획으로 되돌렸어요',
        conflict: '그사이 계획이 바뀌었어요. 최신 계획으로 다시 보여 드릴게요.',
        disclaimer:
            '본 결과는 입력하신 조건과 과거 데이터를 바탕으로 한 모의 계산이며, 실제 수령액이나 수익을 보장하지 않습니다. ' +
            '특정 금융상품의 가입을 권유하지 않으며, 환율 변동은 반영하지 않았습니다. ' +
            '투자 결정은 본인의 판단과 책임하에 이루어져야 합니다.',
    },
    common: {
        networkError: '서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.',
    },
} as const
