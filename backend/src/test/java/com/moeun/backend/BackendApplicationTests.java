package com.moeun.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.moeun.backend.config.TestcontainersConfig;

@SpringBootTest
@Import(TestcontainersConfig.class)
class BackendApplicationTests {

    @Test
    void contextLoads() {
    }

}
