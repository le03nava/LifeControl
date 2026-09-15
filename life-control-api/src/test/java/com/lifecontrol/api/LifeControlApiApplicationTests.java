package com.lifecontrol.api;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LifeControlApiApplicationTests extends AbstractPostgresIntegrationTest {

    @Test
    void contextLoads() {
    }

}
