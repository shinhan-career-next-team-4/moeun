package com.moeun.backend.plan.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.PlanApplyService;
import com.moeun.backend.plan.application.PlanUndoService;

import tools.jackson.databind.JsonNode;

/**
 * 되돌리기 REST(P-5)와 대안·비교 적용 REST(P-6, #68). 대화 적용은 chat 모듈이 PlanApplyService를 직접 부른다(F-5).
 * P-6은 본문 형식(검사 1)만 여기서 보고, 계획 없음·조건 검증·revision·저장은 PlanApplyService가 한다(C12a-9-1).
 */
@RestController
@RequestMapping("/api/plan")
class PlanController {

    // 대화(CHAT)는 F-5, 입력 수정(INPUT_EDIT)은 P-3이 맡는다
    private static final Set<ChangePath> APPLY_PATHS = Set.of(ChangePath.COMPARE, ChangePath.ALTERNATIVE);

    private final PlanUndoService undoService;
    private final PlanApplyService applyService;

    PlanController(PlanUndoService undoService, PlanApplyService applyService) {
        this.undoService = undoService;
        this.applyService = applyService;
    }

    @PostMapping("/undo")
    RevisionResponse undo(@AuthenticationPrincipal Jwt jwt, @RequestBody UndoRequest request) {
        long revision = undoService.undo(memberId(jwt), requireRevision(request.expectedRevision()));
        return new RevisionResponse(revision);
    }

    @PostMapping("/apply")
    RevisionResponse apply(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode body) {
        if (!body.isObject()) {
            throw new MalformedPlanRequestException();
        }
        long revision = applyService.apply(memberId(jwt), applyPath(body.get("path")),
                conditionChanges(body.get("conditionChanges")), expectedRevision(body.get("expectedRevision")),
                null).revision();
        return new RevisionResponse(revision);
    }

    record RevisionResponse(long revision) {
    }

    private static long requireRevision(Long revision) {
        if (revision == null) {
            throw new MalformedPlanRequestException();
        }
        return revision;
    }

    private static ChangePath applyPath(JsonNode node) {
        if (node == null || !node.isString()) {
            throw new MalformedPlanRequestException();
        }
        return APPLY_PATHS.stream().filter(path -> path.name().equals(node.asString())).findFirst()
                .orElseThrow(MalformedPlanRequestException::new);
    }

    private static long expectedRevision(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new MalformedPlanRequestException();
        }
        return node.longValue();
    }

    /** 키는 API와 같은 camelCase다. 허용 키·값 검증은 PlanApplyService가 한다. */
    private static Map<String, JsonNode> conditionChanges(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new MalformedPlanRequestException();
        }
        Map<String, JsonNode> changes = new LinkedHashMap<>();
        node.properties().forEach(entry -> changes.put(entry.getKey(), entry.getValue()));
        return changes;
    }

    private static long memberId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
