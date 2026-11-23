import { useEffect, useId, useRef, useState } from 'react'
import { api } from '../../api'
import { COPY } from '../../shared/copy/copy'
import { Button } from '../../shared/ui'
import type { ComparisonResult } from './requestComparison'
import {
    fetchEtfProducts,
    requestEtfComparison,
    type EtfCompareDeps,
    type EtfProduct,
} from './requestEtfComparison'
import type { ComparisonView, ConditionChanges } from './types'

/** 적용할 거리: 고른 종목과, 그 종목의 결과를 받은 때의 revision(P-6 expectedRevision) */
export interface EtfApplyRequest {
    changes: ConditionChanges
    revision: number
}

interface EtfCompareSectionProps {
    /** 내 계획의 종목 이름(`display["assumptions.etfProductName"]`). 비면 ETF 0%라 영역을 그리지 않는다 */
    currentProductName: string
    /** 내 계획의 종목 코드(`result.assumptions.etfProductCode`). 목록에서 지금 종목을 찾는 기준이다 */
    currentProductCode?: string
    /** 내 계획의 revision. 바뀌면 받아 둔 결과의 '지금 종목'이 더 이상 내 계획이 아니라서 쓰지 않는다 */
    revision: number | null
    /**
     * 고른 종목을 내 계획에 적용한다. 확인창 없이 바로 보낸다(#156). P-6 호출과 응답 처리는 결과 화면이 맡고,
     * 적용에 성공하면 새 revision을, 아니면 null을 돌려준다.
     */
    onApply(request: EtfApplyRequest): Promise<number | null>
    /** 테스트에서 바꿔 끼운다. 기본은 앱의 API 래퍼 */
    deps?: EtfCompareDeps
}

const DEFAULT_DEPS: EtfCompareDeps = {
    request: (method, path, body) => api.request(method, path, body),
}

/** 종목 코드별 비교 결과와, 그것을 받은 때의 내 계획 revision */
interface Options {
    revision: number | null
    /** 이 결과가 '지금 종목'으로 본 종목 코드 */
    currentCode?: string
    byCode: Record<string, ComparisonResult>
}

/** 종목 코드가 기준이다. 이름은 서버 두 곳(display와 종목 목록)이 글자까지 같아야 맞아서 코드가 없을 때만 쓴다 */
function findCurrent(
    products: EtfProduct[],
    code: string | undefined,
    name: string,
): EtfProduct | undefined {
    return (
        products.find((product) => product.code === code) ??
        products.find((product) => product.displayName === name)
    )
}

interface EtfOptionCardProps {
    product: EtfProduct
    isCurrent: boolean
    /** 값을 읽을 비교 응답. 지금 종목은 아무 비교의 current 쪽, 다른 종목은 자기 비교의 changed 쪽을 읽는다 */
    view: ComparisonView | null
    /** 이 종목의 비교를 받지 못한 이유 */
    failure: string | null
    disabled: boolean
    onChoose(): void
}

/** ETF 한 종목의 안(案). 카드를 누르면 바로 적용한다. 값은 모두 서버 display 문자열이다(NFR-INT-01). */
function EtfOptionCard({
    product,
    isCurrent,
    view,
    failure,
    disabled,
    onChoose,
}: EtfOptionCardProps) {
    const nameId = useId()
    const valuesId = useId()
    const side = isCurrent ? 'current' : 'changed'
    const baseline = view?.diff.baselineScenario
    const rows =
        view && baseline
            ? [
                  {
                      label: COPY['compare.rowAvgAsset'],
                      value: view.display[`${side}.${baseline}.afterTaxAsset`],
                  },
                  {
                      label: COPY['etfCompare.rowDelta'],
                      value: isCurrent
                          ? undefined
                          : view.display[`diff.${baseline}.afterTaxAssetDelta`],
                  },
                  {
                      label: COPY['compare.rowGapToGoal'],
                      value: view.display[`${side}.${baseline}.gapToGoal`],
                  },
                  {
                      label: COPY['compare.rowBad'],
                      value: view.display[`${side}.BAD.afterTaxAsset`],
                  },
              ]
            : []

    return (
        // 카드 전체가 하나의 단추다. 단추 안에는 문장 요소(span)만 둔다
        <button
            type="button"
            disabled={isCurrent || disabled || failure !== null}
            aria-labelledby={nameId}
            aria-describedby={valuesId}
            onClick={onChoose}
            className={`flex flex-col gap-3 rounded-xl border p-4 text-left transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary ${
                isCurrent
                    ? 'cursor-default border-primary bg-primary-soft'
                    : 'border-border bg-surface hover:bg-surface-muted disabled:cursor-not-allowed disabled:opacity-60'
            }`}
        >
            <span className="flex items-center justify-between gap-2">
                <span id={nameId} className="text-sm font-semibold text-text">
                    {product.displayName}
                </span>
                {isCurrent && (
                    <span className="shrink-0 rounded-full bg-surface px-2 py-0.5 text-xs font-semibold text-primary-strong">
                        {COPY['etfCompare.currentBadge']}
                    </span>
                )}
            </span>
            <span id={valuesId} className="flex flex-col gap-1">
                {rows
                    .filter((row) => row.value)
                    .map((row) => (
                        <span
                            key={row.label}
                            className="flex items-baseline justify-between gap-3"
                        >
                            <span className="shrink-0 text-xs text-text-muted">
                                {row.label}
                            </span>
                            <span className="text-right text-sm font-medium text-text">
                                {row.value}
                            </span>
                        </span>
                    ))}
                {failure !== null && (
                    <span className="text-xs text-danger">{failure}</span>
                )}
            </span>
            {!isCurrent && failure === null && (
                <span className="text-xs font-semibold text-primary">
                    {COPY['etfCompare.choose']}
                </span>
            )}
        </button>
    )
}

