package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.moeun.backend.chat.domain.ChatMessageRepository;

/** 리뷰 반영(#41 라운드 2): 고정 안내 문구는 문구표에서 가져오고, citation은 그 회원의 메시지에만 저장한다. */
class ChatReviewFixesTest extends ChatIntegrationTestSupport {

    @Autowired
    ChatMessageRepository messages;

    @Test
    void validationDetailComesFromCopyBook() throws Exception {
        askText(cookie, "   ")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(copy.get("error.title.VALIDATION_FAILED")));
    }

    @Test
    void answerPendingBodyCarriesNoHardcodedSentence() throws Exception {
        askText(cookie, "첫 질문").andExpect(status().isAccepted());

        askText(cookie, "두 번째 질문")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_PENDING"))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    void citationsAreNotInsertedForAnotherMembersMessage() {
        List<Long> chunks = insertChunks();
        long othersMessage = insertMessage(otherMemberId, "ASSISTANT", "ANSWER", "COMPLETED", "남의 답");

        messages.insertCitations(memberId, othersMessage, chunks);

        assertThat(citationCount(othersMessage)).isZero();
    }

    @Test
    void citationsAreInsertedInRankOrderForOwnMessage() {
        List<Long> chunks = insertChunks();
        long own = insertMessage(memberId, "ASSISTANT", "ANSWER", "COMPLETED", "내 답");

        messages.insertCitations(memberId, own, chunks);

        List<Long> cited = jdbc.sql("SELECT chunk_id FROM chat_message_citation WHERE message_id = ? ORDER BY rank")
                .param(own).query(Long.class).list();
        assertThat(cited).containsExactlyElementsOf(chunks);
    }

    private long citationCount(long messageId) {
        return jdbc.sql("SELECT count(*) FROM chat_message_citation WHERE message_id = ?").param(messageId)
                .query(Long.class).single();
    }
}
