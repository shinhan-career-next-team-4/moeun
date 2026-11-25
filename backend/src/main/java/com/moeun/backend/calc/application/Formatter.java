package com.moeun.backend.calc.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.moeun.backend.config.copy.CopyBook;

/**
 * 표시 형식을 만드는 곳 하나(CP-04). 결과 화면·AI 설명·planContext가 같은 문자열을 쓰도록 여기서만 만든다(PR-04).
 * <ul>
 *     <li>금액: 천 원 자리에서 반올림한 만원 단위 {@code 약 N만원}. 1억 이상도 단위를 바꾸지 않는다(D-065).
 *     정확히 0원은 {@code 0원}, 0만원으로 반올림되면 {@code 1만원 미만}, 음수는 앞에 {@code -}(D-061)</li>
 *     <li>목표 대비: 부호 + 금액 + 달성/미달(D-055, D-065). 라벨은 반올림 값이 아니라 엔진 판정(isAchieved)을 따른다</li>
 *     <li>차이: 부호 대신 방향 단어 {@code 약 N만원 증가/감소}, 0원은 {@code 변화 없음}(D-055)</li>
 *     <li>기간 {@code n년 n개월}, 시점 {@code YYYY년 M월}, 금리 {@code 연 N.NN%}, 세율 {@code N.N%}(D-060),
 *     비중 {@code 적금 a : ETF b}</li>
 * </ul>
 * 달성·미달·증가·감소·변화 없음·비중 템플릿은 문구표(copy/copy.ko.yml, CP-05)에서 읽는다.
 */
@Component
public class Formatter {

    private static final long TEN_THOUSAND = 10_000L;
    /** 1억은 만원 단위로 10,000. */
    private static final long EOK_IN_TEN_THOUSANDS = 10_000L;
    private static final int MONTHS_PER_YEAR = 12;
    private static final int TOTAL_RATIO_PCT = 100;
    private static final String UNDER_TEN_THOUSAND = "1만원 미만";
    private static final String BADGE_UNDER_TEN_THOUSAND = "1만 원 미만";
    private static final String BADGE_MINUS = "\u2212";

    private final CopyBook copy;

    public Formatter(CopyBook copy) {
        this.copy = copy;
    }

    /** 일반 금액(보유 자산, 세후 예상 자산, 세후 이익 등). */
    public String formatAmount(long won) {
        if (won == 0) {
            return "0원";
        }
        String sign = won < 0 ? "-" : "";
        return sign + unsignedAmount(Math.abs(won));
    }

    /** 목표 대비(세후 예상 자산 − 목표 금액). 라벨은 엔진 판정을 따르므로 표시와 판정이 어긋나지 않는다. */
    public String formatGapToGoal(long gap, boolean isAchieved) {
        String label = copy.get(isAchieved ? "template.achieved" : "template.missed");
        if (gap == 0) {
            return "0원 " + label;
        }
        String sign = gap < 0 ? "-" : "+";
        return sign + unsignedAmount(Math.abs(gap)) + " " + label;
    }

    /**
     * 결과 화면 배지(#224): {@code 달성 · 목표 +150만 원}, {@code 미달 · 목표 −20만 원}. 금액에 "약"을 붙이지 않는다.
     * 라벨은 반올림 값이 아니라 엔진 판정(isAchieved)을 따르고, 0원은 부호 없이 {@code 0원}이다.
     */
    public String formatGapBadge(long gap, boolean isAchieved) {
        String signed;
        if (gap == 0) {
            signed = "0원";
        } else {
            signed = (gap < 0 ? BADGE_MINUS : "+") + badgeAmount(Math.abs(gap));
        }
        return copy.get(isAchieved ? "template.gapBadgeAchieved" : "template.gapBadgeMissed", Map.of("gap", signed));
    }

    /** 두 계산의 차이(바꾼 쪽 − 현재). 부호 대신 방향 단어를 쓴다. 차이는 값마다 따로 반올림한다. */
    public String formatDifference(long difference) {
        if (difference == 0) {
            return copy.get("template.unchanged");
        }
        String direction = copy.get(difference > 0 ? "template.increase" : "template.decrease");
        return unsignedAmount(Math.abs(difference)) + " " + direction;
    }

