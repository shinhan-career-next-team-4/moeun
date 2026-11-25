package com.moeun.backend.calc.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 부트스트랩으로 3구간을 만든다(calc-spec 7-2, 7-3, TBD-01 제안).
 * <p>경로 하나 = 관측 수익률 중에서 같은 값이 다시 뽑힐 수 있게 N번 뽑아 이은 것이다. 경로를 여러 개 만들고,
 * 시점 k마다 모든 경로의 총 평가액(보유 자산 + 적금 + ETF 세후)에서 하위 분위(BAD)·산술평균(AVG)·상위 분위(GOOD)를 고른다.
 * <p>같은 시드면 같은 결과가 나오도록 추출 순서를 고정한다: {@code Random(시드)}를 한 번 만들고
 * 경로 1의 1 … N개월, 경로 2의 1 … N개월 … 순서로 {@code nextInt(관측 수익률 개수)}를 부른다.
 * <p>메모리를 아끼려고 모든 경로를 한 달씩 같이 진행한다. 한 번에 들고 있는 금액은 경로 수만큼이다.
 */
final class BootstrapScenarios {

    private final List<BigDecimal> observedReturns;
    private final int pathCount;
    private final long seed;
    private final BigDecimal lowFraction;
    private final BigDecimal highFraction;

    /**
     * @param observedReturns 관측 한 달 수익률(오래된 달이 0번)
     * @param pathCount 경로 수
     * @param seed 난수 시드
     * @param lowFraction 나빴던 때 분위(예: 0.1)
     * @param highFraction 좋았던 때 분위(예: 0.9)
     */
    BootstrapScenarios(List<BigDecimal> observedReturns, int pathCount, long seed, BigDecimal lowFraction,
            BigDecimal highFraction) {
        if (observedReturns.isEmpty() || pathCount < 1) {
            throw new IllegalArgumentException("bootstrap needs observed returns and at least one path");
        }
        this.observedReturns = List.copyOf(observedReturns);
        this.pathCount = pathCount;
        this.seed = seed;
        this.lowFraction = lowFraction;
        this.highFraction = highFraction;
    }

    /**
     * k = 0 … N의 구간별 총 평가액(반올림 전).
     *
     * @param months 목표 기간 N
     * @param heldAssets 보유 자산(운용 수익 없음)
     * @param savings 적금 평가액. 모든 경로에서 같다
     * @param etf ETF 경로 계산
     */
    Series simulate(int months, BigDecimal heldAssets, SavingsCalculator savings, EtfPath etf) {
        int[][] picks = drawPicks(months);
        BigDecimal[] etfValues = new BigDecimal[pathCount];
        Arrays.fill(etfValues, BigDecimal.ZERO);
        List<BigDecimal> good = new ArrayList<>(months + 1);
        List<BigDecimal> average = new ArrayList<>(months + 1);
        List<BigDecimal> bad = new ArrayList<>(months + 1);
        good.add(heldAssets);
        average.add(heldAssets);
        bad.add(heldAssets);

        BigDecimal[] totals = new BigDecimal[pathCount];
        for (int k = 1; k <= months; k++) {
            BigDecimal fixedPart = heldAssets.add(savings.valueAt(k));
            for (int path = 0; path < pathCount; path++) {
                etfValues[path] = etf.grow(etfValues[path], observedReturns.get(picks[path][k - 1]));
                totals[path] = fixedPart.add(etf.afterTax(etfValues[path], k));
            }
            Arrays.sort(totals);
            good.add(Percentiles.percentile(totals, highFraction));
            average.add(Percentiles.mean(totals));
            bad.add(Percentiles.percentile(totals, lowFraction));
        }
        return new Series(good, average, bad);
    }

    private int[][] drawPicks(int months) {
        Random random = new Random(seed);
        int[][] picks = new int[pathCount][months];
        for (int path = 0; path < pathCount; path++) {
            for (int month = 0; month < months; month++) {
                picks[path][month] = random.nextInt(observedReturns.size());
            }
        }
        return picks;
    }

    /**
     * 구간별 k = 0 … N의 총 평가액(반올림 전).
     *
     * @param good 좋았던 때(상위 분위)
     * @param average 평균(산술평균)
     * @param bad 나빴던 때(하위 분위)
     */
    record Series(List<BigDecimal> good, List<BigDecimal> average, List<BigDecimal> bad) {

        Series {
            good = List.copyOf(good);
            average = List.copyOf(average);
            bad = List.copyOf(bad);
        }
    }
}
