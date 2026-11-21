package com.moeun.backend.calc.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * 리포트 표의 글자 모양. 금액은 엔진이 원 단위로 반올림한 값을 그대로 쓰고, 여기서는 쉼표·부호·단위만 붙인다.
 * 화면 문구표(copy.ko.yml)와 달리 개발자가 읽는 문서라 문구를 코드에 둔다.
 */
final class ReportFormat {

    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);
    private static final int PERCENT_SCALE = 2;

    private ReportFormat() {
    }

    /** 12,345,678원 */
    static String formatWon(long amount) {
        return formatCount(amount) + "원";
    }

    /** 10,000. 컴퓨터의 언어 설정과 관계없이 쉼표를 쓴다. */
    static String formatCount(long value) {
        return new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(value);
    }

    /** +1,234원 / −1,234원 / 0원 */
    static String formatSignedWon(long amount) {
        if (amount == 0) {
            return formatWon(0);
        }
        return (amount > 0 ? "+" : "−") + formatWon(Math.abs(amount));
    }

    /** 목표 대비. 0원은 달성(CP-01). */
    static String describeAchievement(boolean achieved, long gapToGoal) {
        return (achieved ? "달성" : "미달") + " (" + formatSignedWon(gapToGoal) + ")";
    }

    /** 0.0123 → 1.23%, −0.05 → −5.00% */
    static String formatRatioAsPercent(BigDecimal ratio) {
        BigDecimal percent = ratio.multiply(PERCENT).setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
        String text = percent.abs().toPlainString() + "%";
        return percent.signum() < 0 ? "−" + text : text;
    }

    /** 세율처럼 이미 % 단위인 값. 15.4 → 15.4%, 0 → 0% */
    static String formatPercentValue(BigDecimal percent) {
        return percent.stripTrailingZeros().toPlainString() + "%";
    }
}
