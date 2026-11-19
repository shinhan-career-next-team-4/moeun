package com.moeun.backend.plan.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.plan.application.PlanUndoService;

/**
 * 되돌리기 REST(P-5). 대안·비교 적용 REST(P-6 POST /api/plan/apply)는 Should라서 #68이 맡는다.
 * 적용은 chat 모듈이 PlanApplyService를 직접 부른다(F-5).
 */
@RestController
@RequestMapping("/api/plan")
class PlanController {

    private final PlanUndoService undoService;

    PlanController(PlanUndoService undoService) {
        this.undoService = undoService;
    }

    @PostMapping("/undo")
    RevisionResponse undo(@AuthenticationPrincipal Jwt jwt, @RequestBody UndoRequest request) {
        long revision = undoService.undo(memberId(jwt), requireRevision(request.expectedRevision()));
        return new RevisionResponse(revision);
    }

    record RevisionResponse(long revision) {
    }

    private static long requireRevision(Long revision) {
        if (revision == null) {
            throw new MalformedUndoRequestException();
        }
        return revision;
    }

    private static long memberId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