/**
 * 결과 화면 ETF 종목 3안(#156 2단계, 계약 C12a-9-3).
 *
 * 화면이 뜨면 서버가 정한 종목(`GET /api/etf-products`)을 카드 한 장씩 바로 보여 준다. 지금 종목이 아닌 종목의
 * 결과는 `POST /api/compare`로 미리 받아 카드에 채운다. 카드를 누르면 확인창 없이 바로 내 계획에 적용하고
 * (되돌리기 토스트로 돌아간다), 적용한 종목이 지금 종목이 되도록 카드를 새로 받는다. 숫자는 모두 서버
 * display 문자열이고, 종목은 상품명 대신 화면 이름으로만 보여 준다(CP-06).
 */
export function EtfCompareSection({
    currentProductName,
    currentProductCode,
    revision,
    onApply,
    deps,
}: EtfCompareSectionProps) {
    const transport = deps ?? DEFAULT_DEPS
    const [products, setProducts] = useState<EtfProduct[] | null>(null)
    const [options, setOptions] = useState<Options | null>(null)
    // 고를 종목이 있는 계획이면 처음부터 받는 중이다(마운트 효과가 곧바로 받아 온다)
    const [loading, setLoading] = useState(Boolean(currentProductName))
    const [listError, setListError] = useState<string | null>(null)
    const [choosing, setChoosing] = useState(false)
    // 받아 오기를 시작할 때마다 올린다. 늦게 온 응답이 더 나중 상태를 덮어쓰지 않게 한다
    const generation = useRef(0)
    // 요청이 날아가 있는 동안 또 보내지 않는다(다시 시도를 두 번 눌러도 한 번이다)
    const loadingRef = useRef(false)
    // 적용 요청이 날아가 있는 동안 다른 카드를 눌러도 보내지 않는다
    const choosingRef = useRef(false)

    /**
     * 종목 목록과, 지금 종목이 아닌 종목마다의 비교를 받는다.
     * 적용 직후에는 계획이 아직 다시 읽히기 전이라 새 revision과 새 지금 종목을 직접 넘긴다.
     */
    async function fetchOptions(at: {
        revision: number | null
        currentCode?: string
    }) {
        if (loadingRef.current) return
        loadingRef.current = true
        generation.current += 1
        const mine = generation.current

        // 종목 목록은 한 번만 받는다. 종목은 자주 바뀌지 않는다
        const listed =
            products !== null
                ? { ok: true as const, products }
                : await fetchEtfProducts(transport)
        if (!listed.ok) {
            loadingRef.current = false
            setLoading(false)
            setListError(listed.message)
            return
        }
        setProducts(listed.products)

        const currentCode =
            at.currentCode ??
            findCurrent(listed.products, currentProductCode, currentProductName)
                ?.code
        const others = listed.products.filter(
            (product) => product.code !== currentCode,
        )
        const compared = await Promise.all(
            others.map(
                async (product) =>
                    [
                        product.code,
                        await requestEtfComparison(transport, product.code),
                    ] as const,
            ),
        )
        loadingRef.current = false
        setLoading(false)
        if (generation.current !== mine) return
        setOptions({
            revision: at.revision,
            currentCode,
            byCode: Object.fromEntries(compared),
        })
    }

    /** 다시 받기. 진행 표시를 켜고 받아 온다(이벤트 처리기에서 부른다) */
    function loadOptions(
        at: { revision: number | null; currentCode?: string } = { revision },
    ) {
        if (loadingRef.current) return
        setLoading(true)
        setListError(null)
        void fetchOptions(at)
    }

    // 화면이 뜨면 바로 받는다. ETF 0% 계획(종목 이름 없음)은 고를 종목이 없어 받지 않는다
    useEffect(() => {
        if (currentProductName) void fetchOptions({ revision })
        // 처음 한 번만. 그 뒤의 계획 변화는 아래 isStale 안내와 적용 직후 다시 받기가 다룬다
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    async function choose(product: EtfProduct) {
        const option = freshOptions?.byCode[product.code]
        if (
            choosingRef.current ||
            freshOptions === null ||
            freshOptions.revision === null ||
            option?.ok !== true
        ) {
            return
        }
        choosingRef.current = true
        setChoosing(true)
        const newRevision = await onApply({
            changes: { etfProductCode: product.code },
            revision: freshOptions.revision,
        })
        choosingRef.current = false
        setChoosing(false)
        if (newRevision === null) return
        // 바뀐 종목이 지금 종목이 되었으니 나머지 두 종목의 결과를 새 계획 기준으로 다시 받는다
        loadOptions({ revision: newRevision, currentCode: product.code })
    }

    if (!currentProductName) return null

    // 받아 둔 결과는 그때의 revision과 함께 둔다. 내 계획이 바뀌면 '지금 종목'이 달라져 쓰지 않는다
    const freshOptions =
        options !== null && options.revision === revision ? options : null
    const isStale = options !== null && options.revision !== revision
    const currentProduct =
        products === null
            ? undefined
            : products.find(
                  (product) => product.code === freshOptions?.currentCode,
              ) ?? findCurrent(products, currentProductCode, currentProductName)
    // 지금 종목 카드의 값은 어느 비교든 current 쪽에 들어 있다
    const currentView =
        Object.values(freshOptions?.byCode ?? {}).find(
            (option): option is { ok: true; view: ComparisonView } =>
                option.ok,
        )?.view ?? null

    return (
        <section
            aria-label={COPY['etfCompare.sectionLabel']}
            className="flex flex-col gap-4 rounded-xl border border-border bg-surface p-5"
        >
            <div>
                <h2 className="text-base font-semibold text-text">
                    {COPY['etfCompare.title']}
                </h2>
                <p className="mt-1 text-xs leading-relaxed text-text-muted">
                    {COPY['etfCompare.notice']}
                </p>
            </div>

            {listError !== null && (
                <div className="flex flex-col items-start gap-2">
                    <p role="status" className="text-sm text-text-muted">
                        {listError}
                    </p>
                    <Button
                        variant="secondary"
                        onClick={() => loadOptions()}
                    >
                        {COPY['etfCompare.retry']}
                    </Button>
                </div>
            )}

            {loading && (
                <p role="status" className="text-sm text-text-muted">
                    {COPY['etfCompare.loading']}
                </p>
            )}

            {isStale && !loading && (
                <div className="flex flex-col items-start gap-2">
                    <p role="status" className="text-sm text-text-muted">
                        {COPY['etfCompare.planChanged']}
                    </p>
                    <Button
                        variant="secondary"
                        onClick={() => loadOptions()}
                    >
                        {COPY['etfCompare.retry']}
                    </Button>
                </div>
            )}

            {products !== null && products.length <= 1 && (
                <p role="status" className="text-sm text-text-muted">
                    {COPY['etfCompare.onlyOne']}
                </p>
            )}

            {products !== null &&
                products.length > 1 &&
                freshOptions !== null && (
                    <div
                        role="group"
                        aria-label={COPY['etfCompare.pickLabel']}
                        className="grid gap-4 sm:grid-cols-3"
                    >
                        {products.map((product) => {
                            const isCurrent =
                                product.code === currentProduct?.code
                            const option = freshOptions.byCode[product.code]
                            return (
                                <EtfOptionCard
                                    key={product.code}
                                    product={product}
                                    isCurrent={isCurrent}
                                    view={
                                        isCurrent
                                            ? currentView
                                            : option?.ok
                                              ? option.view
                                              : null
                                    }
                                    failure={
                                        option !== undefined && !option.ok
                                            ? option.message
                                            : null
                                    }
                                    disabled={choosing}
                                    onChoose={() => void choose(product)}
                                />
                            )
                        })}
                    </div>
                )}
        </section>
    )
}
