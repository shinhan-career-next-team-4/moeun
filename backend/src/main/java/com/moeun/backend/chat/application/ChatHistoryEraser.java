package com.moeun.backend.chat.application;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.auth.DemoReset;
import com.moeun.backend.auth.MemberLoggedIn;
import com.moeun.backend.auth.MemberLoggedOut;
import com.moeun.backend.chat.domain.ChatMessageRepository;
import com.moeun.backend.plan.PlanEraser;

/**
 * 로그인·로그아웃 때 그 회원의 이전 대화를 지운다(Q-041 권장안 A, #46).
 * 동기 {@code @EventListener}다: 발행 호출이 반환될 때 삭제가 끝나 있어 로그인 직후 복원이 삭제보다 먼저 도는 경쟁이
 * 없다. 실패하면 예외가 발행한 쪽으로 올라가 그 요청도 실패한다(삼키지 않는다). 같은 이벤트를 두 번 받아도 결과가 같다.
 */
@Component
public class ChatHistoryEraser {

    private final ChatMessageRepository messages;
    private final PlanEraser plans;

    ChatHistoryEraser(ChatMessageRepository messages, PlanEraser plans) {
        this.messages = messages;
        this.plans = plans;
    }

    @EventListener
    @Transactional
    public void onLoggedIn(MemberLoggedIn event) {
        messages.deleteAllOf(event.memberId());
    }

    @EventListener
    @Transactional
    public void onLoggedOut(MemberLoggedOut event) {
        messages.deleteAllOf(event.memberId());
    }

    /**
     * 데모 초기화. 대화를 먼저 지우고 계획을 지운다. 대화(chat_message)가 calc_run을 참조하기 때문이다.
     * plan 모듈은 다른 모듈에 의존하지 않으므로 이 순서는 의존 방향이 맞는 chat이 맡는다.
     */
    @EventListener
    @Transactional
    public void onDemoReset(DemoReset event) {
        messages.deleteAllOf(event.memberId());
        plans.deleteAllOf(event.memberId());
    }
}
