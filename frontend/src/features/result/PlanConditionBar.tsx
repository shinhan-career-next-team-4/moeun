// import { Link } from "react-router-dom";
// import { INPUT_PATH } from "../../app/routes";
import { useEffect, useState } from 'react'
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
    const items = [
        <GoalNameEditor key="goal-name" view={view} onFeedback={onFeedback} />,
        `목표 ${display['goalAmount']}`,
        display['conditions.targetPeriodMonths'],
        `보유 ${display['conditions.heldAssets']}`,
        `월 ${display['conditions.monthlySavingAmount']}`,
        `적금 금리 ${display['conditions.savingsRateInputPct']}`,
        display['conditions.savingsRatioPct'],
    ]

    return (
        <section
            aria-label="내 계획 조건"
            className="flex flex-col gap-1 text-sm text-text">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span className="font-bold text-primary-strong">
                    내 계획 조건
                </span>
                {items.map((item, index) => (
                    <span
                        key={index}
                        className="after:ml-3 after:text-border after:content-['·'] last:after:content-none">
                        {item}
                    </span>
                ))}
                <div className="ml-auto flex items-center gap-1">
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
                <p className="text-xs text-text-muted">
                    {COPY['result.defaultSavingsRate']}
                </p>
            )}
        </section>
    )
}
