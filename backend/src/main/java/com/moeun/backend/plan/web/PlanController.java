package com.moeun.backend.plan.web;

import java.util.List;
import java.util.Set;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.FieldError;
import com.moeun.backend.plan.PlanSaveResult;
import com.moeun.backend.plan.application.PlanService;
import com.moeun.backend.plan.application.PlanValidationException;

@RestController
@RequestMapping("/api/plan")
class PlanController {

    private static final Set<ChangePath> APPLY_SOURCES = Set.of(ChangePath.ALTERNATIVE, ChangePath.COMPARE,
            ChangePath.CHAT);

    private final PlanService planService;

    PlanController(PlanService planService) {
        this.planService = planService;
    }

    // 계약 P-6은 경로만 예약돼 있고 요청 모양·오류는 #68이 확정한다.
    @PostMapping("/apply")
    PlanSaveResult apply(@AuthenticationPrincipal Jwt jwt, @RequestBody ApplyRequest request) {
        if (request.source() == null || !APPLY_SOURCES.contains(request.source())) {
            throw new PlanValidationException(List.of(request.source() == null
                    ? FieldError.required("source")
                    : FieldError.notChangeable("source")));
        }
        return planService.apply(memberId(jwt), request.source(), request.changes(), requireRevision(
                request.expectedRevision()));
    }

    @PostMapping("/undo")
    RevisionResponse undo(@AuthenticationPrincipal Jwt jwt, @RequestBody UndoRequest request) {
        long revision = planService.undo(memberId(jwt), requireRevision(request.expectedRevision()));
        return new RevisionResponse(revision);
    }

    record RevisionResponse(long revision) {
    }

    private static long requireRevision(Long revision) {
        if (revision == null) {
            throw new PlanValidationException(List.of(FieldError.required("expectedRevision")));
        }
        return revision;
    }

    private static long memberId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
