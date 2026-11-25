// import { Link } from "react-router-dom";
// import { INPUT_PATH } from "../../app/routes";
import { useEffect, useState, type ReactNode } from 'react'
import { COPY } from '../../shared/copy/copy'
import { GoalNameEditor } from './GoalNameEditor'
import type { ResultPlanView } from './types'
import { UndoPlanButton } from './UndoPlanButton'

/** 되돌리기 버튼이 반짝이는 시간(ms). globals.css의 undo-sparkle 애니메이션 길이(1.5초 × 2회)보다 조금 길게 잡는다 */
const SPARKLE_MS = 3200

interface PlanConditionBarProps {
    view: ResultPlanView
    /** 되돌리기 결과 안내. 없으면 안내 없이 되돌린다 */
    onFeedback?(message: string | null): void
}

/** 결과 화면 상단의 내 계획 조건 바(FR-RES-05, C12a-7). */
export function PlanConditionBar({
    view,
    onFeedback = () => {},
}: PlanConditionBarProps) {
    const { result, display } = view
    // 계획이 바뀌면(적용 등) 되돌리기 버튼이 한동안 반짝인다(#360). 버튼은 처음 바뀐 뒤에야 나타나므로 변화는 늘 그려지는
    // 이 바에서 감지한다. 처음 그려질 때(저장된 계획을 불러온 것)는 반짝이지 않는다
    const revision = view.revision
    const [seenRevision, setSeenRevision] = useState(revision)
    const [sparkling, setSparkling] = useState(false)
    if (revision !== seenRevision) {
        setSeenRevision(revision)
        if (seenRevision !== null && revision > seenRevision) setSparkling(true)
    }
    useEffect(() => {
        if (!sparkling) return
        const timer = window.setTimeout(() => setSparkling(false), SPARKLE_MS)
        return () => window.clearTimeout(timer)
    }, [sparkling])
    // 항목마다 작은 라벨 위, 굵은 값 아래(디자인 #406). 값은 모두 서버 display 문자열 그대로다
    const items: { label: string; value: ReactNode }[] = [
        {
            label: '목표명',
            value: (
                <GoalNameEditor
                    key="goal-name"
                    view={view}
                    onFeedback={onFeedback}
                />
            ),
        },
        { label: '목표', value: display['goalAmount'] },
        { label: '기간', value: display['conditions.targetPeriodMonths'] },
        { label: '보유 자산', value: display['conditions.heldAssets'] },
        {
            label: '월 적립',
            value: display['conditions.monthlySavingAmount'],
        },
        {
            label: '적금 금리',
            value: display['conditions.savingsRateInputPct'],
        },
        { label: '적금 : ETF', value: display['conditions.savingsRatioPct'] },
    ]

    return (
        <section
            aria-label="내 계획 조건"
            className="flex flex-col gap-3 text-text">
            <div className="flex flex-wrap items-center gap-x-4 gap-y-3">
                <span className="sr-only">내 계획 조건</span>
                <dl className="grid min-w-0 flex-1 grid-cols-2 gap-y-4 sm:grid-cols-3 lg:flex lg:items-stretch">
                    {items.map(({ label, value }) => (
                        <div
                            key={label}
                            className="flex min-w-0 flex-col items-center justify-center gap-1 px-3 text-center lg:flex-1 lg:border-l lg:border-border lg:first:border-l-0">
                            <dt className="text-xs text-text-muted">{label}</dt>
                            <dd className="text-base font-bold leading-snug text-text wrap-anywhere xl:text-lg">
                                {value}
                            </dd>
                        </div>
                    ))}
                </dl>
                <div className="ml-auto flex shrink-0 items-center gap-1">
                    {/* 되돌릴 직전 계획이 있을 때만 보인다(D-095) */}
                    {view.undoAvailable && (
                        <UndoPlanButton
                            onFeedback={onFeedback}
                            sparkling={sparkling}
                        />
                    )}
                    {/* <Link to={INPUT_PATH} className="rounded-lg px-3 py-1.5 font-semibold text-primary hover:bg-surface-muted">
            입력 수정
          </Link> */}
                </div>
            </div>
            {result.assumptions.isDefaultSavingsRate && (
                <p className="text-center text-xs text-text-muted">
                    {COPY['result.defaultSavingsRate']}
                </p>
            )}
        </section>
    )
}
