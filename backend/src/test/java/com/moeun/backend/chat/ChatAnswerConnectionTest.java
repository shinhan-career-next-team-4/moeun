package com.moeun.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.zaxxer.hikari.HikariDataSource;

/** ai-server 호출을 기다리는 동안 리스너가 DB 커넥션을 붙들지 않는다(트랜잭션이 없어도 커넥션은 묶일 수 있다). */
class ChatAnswerConnectionTest extends ChatIntegrationTestSupport {

    @Autowired
    DataSource dataSource;

    @Test
    void noPooledConnectionIsHeldWhileWaitingForAiServer() throws Exception {
        long assistantId = askAndGetAssistantId(cookie, "커넥션 확인");
        awaitAiRequests(1);

        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();

        AI.release(200, AiServerStub.ANSWER_OK);
        awaitStatus(assistantId, "COMPLETED");
    }
}
