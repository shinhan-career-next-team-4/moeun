package com.moeun.backend.plan.web;

import java.util.List;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.plan.ChangePath;
import com.moeun.backend.plan.FieldError;
import com.moeun.backend.plan.application.PlanInput;
import com.moeun.backend.plan.application.PlanService;
import com.moeun.backend.plan.application.PlanValidationException;
import com.moeun.backend.plan.application.PlanView;

@RestController
@RequestMapping("/api/plan")
class PlanController {

    private static final Set<ChangePath> APPLY_SOURCES = Set.of(ChangePath.ALTERNATIVE, ChangePath.COMPARE,
            ChangePath.CHAT);

    private final PlanService planService;

    PlanController(PlanService planService) {
        this.planService = planService;
    }

    @GetMapping
    ResponseEntity<PlanView> get(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(planService.find(memberId(jwt)));
    }

    @PostMapping
    PlanView save(@AuthenticationPrincipal Jwt jwt, @RequestBody PlanInput input) {
        return planService.save(memberId(jwt), input);
    }

    @PostMapping("/apply")
    PlanView apply(@AuthenticationPrincipal Jwt jwt, @RequestBody ApplyRequest request) {
        if (request.source() == null || !APPLY_SOURCES.contains(request.source())) {
            throw new PlanValidationException(List.of(request.source() == null
                    ? FieldError.required("source")
                    : FieldError.notChangeable("source")));
        }
        return planService.apply(memberId(jwt), request.source(), request.changes(), requireRevision(
                request.expectedRevision()));
    }

    @PostMapping("/undo")
    PlanView undo(@AuthenticationPrincipal Jwt jwt, @RequestBody UndoRequest request) {
        return planService.undo(memberId(jwt), requireRevision(request.expectedRevision()));
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
