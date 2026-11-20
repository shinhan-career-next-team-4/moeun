package com.moeun.backend.config.copy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import com.moeun.backend.calc.ExcludedItem;
import com.moeun.backend.calc.ScenarioMethod;

/**
 * 문구표 로더 테스트(Issue #18). 실제 문구표(저장소 루트 copy/copy.ko.yml)를 읽는다.
 * 음성 테스트는 문구표를 임시 디렉터리로 복사해 키를 지우거나 비운다.
 * ai-server의 tests/test_copy.py가 같은 문장을 같은 키로 단언한다(두 서버가 같은 문구를 읽는다).
 */
class CopyBookTest {

    /** Gradle 테스트의 작업 디렉터리는 backend/ 이다. */
    private static final Path COPY_PATH = Path.of("../copy/copy.ko.yml");

    private static final String TAX_NOTE_KEY = "tax.note";

    /** D-075 목표 종류 코드. TODO: #19 GoalType enum이 생기면 enum 기반(GoalType.values())으로 교체한다. */
    private static final List<String> GOAL_CODES =
            List.of("HOUSE", "CAR", "WEDDING", "EDUCATION", "TRAVEL", "EMERGENCY_FUND", "CUSTOM");

    private static final List<String> CONDITION_CODES =
            List.of("monthlySavingAmount", "savingsRatioPct", "savingsRateInputPct", "targetPeriodMonths");

    private static final List<String> ERROR_CODES = List.of("VALIDATION_FAILED", "FIELD_NOT_CHANGEABLE", "NO_PLAN",
            "ALREADY_EXCEEDED", "MALFORMED_REQUEST", "INVALID_INTERNAL_KEY", "CALC_FAILED");

    private static final List<String> ACHIEVEMENT_CHANGES =
            List.of("STAYS_ACHIEVED", "STAYS_MISSED", "MISS_TO_ACHIEVE", "ACHIEVE_TO_MISS");

