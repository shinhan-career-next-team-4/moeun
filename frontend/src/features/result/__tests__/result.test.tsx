// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
    cleanup,
    fireEvent,
    render,
    screen,
    waitFor,
    within,
} from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { planStore } from '../../../api'
import { INPUT_PATH, LOGIN_PATH, RESULT_PATH } from '../../../app/routes'
import etf0Mock from '../../../mocks/plan/get-plan.200-etf0.json'
import exceededMock from '../../../mocks/plan/get-plan.200-exceeded.json'
import planMock from '../../../mocks/plan/get-plan.200.json'
import ratio83Mock from '../../../mocks/plan/get-plan.200-ratio83.json'
import { ResultPage } from '../ResultPage'

function jsonResponse(
    status: number,
    body: unknown,
    contentType = 'application/json',
): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': contentType },
    })
}

describe('ResultPage (#49·#50, C12a-7)', () => {
    beforeEach(() => {
        planStore.clear()
    })

    afterEach(() => {
        cleanup()
        vi.unstubAllGlobals()
    })

    function renderResult() {
        return render(
            <MemoryRouter initialEntries={[RESULT_PATH]}>
                <Routes>
                    <Route path={RESULT_PATH} element={<ResultPage />} />
                    <Route path={INPUT_PATH} element={<div>입력화면</div>} />
                    <Route path={LOGIN_PATH} element={<div>로그인화면</div>} />
                </Routes>
            </MemoryRouter>,
        )
    }

    it('CALCULATED 응답이면 조건 바·결과 요약·3구간·그래프·가정·면책을 모두 표시한다', async () => {
        vi.stubGlobal(
            'fetch',
            vi
                .fn()
                .mockResolvedValue(
                    jsonResponse(200, planMock.responses[0].body),
                ),
        )
        renderResult()

    expect(await screen.findByLabelText("내 계획 조건")).toBeTruthy();
    expect(screen.getByLabelText("결과 요약").textContent).toContain("약 1,712만원");
    expect(screen.getByLabelText("세 가지 경우 비교")).toBeTruthy();
    const scenarios = screen.getByLabelText("세 가지 경우 비교");
    expect(scenarios.textContent).toContain("좋았던 때");
    expect(scenarios.textContent).toContain("평균");
    expect(scenarios.textContent).toContain("나빴던 때");
    expect(screen.getByRole("img", { name: "예상 자산 추이" })).toBeTruthy();
    expect(screen.getByLabelText("계산 가정").textContent).toContain("15.4%");
        // 대화 패널에도 같은 고지가 있어서 페이지 면책(footer)을 짚는다
        const footer = document.querySelector('footer') as HTMLElement
        expect(within(footer).getByText(/실제 수령액이나 수익을 보장하지 않습니다/)).toBeTruthy()
  });

  // 와이어프레임 03 반영(#136). 숫자는 모두 서버 display 문자열을 그대로 쓴다.
  describe("와이어프레임 반영 (#136)", () => {
    function withoutDisplayKeys(keys: string[]) {
      const body = structuredClone(planMock.responses[0].body) as { display: Record<string, string> };
      for (const key of keys) delete body.display[key];
      return body;
    }

    it("(W-03) 요약 카드에 원금·이자 분해 한 줄이 display 문자열 그대로 보인다", async () => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, planMock.responses[0].body)));
      renderResult();

      const summary = await screen.findByLabelText("결과 요약");
      expect(summary.textContent).toContain("원금 약 1,580만원 + 이자·수익 약 132만원(세후)");
    });

    it("(W-03 예외) principal·afterTaxGain display 키가 없으면 그 줄만 숨긴다", async () => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(jsonResponse(200, withoutDisplayKeys(["AVG.principal", "AVG.afterTaxGain"]))),
      );
      renderResult();

      const summary = await screen.findByLabelText("결과 요약");
      expect(summary.textContent).toContain("약 1,712만원");
      expect(summary.textContent).not.toContain("원금");
    });

    it("(W-04) 그래프에 3개 시나리오 선(빨강·초록·파랑)과 목표선 범례가 보인다", async () => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, planMock.responses[0].body)));
      renderResult();

      const chart = await screen.findByLabelText("예상 자산 추이 그래프");
      // 3구간 카드에도 '평균' 등이 있으므로 범례는 그래프 안에서 찾는다
      expect(within(chart).getByText("좋았던 때")).toBeTruthy();
      expect(within(chart).getByText("평균")).toBeTruthy();
      expect(within(chart).getByText("나빴던 때")).toBeTruthy();
      expect(within(chart).getByText("목표선")).toBeTruthy();
      expect(chart.querySelector('path[data-scenario="GOOD"]')?.getAttribute("stroke")).toBe("#dc2626");
      expect(chart.querySelector('path[data-scenario="AVG"]')?.getAttribute("stroke")).toBe("#16a34a");
      expect(chart.querySelector('path[data-scenario="BAD"]')?.getAttribute("stroke")).toBe("#2563eb");
    });

    it("(W-04 예외) GOOD·BAD monthlyPoints가 없으면 밴드 없이 평균선과 목표선만 그린다", async () => {
      const body = structuredClone(planMock.responses[0].body) as {
        result: { scenarios: { scenario: string; monthlyPoints: unknown[] }[] };
      };
      for (const scenario of body.result.scenarios) {
        if (scenario.scenario !== "AVG") scenario.monthlyPoints = [];
      }
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, body)));
      renderResult();

      const chart = await screen.findByLabelText("예상 자산 추이 그래프");
      expect([...chart.querySelectorAll("path")].filter((n) => n.getAttribute("d")?.endsWith("Z")).length).toBe(0);
      expect(within(chart).queryByText("좋았던 때~나빴던 때 범위")).toBeNull();
      expect(within(chart).getByText("평균")).toBeTruthy();
    });

    it("(W-06) 3구간 머리글과 구간 산정 설명이 보인다", async () => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, planMock.responses[0].body)));
      renderResult();

      expect(await screen.findByText("ETF가 포함된 결과는 시기에 따라 달라요")).toBeTruthy();
      // assumptions.scenarioMethod = ROLLING_DISTRIBUTION에 대응하는 짧은 문구표 문장(종목 이름은 #156에서 앞에 붙는다)
      expect(screen.getByText(/과거 10년 수익률로 1만 가지 경우를 계산해 세 구간/)).toBeTruthy();
    });

    it("(W-06 예외) scenarioMethod가 null이면 설명 줄만 숨긴다", async () => {
      const body = structuredClone(planMock.responses[0].body) as {
        result: { assumptions: { scenarioMethod: string | null } };
      };
      body.result.assumptions.scenarioMethod = null;
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, body)));
      renderResult();

      expect(await screen.findByText("ETF가 포함된 결과는 시기에 따라 달라요")).toBeTruthy();
      expect(screen.queryByText(/과거 10년 수익률로 1만 가지 경우를 계산해 세 구간/)).toBeNull();
    });

    it("(W-26) 가로축 양 끝은 display 값이고 중간 눈금은 1년 간격이다", async () => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, planMock.responses[0].body)));
      renderResult();

      const chart = await screen.findByLabelText("예상 자산 추이 그래프");
      const labels = [...chart.querySelectorAll("text")].map((node) => node.textContent);
      expect(labels).toContain("2026년 11월");
      expect(labels).toContain("2029년 11월");
      expect(labels).toContain("2027.11");
      expect(labels).toContain("2028.11");
    });

    it("(W-19) 면책 문구에 다섯 가지가 모두 들어 있다", async () => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, planMock.responses[0].body)));
      renderResult();

      await screen.findByLabelText("내 계획 조건");
      const text = document.body.textContent ?? "";
      for (const phrase of [
        "모의 계산",
        "실제 수령액이나 수익을 보장하지 않습니다",
        "특정 금융상품의 가입을 권유하지 않으며",
        "환율 변동은 반영하지 않았습니다",
        "본인의 판단과 책임",
      ]) {
        expect(text, phrase).toContain(phrase);
      }
    });
  });

    it('ALREADY_EXCEEDED면 초과 안내만 표시하고 요약·3구간·그래프를 숨긴다', async () => {
        vi.stubGlobal(
            'fetch',
            vi
                .fn()
                .mockResolvedValue(
                    jsonResponse(200, exceededMock.responses[0].body),
                ),
        )
        renderResult()

        const banner = await screen.findByLabelText('목표 초과 안내')
        expect(banner.textContent).toContain(
            '가진 자산 약 2,500만원이 목표 금액 약 2,000만원을 이미 넘었어요',
        )
        expect(screen.queryByLabelText('결과 요약')).toBeNull()
        expect(screen.queryByLabelText('세 가지 경우 비교')).toBeNull()
        expect(screen.queryByRole('img', { name: '예상 자산 추이' })).toBeNull()
    })

    it('ETF 0%면 평균 1개 카드만 그린다', async () => {
        vi.stubGlobal(
            'fetch',
            vi
                .fn()
                .mockResolvedValue(
                    jsonResponse(200, etf0Mock.responses[0].body),
                ),
        )
        renderResult()

        await screen.findByLabelText('세 가지 경우 비교')
        const scenarios = screen.getByLabelText('세 가지 경우 비교')
        expect(scenarios.textContent).toContain('평균')
        expect(scenarios.textContent).not.toContain('좋았던 때')
        expect(scenarios.textContent).not.toContain('나빴던 때')
    })

    it('500 오류면 loadFailed와 다시 시도 버튼을 표시하고 재시도로 복구한다', async () => {
        const fetchSpy = vi
            .fn()
            .mockResolvedValueOnce(
                jsonResponse(
                    500,
                    { code: 'INTERNAL_ERROR', errors: [] },
                    'application/problem+json',
                ),
            )
            .mockResolvedValueOnce(
                jsonResponse(200, planMock.responses[0].body),
            )
        vi.stubGlobal('fetch', fetchSpy)
        renderResult()

        expect((await screen.findByRole('alert')).textContent).toContain(
            '결과를 불러오지 못했어요',
        )
        fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

        await waitFor(() => {
            expect(screen.getByLabelText('결과 요약')).toBeTruthy()
        })
    })
    // 대안 영역 연결(#67, FR-ALT-01)
    it('기준 시나리오가 미달이면 대안 영역이 보이고 달성이면 보이지 않는다', async () => {
        vi.stubGlobal(
            'fetch',
            vi
                .fn()
                .mockResolvedValue(
                    jsonResponse(200, planMock.responses[0].body),
                ),
        )
        renderResult()

        expect(await screen.findByLabelText('대안')).toBeTruthy()
        cleanup()

        const achieved = structuredClone(planMock.responses[0].body) as {
            result: { scenarios: { scenario: string; isAchieved: boolean }[] }
        }
        for (const scenario of achieved.result.scenarios)
            scenario.isAchieved = true
        planStore.clear()
        vi.stubGlobal(
            'fetch',
            vi.fn().mockResolvedValue(jsonResponse(200, achieved)),
        )
        renderResult()

        await screen.findByLabelText('결과 요약')
        expect(screen.queryByLabelText('대안')).toBeNull()
    })
    // 상단 배지(#224): 서버가 준 gapBadge 문자열을 그대로 쓰고, 색은 isAchieved로 정한다(프론트가 재계산하지 않는다)
    it('결과 요약 배지는 서버 gapBadge를 그대로 보여 주고 미달은 주황, 달성은 초록이다', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn().mockResolvedValue(jsonResponse(200, planMock.responses[0].body)),
        )
        renderResult()

        const missed = await screen.findByTestId('result-gap-badge')
        expect(missed.textContent).toBe('미달 · 목표 −288만 원')
        expect(missed.className).toContain('text-warning')
        cleanup()

        const achieved = structuredClone(planMock.responses[0].body) as {
            result: { scenarios: { scenario: string; isAchieved: boolean }[] }
            display: Record<string, string>
        }
        for (const scenario of achieved.result.scenarios)
            scenario.isAchieved = true
        achieved.display['AVG.gapBadge'] = '달성 · 목표 +150만 원'
        planStore.clear()
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, achieved)))
        renderResult()

        const badge = await screen.findByTestId('result-gap-badge')
        expect(badge.textContent).toBe('달성 · 목표 +150만 원')
        expect(badge.className).toContain('text-success')
    })

    // 비교 영역 연결(#65, FR-CMP-02)
    it('결과 화면에 비교 영역이 있고 실서버 모드에서는 POST /api/compare로 비교한다(#68)', async () => {
        const fetchMock = vi
            .fn()
            .mockResolvedValue(jsonResponse(200, planMock.responses[0].body))
        vi.stubGlobal('fetch', fetchMock)
        renderResult()

        expect(await screen.findByLabelText('조건 변경 비교')).toBeTruthy()
        fireEvent.click(screen.getByRole('checkbox', { name: '월 저축액' }))
        fireEvent.change(screen.getByRole('textbox', { name: '월 저축액' }), {
            target: { value: '150' },
        })
        const compareSection = screen.getByLabelText('조건 변경 비교')
        fireEvent.click(
            within(compareSection).getByRole('button', { name: '비교하기' }),
        )

        await waitFor(() => {
            const call = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/api/compare'))
            expect(call?.[1]?.method).toBe('POST')
            expect(JSON.parse(String(call?.[1]?.body))).toEqual({
                conditionChanges: { monthlySavingAmount: 1500000 },
            })
        })
    })
    // 목표 대비 가로 막대 연결(#146)
    it('결과 요약에는 목표 대비 가로 막대가 있고 초과 안내 화면에는 없다', async () => {
        vi.stubGlobal(
            'fetch',
            vi
                .fn()
                .mockResolvedValue(
                    jsonResponse(200, planMock.responses[0].body),
                ),
        )
        renderResult()

        const summary = await screen.findByLabelText('결과 요약')
        expect(
            screen.getByRole('img', { name: '목표 대비 예상 자산' }),
        ).toBeTruthy()
        expect(summary.textContent).toContain('▲ 목표')
        cleanup()

        planStore.clear()
        vi.stubGlobal(
            'fetch',
            vi
                .fn()
                .mockResolvedValue(
                    jsonResponse(200, exceededMock.responses[0].body),
                ),
        )
        renderResult()

        await screen.findByLabelText('목표 초과 안내')
        expect(
            screen.queryByRole('img', { name: '목표 대비 예상 자산' }),
        ).toBeNull()
    })

  // 가정 보기의 비중 안내(#127, CF-08 ②, D-088)
  it("저장된 비중이 있으면 가정 보기에 금액 없는 비중 안내가 보인다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, ratio83Mock.responses[0].body)));
    renderResult();

    const assumptions = await screen.findByLabelText("계산 가정");
    expect(assumptions.textContent).toContain("월 저축 가능 금액 전체를 아래 비중대로 나눠 계산했어요");
    expect(assumptions.textContent).toContain("적금 83 : ETF 17");
    // 금액(차액)은 쓰지 않는다
    expect(assumptions.textContent).not.toContain("차액");
  });

  it("저장된 비중이 없으면 비중 안내가 없다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, planMock.responses[0].body)));
    renderResult();

    const assumptions = await screen.findByLabelText("계산 가정");
    expect(assumptions.textContent).not.toContain("월 저축 가능 금액 전체를");
  });

    // #156 2단계: 그래프 아래의 ETF 종목 비교 입구
    describe("ETF 종목 비교 영역 (#156)", () => {
        function withProductName() {
            const body = structuredClone(planMock.responses[0].body) as {
                display: Record<string, string>
            }
            body.display["assumptions.etfProductName"] = "S&P500 추종 ETF"
            return body
        }

        it("그래프 바로 다음에 ETF 비교 영역이 온다", async () => {
            vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, withProductName())))
            renderResult()

            await screen.findByLabelText("ETF 종목 비교")
            const labels = [...document.querySelectorAll("section[aria-label]")].map((node) =>
                node.getAttribute("aria-label"),
            )
            expect(labels.indexOf("ETF 종목 비교")).toBe(labels.indexOf("예상 자산 추이 그래프") + 1)
        })

        it("3구간 머리글이 display의 종목 이름을 쓴다", async () => {
            vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, withProductName())))
            renderResult()

            expect(
                await screen.findByText(
                    /기준 종목 S&P500 추종 ETF · 과거 10년 수익률로 1만 가지 경우를 계산해 세 구간/,
                ),
            ).toBeTruthy()
        })

        it("ETF 0% 계획이면 ETF 비교 영역이 없다", async () => {
            vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, etf0Mock.responses[0].body)))
            renderResult()

            await screen.findByLabelText("계산 가정")
            expect(screen.queryByLabelText("ETF 종목 비교")).toBeNull()
        })
    })

    // #292: 그래프에서 월을 누르면 그 달의 값(서버가 서식한 문자열)을 보여 준다
    describe('월별 상세 (#292)', () => {
        /** 서버가 월마다 서식한 문자열을 준다. 화면이 그대로 보이는지 보려고 값마다 다른 글자를 쓴다 */
        function withMonthlyDetails(body: typeof planMock.responses[0]['body']) {
            const clone = structuredClone(body) as unknown as {
                result: {
                    scenarios: { monthlyPoints: { month: string }[] }[]
                    monthlyDetails?: unknown[]
                }
            }
            clone.result.monthlyDetails = clone.result.scenarios[0].monthlyPoints.map((point, k) => ({
                month: point.month,
                label: `${point.month.slice(0, 4)}년 ${Number(point.month.slice(5))}월`,
                principal: `원금${k}`,
                afterTaxAsset: { GOOD: `좋${k}`, AVG: `평${k}`, BAD: `나${k}` },
            }))
            return clone
        }

        it('월 영역을 누르면 그 달의 시나리오별 자산과 누적 납입 원금이 서버 문자열 그대로 보인다', async () => {
            vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, withMonthlyDetails(planMock.responses[0].body))))
            renderResult()

            const chart = await screen.findByLabelText('예상 자산 추이 그래프')
            expect(screen.queryByRole('region', { name: '월별 상세' })).toBeNull()
            fireEvent.click(within(chart).getByRole('button', { name: '2026-12 선택' }))

            const panel = screen.getByRole('region', { name: '월별 상세' })
            expect(within(panel).getByText('2026년 12월')).toBeTruthy()
            expect(within(panel).getByText('좋1')).toBeTruthy()
            expect(within(panel).getByText('평1')).toBeTruthy()
            expect(within(panel).getByText('나1')).toBeTruthy()
            expect(within(panel).getByText('원금1')).toBeTruthy()
        })

        it('Enter로도 고르고, 다른 월을 누르면 값이 바뀐다', async () => {
            vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, withMonthlyDetails(planMock.responses[0].body))))
            renderResult()

            const chart = await screen.findByLabelText('예상 자산 추이 그래프')
            fireEvent.keyDown(within(chart).getByRole('button', { name: '2026-12 선택' }), { key: 'Enter' })
            expect(within(screen.getByRole('region', { name: '월별 상세' })).getByText('평1')).toBeTruthy()

            fireEvent.click(within(chart).getByRole('button', { name: '2027-01 선택' }))
            const panel = screen.getByRole('region', { name: '월별 상세' })
            expect(within(panel).getByText('평2')).toBeTruthy()
            expect(within(panel).queryByText('평1')).toBeNull()
        })

        it('같은 월을 다시 누르거나 닫기를 누르면 상세가 닫힌다', async () => {
            vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, withMonthlyDetails(planMock.responses[0].body))))
            renderResult()

            const chart = await screen.findByLabelText('예상 자산 추이 그래프')
            fireEvent.click(within(chart).getByRole('button', { name: '2026-12 선택' }))
            fireEvent.click(within(chart).getByRole('button', { name: '2026-12 선택' }))
            expect(screen.queryByRole('region', { name: '월별 상세' })).toBeNull()

            fireEvent.click(within(chart).getByRole('button', { name: '2026-12 선택' }))
            fireEvent.click(within(screen.getByRole('region', { name: '월별 상세' })).getByRole('button', { name: '닫기' }))
            expect(screen.queryByRole('region', { name: '월별 상세' })).toBeNull()
        })

        it('월별 표시 문자열이 없는 응답이면 월 영역은 단추가 아니다', async () => {
            vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, planMock.responses[0].body)))
            renderResult()

            const chart = await screen.findByLabelText('예상 자산 추이 그래프')
            expect(within(chart).queryByRole('button', { name: '2026-12 선택' })).toBeNull()
        })
    })
})
