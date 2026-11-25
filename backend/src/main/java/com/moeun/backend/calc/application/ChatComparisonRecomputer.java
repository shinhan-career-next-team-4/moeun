package com.moeun.backend.calc.application;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.calc.ChatComparisonQuery;
import com.moeun.backend.calc.ChatComparisonView;
import com.moeun.backend.calc.domain.RunType;

import tools.jackson.databind.JsonNode;

/**
 * 대화 비교 기록 재계산(H-8b, C12b-6). calc_run(CHAT)이 남긴 계획 버전·기준 데이터 버전에 그때의 바꾼 조건을 얹어 다시
 * 계산한다. 결과는 저장하지 않고 기록도 새로 남기지 않는다(ADR-002).
 */
@Service
public class ChatComparisonRecomputer implements ChatComparisonQuery {

    private final CalcRunReplayer replayer;
    private final CompareConditionsUseCase compareConditionsUseCase;

    ChatComparisonRecomputer(CalcRunReplayer replayer, CompareConditionsUseCase compareConditionsUseCase) {
        this.replayer = replayer;
        this.compareConditionsUseCase = compareConditionsUseCase;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findChatRunPlanVersion(long memberId, long calcRunId) {
        return replayer.findOwnedRun(memberId, calcRunId, RunType.CHAT).map(owned -> owned.run().getPlanVersionId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ChatComparisonView> recompute(long memberId, long calcRunId,
            Map<String, JsonNode> conditionChanges, Map<String, JsonNode> baselineChanges) {
        Objects.requireNonNull(conditionChanges, "conditionChanges");
        Objects.requireNonNull(baselineChanges, "baselineChanges");
        return replayer.findOwnedRun(memberId, calcRunId, RunType.CHAT).map(owned -> {
            ConditionChanges.Conversion changes = toConversion(conditionChanges);
            // 비교 기준 변경(#418)을 기록의 계획 버전에 얹어 current로 삼는다. /internal/calc와 같은 방식이다
            ConditionChanges.Conversion baseline = ConditionChanges.withoutNulls(baselineChanges).isEmpty()
                    ? null : toConversion(baselineChanges);
            // calc_run에는 기준 시나리오가 없어 지금 계획의 기준 시나리오를 쓴다. 해커톤 범위에는 기준 시나리오를 바꾸는
            // 기능이 없어 /internal/calc 때와 같다. 그 기능이 생기면 대화 메시지에 저장한 값을 인자로 받아야 한다
            CalcRunReplayer.RecordedData data = replayer.loadRecordedData(owned.run());
            CompareConditionsUseCase.ComputedComparison computed = compareConditionsUseCase.computeComparison(
                    BaselinePlan.of(owned.plan(), baseline), changes.override(), data.params(), data.sp500(),
                    data.changedEtf(), changes.etfProductCode(), data.defaultRate());
            return new ChatComparisonView(owned.run().getPlanVersionId(), computed.comparison(), computed.display());
        });
    }

    /**
     * 저장된 대화의 바꾼 조건을 타입만 바꾼다. 범위는 검사하지 않는다(C12b-6). null 값은 바꾸지 않은 것으로 본다.
     * 목표 이름(#345)은 계산에 쓰지 않아 받기만 하고 버린다.
     * 변환은 {@code /internal/calc}와 같은 {@link ConditionChanges}를 쓰고, 맞지 않는 값은 모두 예외로 낸다.
     *
     * @throws IllegalArgumentException 허용 7개 밖의 키, 타입이 맞지 않는 값, 정수 자리의 소수, 타입 범위를 넘는 정수
     */
    private static ConditionChanges.Conversion toConversion(Map<String, JsonNode> conditionChanges) {
        Map<String, JsonNode> changes = ConditionChanges.withoutNulls(conditionChanges);
        if (!ConditionChanges.unchangeableKeys(changes).isEmpty()) {
            throw new IllegalArgumentException("condition is not changeable: "
                    + ConditionChanges.unchangeableKeys(changes));
        }
        if (!ConditionChanges.allValuesWellTyped(changes)) {
            throw new IllegalArgumentException("condition value has a wrong type");
        }
        ConditionChanges.Conversion conversion = ConditionChanges.convert(changes);
        if (!conversion.isExact()) {
            throw new IllegalArgumentException("condition value cannot be converted: "
                    + conversion.notIntegerKeys() + conversion.outOfTypeRangeKeys());
        }
        return conversion;
    }
}