    /**
     * 입력 범위 경계처럼 정확한 금액. 반올림하지 않으므로 {@code 약}을 붙이지 않는다(C3-1). 만원 단위 값만 받는다.
     * 보유 자산 하한처럼 0원이면 일반 금액과 같이 {@code 0원}이다(D-061).
     */
    public String formatExactAmount(long won) {
        if (won == 0) {
            return "0원";
        }
        if (won % TEN_THOUSAND != 0) {
            throw new IllegalArgumentException("exact amount must be in ten-thousand won");
        }
        return manwonText(won / TEN_THOUSAND, "원");
    }

    public String formatPeriod(int months) {
        return months / MONTHS_PER_YEAR + "년 " + months % MONTHS_PER_YEAR + "개월";
    }

    public String formatMonth(YearMonth month) {
        return month.getYear() + "년 " + month.getMonthValue() + "월";
    }

    /** 날짜는 일을 버리고 월까지만 쓴다(S&P500 기준일). */
    public String formatMonth(LocalDate date) {
        return formatMonth(YearMonth.from(date));
    }

    /** 적금 금리. 소수 둘째 자리까지, 끝의 0을 지우지 않는다. */
    public String formatSavingsRate(BigDecimal ratePct) {
        return "연 " + ratePct.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** 세율. 끝의 0을 뺀다(15.4·15.40 → 15.4%, D-060). */
    public String formatTaxRate(BigDecimal ratePct) {
        return ratePct.stripTrailingZeros().toPlainString() + "%";
    }

    /**
     * ETF 종목의 매매차익 세율(#150). 비과세(0%)면 이유를 붙인다: {@code 0%(국내 주식형 ETF 매매차익 비과세)}.
     */
    public String formatEtfGainTaxRate(BigDecimal ratePct) {
        String rate = formatTaxRate(ratePct);
        return ratePct.signum() == 0 ? rate + "(" + copy.get("tax.etfTaxFree") + ")" : rate;
    }

    /** 비중. 이 형식 하나만 쓴다. */
    public String formatRatio(int savingsRatioPct) {
        return copy.get("template.ratio", Map.of(
                "a", String.valueOf(savingsRatioPct),
                "b", String.valueOf(TOTAL_RATIO_PCT - savingsRatioPct)));
    }

    /** 0보다 큰 금액을 만원 단위로. 천 원 자리에서 반올림해 0만원이 되면 {@code 1만원 미만}이다. */
    private static String unsignedAmount(long won) {
        long tenThousands = BigDecimal.valueOf(won)
                .divide(BigDecimal.valueOf(TEN_THOUSAND), 0, RoundingMode.HALF_UP)
                .longValueExact();
        if (tenThousands == 0) {
            return UNDER_TEN_THOUSAND;
        }
        return "약 " + manwonText(tenThousands, "원");
    }

    /** {@link #unsignedAmount}와 같은 반올림에 "약" 없이 {@code N만 원}으로 쓴다. 0만 원으로 반올림되면 {@code 1만 원 미만}이다. */
    private static String badgeAmount(long won) {
        long tenThousands = BigDecimal.valueOf(won)
                .divide(BigDecimal.valueOf(TEN_THOUSAND), 0, RoundingMode.HALF_UP)
                .longValueExact();
        return tenThousands == 0 ? BADGE_UNDER_TEN_THOUSAND : manwonText(tenThousands, " 원");
    }

    /**
     * 만원 수를 글자로. 1억(10,000만원) 이상은 {@code 1억 3,569만원}, 딱 떨어지면 {@code 1억원}으로 쓴다(#421).
     *
     * @param won 끝에 붙일 단위. 일반은 {@code 원}, 배지는 {@code " 원"}
     */
    private static String manwonText(long tenThousands, String won) {
        if (tenThousands < EOK_IN_TEN_THOUSANDS) {
            return group(tenThousands) + "만" + won;
        }
        long eok = tenThousands / EOK_IN_TEN_THOUSANDS;
        long rest = tenThousands % EOK_IN_TEN_THOUSANDS;
        return rest == 0 ? eok + "억" + won : eok + "억 " + group(rest) + "만" + won;
    }

    /** 천 단위 쉼표. 서버 로케일이 달라도 같은 글자가 나오게 구분자를 고정한다(PR-04). */
    private static String group(long value) {
        return new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(value);
    }
}