    /** enum으로 만들 수 없는 고정 키. enum 기반 키는 {@link #requiredKeys()}가 더한다. */
    private static final List<String> FIXED_KEYS = List.of(
            "outOfScope", "outOfScope.tax", "disclaimer", TAX_NOTE_KEY,
            "chat.unchangeableCondition", "chat.noPlan", "chat.alreadyExceeded",
            "chat.failed", "chat.pollingFailed", "chat.sendFailed",
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

    /** 필수 키 전체. ExcludedItem·ScenarioMethod에 코드가 늘면 이 목록이 늘어나 문구표에 없을 때 실패한다. */
    private static List<String> requiredKeys() {
        List<String> keys = new ArrayList<>(FIXED_KEYS);
        for (ExcludedItem item : ExcludedItem.values()) {
            keys.add("excluded." + item.name());
        }
        for (ScenarioMethod method : ScenarioMethod.values()) {
            keys.add("scenarioMethod." + method.name());
        }
        GOAL_CODES.forEach(code -> keys.add("goal." + code));
        CONDITION_CODES.forEach(code -> keys.add("condition." + code));
        CONDITION_CODES.forEach(code -> keys.add("chat.exampleQuestion." + code));
        ERROR_CODES.forEach(code -> keys.add("error.title." + code));
        ACHIEVEMENT_CHANGES.forEach(value -> keys.add("achievementChange." + value));
        return keys;
    }

    static Stream<String> requiredKeyStream() {
        return requiredKeys().stream();
    }

    /** 글자 그대로 일치해야 하는 문장(키, 자리 표시자 값, 기대 문장). 자리 표시자 없는 키는 값이 비어 있다. */
    static Stream<Arguments> verbatimSentences() {
        return Stream.of(
                Arguments.of("excluded.EXCHANGE_RATE", Map.of(), "환율 변동은 반영하지 않았어요"),
                Arguments.of("chat.unchangeableCondition", Map.of(),
                        "목표 종류·기존 납입액은 여기서 바꿀 수 없어요. 보유 자산은 입력 수정에서 바꿀 수 있어요."),
                Arguments.of("chat.noPlan", Map.of(), "먼저 목표와 조건을 입력해 계획을 만들어 주세요"),
                Arguments.of("template.ratio", Map.of("a", "60", "b", "40"), "적금 60 : ETF 40"),
                Arguments.of("chat.exampleQuestion.monthlySavingAmount", Map.of(), "월 저축액을 바꿔 보고 싶어요"),
                Arguments.of("condition.monthlySavingAmount", Map.of(), "월 저축액"),
                Arguments.of("condition.savingsRatioPct", Map.of(), "저축 비중"),
                Arguments.of("condition.savingsRateInputPct", Map.of(), "적금 금리"),
                Arguments.of("condition.targetPeriodMonths", Map.of(), "목표 기간"),
                Arguments.of("toast.applyStaleComparison", Map.of(), "가장 최근 비교 결과만 적용할 수 있어요"),
                Arguments.of("toast.applyPlanChanged", Map.of(), "계획이 다른 곳에서 바뀌었어요"),
                Arguments.of("toast.applyNotApplicable", Map.of(), "지금 기준으로는 적용할 수 없는 값이에요"),
                Arguments.of("toast.applyFailed", Map.of(), "적용하지 못했어요"),
                Arguments.of("comparison.loadFailed", Map.of(), "비교 결과를 불러오지 못했어요"),
                Arguments.of("unit.manwon", Map.of("v", "500"), "500만원"),
                Arguments.of("unit.won", Map.of("v", "0"), "0원"),
                Arguments.of("unit.month", Map.of("y", "2", "m", "6"), "2년 6개월"),
                Arguments.of("unit.pct", Map.of("v", "3.5"), "연 3.5%"),
                Arguments.of("unit.char", Map.of("n", "100"), "100자"),
                Arguments.of("goal.HOUSE", Map.of(), "내 집 마련"),
                Arguments.of("goal.CAR", Map.of(), "자동차"),
                Arguments.of("goal.WEDDING", Map.of(), "결혼"),
                Arguments.of("goal.EDUCATION", Map.of(), "교육"),
                Arguments.of("goal.TRAVEL", Map.of(), "여행"),
                Arguments.of("goal.EMERGENCY_FUND", Map.of(), "비상금"),
                Arguments.of("goal.CUSTOM", Map.of(), "직접 입력"));
    }

    /** 문장에 들어 있어야 하는 조각(키, 자리 표시자 값, 포함할 조각들). */
    static Stream<Arguments> sentenceFragments() {
        return Stream.of(
                Arguments.of("outOfScope", Map.of(), List.of("이 질문은 모은에서 다루지 않아요")),
                Arguments.of("outOfScope.tax", Map.of(), List.of("전문가")),
                Arguments.of("chat.sendFailed", Map.of(), List.of("보내지 못했어요")),
                Arguments.of("chat.alreadyExceeded", Map.of(), List.of("넘었어요", "대화로 목표 금액을 더 높게 바꿔 볼 수 있어요")),
                Arguments.of("disclaimer", Map.of(), List.of("AI 설명은 참고용이며 계산 결과가 기준이에요", "과거")),
                Arguments.of(TAX_NOTE_KEY, Map.of(), List.of("일반 과세", "비과세", "세법")),
                Arguments.of("result.alreadyExceeded", Map.of("goalAmount", "GOAL_X", "heldAssets", "HELD_Y"),
                        List.of("GOAL_X", "HELD_Y")),
                Arguments.of("input.heldAssetsRecheck", Map.of("calcStartMonth", "MONTH_Z"), List.of("MONTH_Z")),
                Arguments.of("validation.OUT_OF_RANGE", Map.of("min", "MIN_A", "max", "MAX_B"),
                        List.of("MIN_A", "MAX_B")),
                Arguments.of("validation.TOO_MANY_DECIMALS", Map.of("scale", "SCALE_C"), List.of("SCALE_C")));
    }

    private static CopyBook loadReal() {
        return CopyBook.load(COPY_PATH);
    }

    /** 실제 문구표를 키 평탄화한 원본 값으로 읽는다(로더를 거치지 않는 픽스처 재료). */
    private static Map<String, String> readRaw() throws IOException {
        Map<String, String> flat = new LinkedHashMap<>();
        flatten("", new Yaml().load(Files.readString(COPY_PATH)), flat);
        return flat;
    }

    private static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> flatten(prefix.isEmpty() ? key.toString() : prefix + "." + key, value, out));
        } else if (node != null) {
            out.put(prefix, node.toString());
        }
    }

    private static Path writeCopy(Path dir, Map<String, String> flat, String extraYaml) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setWidth(Integer.MAX_VALUE);
        Path file = dir.resolve("copy.ko.yml");
        Files.writeString(file, new Yaml(options).dump(flat) + extraYaml);
        return file;
    }

    private static Path writeCopyWithout(Path dir, String key) throws IOException {
        Map<String, String> flat = readRaw();
        flat.remove(key);
        return writeCopy(dir, flat, "");
    }

    private static Path writeCopyWithValue(Path dir, String key, String value) throws IOException {
        Map<String, String> flat = readRaw();
        flat.put(key, value);
        return writeCopy(dir, flat, "");
    }

    @Test
    @DisplayName("실제 문구표를 읽고, 복사본도 같은 방식으로 읽힌다 (CP-05, 픽스처 검증)")
    void loadsRealCopyAndItsCopy(@TempDir Path dir) throws IOException {
        CopyBook real = loadReal();
        CopyBook copied = CopyBook.load(writeCopy(dir, readRaw(), ""));

        assertThat(copied.keys()).isEqualTo(real.keys());
    }

    @Test
    @DisplayName("keys는 문구표의 평탄화된 키 전체를 돌려주고 필수 키를 모두 포함한다 (CP-05)")
    void exposesAllFlattenedKeys() throws IOException {
        CopyBook copyBook = loadReal();

        assertThat(copyBook.keys()).containsExactlyInAnyOrderElementsOf(readRaw().keySet());
        assertThat(copyBook.keys()).containsAll(requiredKeys());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("verbatimSentences")
    @DisplayName("고정 문장을 글자 그대로 읽는다 (Spring·ai-server 같은 문구)")
    void readsVerbatimSentence(String key, Map<String, String> params, String expected) {
        assertThat(loadReal().get(key, params)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sentenceFragments")
    @DisplayName("문장이 요구한 내용과 자리 표시자를 담는다 (D-056, D-066, D-067, D-073, C12a-7-1)")
    void containsRequiredFragments(String key, Map<String, String> params, List<String> fragments) {
        String sentence = loadReal().get(key, params);

        assertThat(sentence).contains(fragments.toArray(String[]::new)).doesNotContain("{", "}");
    }

    @Test
    @DisplayName("tax.note는 금융기관 또는 세무 전문가 확인 안내를 담는다 (D-067)")
    void taxNoteGuidesToProfessional() {
        String note = loadReal().get(TAX_NOTE_KEY);

        assertThat(note.contains("금융기관") || note.contains("세무 전문가")).isTrue();
    }

    @Test
    @DisplayName("대화로 못 바꾸는 조건 안내는 범위 밖 안내와 키도 값도 다르다 (D-056)")
    void separatesUnchangeableConditionFromOutOfScope() {
        CopyBook copyBook = loadReal();

        assertThat("chat.unchangeableCondition").isNotEqualTo("outOfScope");
        assertThat(copyBook.get("chat.unchangeableCondition")).isNotEqualTo(copyBook.get("outOfScope"));
    }

    @Test
    @DisplayName("범위 밖 세금 안내는 일반 범위 밖 안내와 다른 키·값이다 (D-073)")
    void separatesTaxOutOfScopeFromGeneral() {
        CopyBook copyBook = loadReal();

        assertThat(copyBook.keys()).contains("outOfScope", "outOfScope.tax");
        assertThat(copyBook.get("outOfScope.tax")).isNotEqualTo(copyBook.get("outOfScope"));
    }

    @Test
    @DisplayName("결과 화면 초과 안내와 대화창 초과 안내는 합치지 않는다 (C12a-7-1, D-072)")
    void keepsResultAndChatAlreadyExceededApart() {
        CopyBook copyBook = loadReal();

        assertThat(copyBook.keys()).contains("result.alreadyExceeded", "chat.alreadyExceeded");
        String result = copyBook.get("result.alreadyExceeded", Map.of("goalAmount", "1", "heldAssets", "2"));
        assertThat(result).isNotEqualTo(copyBook.get("chat.alreadyExceeded"));
    }

    @Test
    @DisplayName("면책은 disclaimer 키 하나이고 chat.disclaimer는 없으며 한 줄이다 (D-066)")
    void hasSingleOneLineDisclaimer() {
        CopyBook copyBook = loadReal();

        assertThat(copyBook.keys()).contains("disclaimer").doesNotContain("chat.disclaimer");
        assertThat(copyBook.get("disclaimer")).doesNotContain("\n");
        assertThatThrownBy(() -> copyBook.get("chat.disclaimer")).isInstanceOf(CopyKeyMissingException.class);
    }

    @Test
    @DisplayName("ExcludedItem의 모든 코드에 비어 있지 않은 문장이 있다 (G-14, enum이 늘면 실패)")
    void hasSentenceForEveryExcludedItem() {
        CopyBook copyBook = loadReal();

        for (ExcludedItem item : ExcludedItem.values()) {
            assertThat(copyBook.get("excluded." + item.name())).as(item.name()).isNotBlank();
        }
    }

    @Test
    @DisplayName("ScenarioMethod의 모든 코드에 비어 있지 않은 문장이 있다 (G-14, enum이 늘면 실패)")
    void hasSentenceForEveryScenarioMethod() {
        CopyBook copyBook = loadReal();

        for (ScenarioMethod method : ScenarioMethod.values()) {
            assertThat(copyBook.get("scenarioMethod." + method.name())).as(method.name()).isNotBlank();
        }
    }

    @Test
    @DisplayName("목표 종류 7개, 오류 title 7개, achievementChange 4값, 시나리오 3개에 문장이 있다")
    void hasSentenceForEveryCode() {
        CopyBook copyBook = loadReal();

        GOAL_CODES.forEach(code -> assertThat(copyBook.get("goal." + code)).as(code).isNotBlank());
        ERROR_CODES.forEach(code -> assertThat(copyBook.get("error.title." + code)).as(code).isNotBlank());
        ACHIEVEMENT_CHANGES.forEach(value -> assertThat(copyBook.get("achievementChange." + value)).isNotBlank());
        List.of("GOOD", "AVG", "BAD").forEach(code -> assertThat(copyBook.get("scenario." + code)).isNotBlank());
    }

    @Test
    @DisplayName("표시 템플릿(달성·미달·증가·감소·변화 없음)에 문장이 있다 (Formatter #28)")
    void hasDisplayTemplates() {
        CopyBook copyBook = loadReal();

        List.of("achieved", "missed", "increase", "decrease", "unchanged")
                .forEach(name -> assertThat(copyBook.get("template." + name)).as(name).isNotBlank());
    }

    @Test
    @DisplayName("문구표 전체에 추천이라는 단어가 없다 (CF-07, D-064 예시 버튼 포함)")
    void hasNoRecommendWord() throws IOException {
        assertThat(Files.readString(COPY_PATH)).doesNotContain("추천");
        CopyBook copyBook = loadReal();
        assertThat(copyBook.keys()).noneMatch(key -> key.contains("추천"));
    }

    @Test
    @DisplayName("질문 예시 버튼은 조건 4개 각각 있고 숫자·%·늘·줄 같은 값·변화량 표현이 없다 (D-064)")
    void exampleQuestionsOnlyPickCondition() {
        CopyBook copyBook = loadReal();

        for (String code : CONDITION_CODES) {
            String sentence = copyBook.get("chat.exampleQuestion." + code);
            assertThat(sentence).as(code).isNotBlank()
                    .doesNotContainPattern("\\d").doesNotContain("%", "늘", "줄", "추천");
        }
    }

    @Test
    @DisplayName("없는 키를 읽으면 빈 문구가 아니라 예외가 난다 (CP-05)")
    void failsOnUnknownKey() {
        CopyBook copyBook = loadReal();

        assertThatThrownBy(() -> copyBook.get("no.such.key")).isInstanceOf(CopyKeyMissingException.class);
        assertThatThrownBy(() -> copyBook.get("no.such.key", Map.of("v", "1")))
                .isInstanceOf(CopyKeyMissingException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requiredKeyStream")
    @DisplayName("필수 키가 하나라도 없으면 로드가 실패한다 (CP-05, 기동 시 실패)")
    void failsToLoadWhenRequiredKeyIsMissing(String key, @TempDir Path dir) throws IOException {
        Path file = writeCopyWithout(dir, key);

        assertThatThrownBy(() -> CopyBook.load(file)).isInstanceOf(CopyKeyMissingException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requiredKeyStream")
    @DisplayName("필수 키의 값이 비어 있으면 로드가 실패한다 (CP-05, 조용히 빈 문구가 되지 않음)")
    void failsToLoadWhenRequiredValueIsEmpty(String key, @TempDir Path dir) throws IOException {
        Path file = writeCopyWithValue(dir, key, "");

        assertThatThrownBy(() -> CopyBook.load(file)).isInstanceOf(CopyKeyMissingException.class);
    }

    @Test
    @DisplayName("공백뿐인 값도 빈 값으로 본다 (CP-05)")
    void failsToLoadWhenRequiredValueIsBlank(@TempDir Path dir) throws IOException {
        Path file = writeCopyWithValue(dir, "excluded.EXCHANGE_RATE", "   ");

        assertThatThrownBy(() -> CopyBook.load(file)).isInstanceOf(CopyKeyMissingException.class);
    }

    @Test
    @DisplayName("같은 키가 두 번 있으면 로드가 실패한다 (CP-05 중복 키 금지)")
    void failsToLoadOnDuplicateKey(@TempDir Path dir) throws IOException {
        Path file = writeCopy(dir, readRaw(), "\"disclaimer\": \"다시 쓴 면책\"\n");

        assertThatThrownBy(() -> CopyBook.load(file)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("중첩 키를 펼친 결과가 다른 키와 겹치면 로드가 실패한다 (CP-05 중복 키 금지)")
    void failsToLoadWhenFlattenedKeysCollide(@TempDir Path dir) throws IOException {
        Path file = writeCopy(dir, readRaw(), "excluded:\n  EXCHANGE_RATE: \"겹치는 문장\"\n");

        assertThatThrownBy(() -> CopyBook.load(file)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("YAML 문법이 잘못되면 로드가 실패한다 (예외)")
    void failsToLoadOnMalformedYaml(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("copy.ko.yml");
        Files.writeString(file, "disclaimer: [열리고 닫히지 않은 목록\n");

        assertThatThrownBy(() -> CopyBook.load(file)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("자리 표시자를 값으로 바꾼다 (치환)")
    void substitutesPlaceholders() {
        CopyBook copyBook = loadReal();

        assertThat(copyBook.get("unit.month", Map.of("y", "1", "m", "0"))).isEqualTo("1년 0개월");
        assertThat(copyBook.get("unit.manwon", Map.of("v", "1,000"))).isEqualTo("1,000만원");
    }

    @Test
    @DisplayName("치환 값이 하나라도 빠지면 IllegalArgumentException이다 (조용히 자리 표시자가 남지 않음)")
    void failsWhenPlaceholderValueIsMissing() {
        CopyBook copyBook = loadReal();

        assertThatIllegalArgumentException().isThrownBy(() -> copyBook.get("unit.month", Map.of("y", "1")));
        assertThatIllegalArgumentException().isThrownBy(() -> copyBook.get("unit.manwon", Map.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> copyBook.get("validation.OUT_OF_RANGE", Map.of("min", "1")));
    }
}
