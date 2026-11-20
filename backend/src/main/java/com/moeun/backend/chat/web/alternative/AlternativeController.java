package com.moeun.backend.chat.web.alternative;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moeun.backend.calc.AlternativeQuery;

/**
 * 대안 값 산출(FR-ALT-01·03). 허용 범위 밖 기간은 422 VALIDATION_FAILED, 계획이 없으면 409 NO_PLAN이고 오류 본문은
 * plan의 공통 처리기가 만든다. 본문이 비어 있으면 기간 없이 요청한 것으로 본다.
 */
@RestController
@RequestMapping("/api/alternatives")
class AlternativeController {

    private final AlternativeQuery alternatives;

    AlternativeController(AlternativeQuery alternatives) {
        this.alternatives = alternatives;
    }

    @PostMapping
    AlternativeResponse find(@AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) AlternativeRequest request) {
        Integer months = request == null ? null : request.targetPeriodMonths();
        return AlternativeResponse.from(alternatives.find(Long.parseLong(jwt.getSubject()), months));
    }
}
