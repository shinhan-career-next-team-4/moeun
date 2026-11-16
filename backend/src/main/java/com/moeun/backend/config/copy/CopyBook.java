package com.moeun.backend.config.copy;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 문구표(copy/copy.ko.yml)를 읽어 키로 문장을 찾아 준다(CP-05). ai-server가 같은 파일을 같은 키로 읽는다.
 * 없는 키, 빈 값, 중복 키는 조용히 넘기지 않고 로드 또는 조회 때 실패한다.
 */
public final class CopyBook {

    /** 필수 키 중 enum으로 만들 수 없는 것. 문구표를 바꾸면 ai-server의 REQUIRED_KEYS도 함께 고친다. */
    private static final List<String> FIXED_KEYS = List.of(
            "outOfScope", "outOfScope.tax", "disclaimer", "tax.note",
            "chat.unchangeableCondition", "chat.noPlan", "chat.alreadyExceeded", "chat.failed",
            "chat.pollingFailed", "chat.sendFailed",
            "template.achieved", "template.missed", "template.increase", "template.decrease",
            "template.unchanged", "template.ratio",
            "toast.applyStaleComparison", "toast.applyPlanChanged", "toast.applyNotApplicable",
            "toast.applyFailed", "comparison.loadFailed",
            "common.networkError", "auth.invalidCredentials", "auth.loginFailed", "auth.logoutFailed",
            "input.saveFailed", "input.invalidAmountFormat", "input.currentRatioLabel", "input.heldAssetsRecheck",
            "plan.changedElsewhere", "undo.nothingToUndo", "undo.failed",
            "result.loadFailed", "result.alreadyExceeded", "result.notReachedInPeriod", "result.scenarioNotice",
            "result.defaultSavingsRate", "scenario.GOOD", "scenario.AVG", "scenario.BAD",
            "validation.REQUIRED", "validation.OUT_OF_RANGE", "validation.NOT_INTEGER",
            "validation.TOO_MANY_DECIMALS",
            "unit.manwon", "unit.won", "unit.month", "unit.pct", "unit.char");

    private static final List<String> CONDITION_CODES =
            List.of("monthlySavingAmount", "savingsRatioPct", "savingsRateInputPct", "targetPeriodMonths");

    private static final List<String> ERROR_CODES = List.of("VALIDATION_FAILED", "FIELD_NOT_CHANGEABLE", "NO_PLAN",
            "ALREADY_EXCEEDED", "MALFORMED_REQUEST", "INVALID_INTERNAL_KEY", "CALC_FAILED");

    private static final List<String> ACHIEVEMENT_CHANGES =
            List.of("STAYS_ACHIEVED", "STAYS_MISSED", "MISS_TO_ACHIEVE", "ACHIEVE_TO_MISS");

    /**
     * calc의 ExcludedItem·ScenarioMethod와 같은 코드 목록(G-14). calc가 이 로더를 쓰게 되므로(Formatter #28)
     * enum을 import하면 모듈 순환이 된다. enum에 코드가 늘면 CopyBookTest가 문구표에 그 코드의 문장이 없다고 실패하니,
     * 그때 이 목록에도 같은 코드를 더한다.
     */
    private static final List<String> EXCLUDED_CODES = List.of(
            "ETF_TOTAL_EXPENSE", "EXCHANGE_RATE", "ETF_DISTRIBUTION", "HELD_ASSET_RETURN", "SAVINGS_REDEPOSIT");

    private static final List<String> SCENARIO_METHOD_CODES = List.of("CAGR", "ROLLING_DISTRIBUTION");

    /** D-075 목표 종류 코드. TODO: #19 GoalType enum이 생기면 enum 기반으로 바꾼다. */
    private static final List<String> GOAL_CODES =
            List.of("HOUSE", "CAR", "WEDDING", "EDUCATION", "TRAVEL", "EMERGENCY_FUND", "CUSTOM");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private final Map<String, String> sentences;

    private CopyBook(Map<String, String> sentences) {
        this.sentences = Collections.unmodifiableMap(sentences);
    }

    /** 문구표를 읽고 필수 키를 검증한다. 문제가 있으면 예외를 던지므로 기동 시 실패한다. */
    public static CopyBook load(Path path) {
        Map<String, String> flat = new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            flatten("", new Yaml(new SafeConstructor(options)).load(reader), flat);
        } catch (IOException e) {
            throw new IllegalStateException("문구표를 읽지 못했어요: " + path, e);
        }

        List<String> blank = flat.entrySet().stream()
                .filter(entry -> entry.getValue().isBlank()).map(Map.Entry::getKey).sorted().toList();
        if (!blank.isEmpty()) {
            throw new CopyKeyMissingException("문구표에 빈 값이 있어요: " + String.join(", ", blank));
        }
        Set<String> missing = new TreeSet<>(requiredKeys());
        missing.removeAll(flat.keySet());
        if (!missing.isEmpty()) {
            throw new CopyKeyMissingException("문구표에 없는 키가 있어요: " + String.join(", ", missing));
        }
        return new CopyBook(flat);
    }

    private static List<String> requiredKeys() {
        List<String> keys = new ArrayList<>(FIXED_KEYS);
        EXCLUDED_CODES.forEach(code -> keys.add("excluded." + code));
        SCENARIO_METHOD_CODES.forEach(code -> keys.add("scenarioMethod." + code));
        GOAL_CODES.forEach(code -> keys.add("goal." + code));
        CONDITION_CODES.forEach(code -> keys.add("condition." + code));
        CONDITION_CODES.forEach(code -> keys.add("chat.exampleQuestion." + code));
        ERROR_CODES.forEach(code -> keys.add("error.title." + code));
        ACHIEVEMENT_CHANGES.forEach(value -> keys.add("achievementChange." + value));
        return keys;
    }

    private static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> flatten(prefix.isEmpty() ? key.toString() : prefix + "." + key, value, out));
            return;
        }
        if (prefix.isEmpty()) {
            throw new IllegalStateException("문구표 형식이 올바르지 않아요");
        }
        if (out.put(prefix, node == null ? "" : node.toString()) != null) {
            throw new IllegalStateException("문구표에 중복 키가 있어요: " + prefix);
        }
    }

    /** 평탄화된 키 전체. */
    public Set<String> keys() {
        return sentences.keySet();
    }

    public String get(String key) {
        return get(key, Map.of());
    }

    /**
     * 키로 문장을 찾고 {@code {이름}} 자리 표시자를 params 값으로 바꾼다.
     *
     * @throws CopyKeyMissingException 없는 키일 때
     * @throws IllegalArgumentException 자리 표시자에 넣을 값이 없을 때
     */
    public String get(String key, Map<String, String> params) {
        String sentence = sentences.get(key);
        if (sentence == null) {
            throw new CopyKeyMissingException("문구표에 없는 키예요: " + key);
        }
        return PLACEHOLDER.matcher(sentence).replaceAll(match -> {
            String value = params.get(match.group(1));
            if (value == null) {
                throw new IllegalArgumentException("문구 " + key + "의 자리 표시자 " + match.group(1) + " 값이 없어요");
            }
            return Matcher.quoteReplacement(value);
        });
    }
}
