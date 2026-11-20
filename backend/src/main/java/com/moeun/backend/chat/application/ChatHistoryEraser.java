package com.moeun.backend.chat.application;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.moeun.backend.auth.MemberLoggedIn;
import com.moeun.backend.auth.MemberLoggedOut;
import com.moeun.backend.chat.domain.ChatMessageRepository;

/**
 * 로그인·로그아웃 때 그 회원의 이전 대화를 지운다(Q-041 권장안 A, #46).
 * 동기 {@code @EventListener}다: 발행 호출이 반환될 때 삭제가 끝나 있어 로그인 직후 복원이 삭제보다 먼저 도는 경쟁이
 * 없다. 실패하면 예외가 발행한 쪽으로 올라가 그 요청도 실패한다(삼키지 않는다). 같은 이벤트를 두 번 받아도 결과가 같다.
 */
@Component
public class ChatHistoryEraser {

    private final ChatMessageRepository messages;

    ChatHistoryEraser(ChatMessageRepository messages) {
        this.messages = messages;
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
}
